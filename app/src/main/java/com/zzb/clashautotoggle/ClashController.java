package com.zzb.clashautotoggle;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

/**
 * Drives a Clash client through its public "external control" intents.
 *
 * <p>ClashMetaForAndroid exposes {@code ExternalControlActivity} with the
 * {@code <package>.action.START_CLASH} / {@code STOP_CLASH} actions, while
 * FlClash exposes {@code TempActivity} with the {@code <package>.action.START}
 * / {@code STOP} actions.
 */
public final class ClashController {

    private static final String TAG = "ClashController";

    private static final String ACTION_START_SUFFIX = ".action.START_CLASH";
    private static final String ACTION_STOP_SUFFIX = ".action.STOP_CLASH";
    private static final String FLCLASH_ACTION_START_SUFFIX = ".action.START";
    private static final String FLCLASH_ACTION_STOP_SUFFIX = ".action.STOP";

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

    static String action(int clientType, String packageName, boolean start) {
        if (clientType == Settings.CLIENT_FLCLASH) {
            return packageName
                    + (start ? FLCLASH_ACTION_START_SUFFIX : FLCLASH_ACTION_STOP_SUFFIX);
        }
        return packageName + (start ? ACTION_START_SUFFIX : ACTION_STOP_SUFFIX);
    }

    /**
     * @return {@code true} when the intent could be delivered.
     */
    public static boolean apply(Context context, String packageName, int clientType,
                                boolean start) {
        Intent intent = new Intent(action(clientType, packageName, start));
        intent.setPackage(packageName);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            // ActivityNotFoundException (client missing) or SecurityException
            // (background activity start blocked without the overlay permission).
            Log.w(TAG, "Unable to control the Clash client: " + e);
            return false;
        }
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
