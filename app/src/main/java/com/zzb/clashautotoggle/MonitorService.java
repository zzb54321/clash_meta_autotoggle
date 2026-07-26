package com.zzb.clashautotoggle;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TransportInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Watches Wi-Fi connectivity and toggles ClashMeta accordingly.
 *
 * <p>The service is intentionally cheap: it never polls. It only registers a
 * {@link ConnectivityManager.NetworkCallback}, so the process stays idle until
 * the system pushes a Wi-Fi connect/disconnect event. Events are additionally
 * debounced and de-duplicated so a roaming device does not repeatedly wake up
 * ClashMeta.
 */
public class MonitorService extends Service {

    private static final String CHANNEL_ID = "monitor";
    private static final int NOTIFICATION_ID = 1;
    /** Coalesce the burst of callbacks emitted while a network is settling. */
    private static final long DEBOUNCE_MS = 2000L;
    /** Delay after which the effect of a sent command is verified. */
    private static final long VERIFY_MS = 6000L;

    /** Callback used by {@link MainActivity} to refresh its status line. */
    public interface StatusListener {
        void onStatusChanged();
    }

    private static StatusListener statusListener;
    private static volatile String currentSsid;
    private static volatile boolean running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable evaluateTask = this::evaluate;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback callback;
    private ConnectivityManager.NetworkCallback defaultCallback;
    private Settings settings;
    private static volatile String statusText;

    public static boolean isRunning() {
        return running;
    }

    public static String getCurrentSsid() {
        return currentSsid;
    }

    /** Human readable description of the last evaluation. */
    public static String getStatusText() {
        return statusText;
    }

    public static void setStatusListener(StatusListener listener) {
        statusListener = listener;
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, MonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, MonitorService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        settings = new Settings(this);
        connectivityManager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        createChannel();
        startForegroundCompat(getString(R.string.status_starting));
        registerCallback();
        running = true;
        AppLog.i(this, "监听服务已启动，客户端=" + settings.getClashPackage()
                + " 类型=" + settings.getClientType());
        notifyListener();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Re-evaluate on every explicit start (settings changed, boot, ...).
        AppLog.i(this, "收到启动请求（设置变更 / 开机 / 系统重启服务）");
        scheduleEvaluate();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(evaluateTask);
        handler.removeCallbacksAndMessages(null);
        unregister(callback);
        callback = null;
        unregister(defaultCallback);
        defaultCallback = null;
        running = false;
        AppLog.i(this, "监听服务已停止");
        currentSsid = null;
        super.onDestroy();
        notifyListener();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void registerCallback() {
        if (connectivityManager == null || callback != null) {
            return;
        }
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                onNetworkEvent("WiFi 已连接");
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                // Too chatty to log: only schedule the (debounced) evaluation.
                scheduleEvaluate();
            }

            @Override
            public void onLost(Network network) {
                onNetworkEvent("WiFi 已断开");
            }
        };
        try {
            connectivityManager.registerNetworkCallback(request, callback);
        } catch (SecurityException e) {
            AppLog.i(this, "注册 WiFi 网络回调失败: " + e);
            callback = null;
        }

