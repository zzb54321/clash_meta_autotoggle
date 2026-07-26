package com.zzb.clashautotoggle;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Restarts the monitor after a reboot or an app update, but only when the user
 * enabled it.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        switch (intent.getAction()) {
            case Intent.ACTION_BOOT_COMPLETED:
            case Intent.ACTION_MY_PACKAGE_REPLACED:
                boolean enabled = new Settings(context).isEnabled();
                AppLog.i(context, "收到广播 " + intent.getAction()
                        + "，自动切换" + (enabled ? "已开启，重新启动监听服务" : "未开启，忽略"));
                if (enabled) {
                    MonitorService.start(context);
                }
                break;
            default:
                break;
        }
    }
}
