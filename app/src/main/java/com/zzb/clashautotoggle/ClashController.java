package com.zzb.clashautotoggle;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;

/**
 * Drives a Clash client through its public "external control" intents.
 *
 * <p>ClashMetaForAndroid exposes {@code ExternalControlActivity} with the
 * {@code <package>.action.START_CLASH} / {@code STOP_CLASH} actions, while
 * FlClash exposes {@code TempActivity} with the {@code <package>.action.START}
 * / {@code STOP} actions. Both are invisible activities that finish immediately,
 * so the switch is silent as long as the intent does not drag an existing task
 * of the client to the foreground (see {@link #buildIntent}).
 */
public final class ClashController {

    private static final String ACTION_START_SUFFIX = ".action.START_CLASH";
    private static final String ACTION_STOP_SUFFIX = ".action.STOP_CLASH";
    private static final String FLCLASH_ACTION_START_SUFFIX = ".action.START";
    private static final String FLCLASH_ACTION_STOP_SUFFIX = ".action.STOP";

    /** How long the helper overlay window stays up around the activity start. */
    private static final long OVERLAY_MS = 1000L;

    private ClashController() {
    }

    public static boolean isInstalled(Context context, String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * @return the actions to try, in order of preference. A custom client may
     *         be a fork of either app, so both conventions are candidates and
     *         the one the package actually declares is picked at send time.
     */
    static String[] actions(int clientType, String packageName, boolean start) {
        String cmfa = packageName + (start ? ACTION_START_SUFFIX : ACTION_STOP_SUFFIX);
        String flClash =
                packageName + (start ? FLCLASH_ACTION_START_SUFFIX : FLCLASH_ACTION_STOP_SUFFIX);
        if (clientType == Settings.CLIENT_FLCLASH) {
            return new String[]{flClash};
        }
        if (clientType == Settings.CLIENT_CUSTOM) {
            return new String[]{cmfa, flClash};
        }
        return new String[]{cmfa};
    }

    /**
     * @return {@code true} when the intent could be delivered.
     */
    public static boolean apply(Context context, String packageName, int clientType,
                                boolean start) {
        String[] candidates = actions(clientType, packageName, start);
        Intent intent = null;
        String target = null;
        for (String action : candidates) {
            Intent candidate = buildIntent(action, packageName);
            if (intent == null) {
                intent = candidate;
            }
            ResolveInfo info = context.getPackageManager().resolveActivity(candidate, 0);
            if (info != null && info.activityInfo != null) {
                intent = candidate;
                target = info.activityInfo.name;
                break;
            }
        }
        AppLog.i(context, "下发指令 action=" + intent.getAction()
                + " 目标=" + (target != null ? target : "未解析到（包名或客户端版本可能不匹配）"));

        // Android restricts activity starts from the background. Holding a
        // window on screen is one of the documented exemptions, so a 1x1
        // transparent overlay is put up around the call when the "display over
        // other apps" permission was granted. Without it the system silently
        // drops the start and the client is never toggled while the phone is
        // locked / this app is in the background.
        View overlay = addOverlay(context);
        try {
            context.startActivity(intent);
            AppLog.i(context, "已发送 " + (start ? "启动" : "停止") + " 指令"
                    + (overlay != null ? "（已借助悬浮窗规避后台限制）" : "（未使用悬浮窗）"));
            return true;
        } catch (Exception e) {
            // ActivityNotFoundException (client missing / action unsupported) or
            // SecurityException (background activity start blocked).
            AppLog.i(context, "发送指令失败: " + e);
            return false;
        } finally {
            removeOverlay(context, overlay);
        }
    }

    private static Intent buildIntent(String action, String packageName) {
        Intent intent = new Intent(action);
        intent.setPackage(packageName);
        // FLAG_ACTIVITY_MULTIPLE_TASK is what keeps the switch silent: without
        // it FLAG_ACTIVITY_NEW_TASK reuses the client's existing task and brings
        // its main window to the foreground, which is why the FlClash / CMFA UI
        // popped up on every toggle. With it the invisible control activity runs
        // in a task of its own and nothing becomes visible.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_NO_USER_ACTION
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        return intent;
    }

    /**
     * @return the overlay view that was added, or {@code null} when no overlay
     *         could be shown (permission missing or not on a looper thread).
     */
    private static View addOverlay(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !android.provider.Settings.canDrawOverlays(context)) {
            AppLog.i(context, "未授予“显示在其他应用上层”权限，后台切换可能被系统拦截");
            return null;
        }
        if (Looper.myLooper() == null) {
            return null;
        }
        Context appContext = context.getApplicationContext();
        WindowManager windowManager =
                (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            return null;
        }
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(1, 1, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSPARENT);
        View view = new View(appContext);
        try {
            windowManager.addView(view, params);
            return view;
        } catch (Exception e) {
            AppLog.i(context, "创建悬浮窗失败: " + e);
            return null;
        }
    }

    private static void removeOverlay(Context context, final View overlay) {
        if (overlay == null) {
            return;
        }
        final WindowManager windowManager = (WindowManager) context.getApplicationContext()
                .getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            return;
        }
        // Keep the window up for a moment: the target activity is started
        // asynchronously and the check happens on the system side.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                windowManager.removeView(overlay);
            } catch (Exception ignored) {
                // Already removed.
            }
        }, OVERLAY_MS);
    }

    /**
     * Best effort detection of a running VPN, used to skip redundant intents.
     *
     * @return {@code true} when a VPN transport is currently active.
     */
    public static boolean isVpnActive(Context context) {
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return true;
            }
        }
        return false;
    }
}
