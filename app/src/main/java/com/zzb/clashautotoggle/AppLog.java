package com.zzb.clashautotoggle;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Small persistent debug log.
 *
 * <p>Everything the service decides is recorded here so a misbehaving toggle can
 * be diagnosed without a USB cable: the log survives process death because it is
 * stored in {@link SharedPreferences}, and it is capped to {@link #MAX_LINES}
 * lines so it can never grow without bound.
 */
public final class AppLog {

    /** Tag used for the logcat mirror ({@code adb logcat -s ClashAutoToggle}). */
    public static final String TAG = "ClashAutoToggle";

    private static final String PREFS = "log";
    private static final String KEY_LINES = "lines";
    private static final int MAX_LINES = 300;

    private static final Object LOCK = new Object();

    private AppLog() {
    }

    /** Appends one timestamped line to the log. */
    public static void i(Context context, String message) {
        Log.i(TAG, message);
        if (context == null) {
            return;
        }
        String line = timestamp() + " " + message;
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            String previous = prefs.getString(KEY_LINES, "");
            String merged = previous.isEmpty() ? line : previous + "\n" + line;
            prefs.edit().putString(KEY_LINES, trim(merged)).apply();
        }
    }

    /** @return the whole log, oldest line first; empty when nothing was logged. */
    public static String read(Context context) {
        synchronized (LOCK) {
            return prefs(context).getString(KEY_LINES, "");
        }
    }

    public static void clear(Context context) {
        synchronized (LOCK) {
            prefs(context).edit().remove(KEY_LINES).apply();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String timestamp() {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    /** Keeps only the newest {@link #MAX_LINES} lines. */
    private static String trim(String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        if (lines <= MAX_LINES) {
            return text;
        }
        int toDrop = lines - MAX_LINES;
        int index = 0;
        for (int dropped = 0; dropped < toDrop; dropped++) {
            index = text.indexOf('\n', index);
            if (index < 0) {
                return "";
            }
            index++;
        }
        return text.substring(index);
    }
}