        // The Wi-Fi callback alone can be delayed by some vendor ROMs; watching
        // the default network as well makes the switch to / from mobile data
        // visible immediately.
        defaultCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                onNetworkEvent("默认网络已切换");
            }

            @Override
            public void onLost(Network network) {
                onNetworkEvent("默认网络已断开");
            }
        };
        try {
            connectivityManager.registerDefaultNetworkCallback(defaultCallback);
        } catch (Exception e) {
            AppLog.i(this, "注册默认网络回调失败: " + e);
            defaultCallback = null;
        }
    }

    private void unregister(ConnectivityManager.NetworkCallback target) {
        if (target == null || connectivityManager == null) {
            return;
        }
        try {
            connectivityManager.unregisterNetworkCallback(target);
        } catch (IllegalArgumentException ignored) {
            // Already unregistered.
        }
    }

    private void onNetworkEvent(String description) {
        AppLog.i(this, "网络事件：" + description);
        scheduleEvaluate();
    }

    private void scheduleEvaluate() {
        handler.removeCallbacks(evaluateTask);
        handler.postDelayed(evaluateTask, DEBOUNCE_MS);
    }

    private void evaluate() {
        String ssid = readConnectedSsid();
        currentSsid = ssid;

        int action = settings.resolveAction(ssid);
        String packageName = settings.getClashPackage();
        boolean vpnActive = ClashController.isVpnActive(this);
        String detail;

        AppLog.i(this, "开始判定：WiFi=" + (ssid == null ? "无/未知" : ssid)
                + " 规则动作=" + actionName(action)
                + " 上次下发=" + actionName(settings.getLastApplied())
                + " VPN当前=" + (vpnActive ? "运行中" : "未运行"));

        if (action == Settings.ACTION_KEEP) {
            detail = getString(R.string.status_no_action);
            AppLog.i(this, "判定结果：不改变客户端状态");
        } else if (!ClashController.isInstalled(this, packageName)) {
            detail = getString(R.string.status_clash_missing);
            AppLog.i(this, "判定结果：未安装客户端 " + packageName);
        } else {
            boolean wantRunning = action == Settings.ACTION_ENABLE;
            boolean alreadyApplied = settings.getLastApplied() == action;
            if (vpnActive == wantRunning && alreadyApplied) {
                detail = wantRunning
                        ? getString(R.string.status_already_on)
                        : getString(R.string.status_already_off);
                AppLog.i(this, "判定结果：状态已符合预期，无需下发");
            } else if (ClashController.apply(this, packageName, settings.getClientType(),
                    wantRunning)) {
                settings.setLastApplied(action);
                detail = wantRunning
                        ? getString(R.string.status_started)
                        : getString(R.string.status_stopped);
                scheduleVerify(action);
            } else {
                detail = getString(R.string.status_failed);
                // Do not remember a command that was never delivered, otherwise
                // the next evaluation would wrongly consider it applied.
                settings.setLastApplied(Settings.ACTION_KEEP);
            }
        }

        statusText = getString(R.string.status_format,
                ssid == null ? getString(R.string.ssid_none) : ssid, detail);
        updateNotification(statusText);
        notifyListener();
    }

    /**
     * Checks a moment later whether the command actually took effect. A
     * background activity start can be dropped by the system without throwing,
     * so this is the only reliable way to notice it.
     */
    private void scheduleVerify(final int action) {
        final boolean wantRunning = action == Settings.ACTION_ENABLE;
        handler.postDelayed(() -> {
            boolean vpnActive = ClashController.isVpnActive(this);
            if (vpnActive == wantRunning) {
                AppLog.i(this, "校验：指令已生效，VPN " + (vpnActive ? "运行中" : "未运行"));
                return;
            }
            AppLog.i(this, "校验：指令未生效（期望 " + (wantRunning ? "运行中" : "未运行")
                    + "，实际 " + (vpnActive ? "运行中" : "未运行")
                    + "）。常见原因：未授予“显示在其他应用上层”权限、客户端未授权 VPN、"
                    + "或客户端未选择配置文件");
            // Forget the command so the next network event retries it.
            if (settings.getLastApplied() == action) {
                settings.setLastApplied(Settings.ACTION_KEEP);
            }
            statusText = getString(R.string.status_format,
                    currentSsid == null ? getString(R.string.ssid_none) : currentSsid,
                    getString(R.string.status_not_applied));
            updateNotification(statusText);
            notifyListener();
        }, VERIFY_MS);
    }

    private String actionName(int action) {
        switch (action) {
            case Settings.ACTION_ENABLE:
                return "启用";
            case Settings.ACTION_DISABLE:
                return "停用";
            default:
                return "不改变";
        }
    }

    /**
     * @return the SSID of the connected Wi-Fi network, or {@code null} when not
     *         connected to Wi-Fi or when the SSID cannot be read (missing
     *         location permission or location services turned off).
     */
    private String readConnectedSsid() {
        if (connectivityManager == null) {
            return null;
        }
        for (Network network : connectivityManager.getAllNetworks()) {
            NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                continue;
            }
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                continue;
            }
            String ssid = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                TransportInfo info = caps.getTransportInfo();
                if (info instanceof WifiInfo) {
                    ssid = normalizeSsid(((WifiInfo) info).getSSID());
                }
            }
            if (ssid == null) {
                ssid = readSsidFromWifiManager();
            }
            if (ssid != null) {
                return ssid;
            }
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private String readSsidFromWifiManager() {
        WifiManager wifiManager =
                (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) {
            return null;
        }
        try {
            WifiInfo info = wifiManager.getConnectionInfo();
            return info == null ? null : normalizeSsid(info.getSSID());
        } catch (SecurityException e) {
            return null;
        }
    }

    static String normalizeSsid(String raw) {
        if (raw == null) {
            return null;
        }
        String ssid = raw;
        if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length() - 1);
        }
        if (ssid.isEmpty() || ssid.equals("<unknown ssid>") || ssid.equals("0x")) {
            return null;
        }
        return ssid;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_MIN);
        channel.setShowBadge(false);
        channel.setDescription(getString(R.string.channel_description));
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent content = PendingIntent.getActivity(this, 0, intent, flags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(content)
                .setOngoing(true)
                .setShowWhen(false);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setPriority(Notification.PRIORITY_MIN);
        }
        return builder.build();
    }

    private void startForegroundCompat(String text) {
        Notification notification = buildNotification(text);
        statusText = text;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
                return;
            } catch (Exception e) {
                // Location permission not granted yet: fall back to an untyped
                // foreground service, SSID detection may then be limited.
                AppLog.i(this, "前台服务降级为无类型（可能缺少位置权限）: " + e);
            }
        }
        startForeground(NOTIFICATION_ID, notification);
    }

    private void updateNotification(String text) {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private void notifyListener() {
        StatusListener listener = statusListener;
        if (listener != null) {
            handler.post(listener::onStatusChanged);
        }
    }
}
