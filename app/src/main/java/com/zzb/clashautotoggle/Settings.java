package com.zzb.clashautotoggle;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent configuration, backed by {@link SharedPreferences}.
 */
public final class Settings {

    /** Do not touch ClashMeta. */
    public static final int ACTION_KEEP = 0;
    /** Start ClashMeta. */
    public static final int ACTION_ENABLE = 1;
    /** Stop ClashMeta. */
    public static final int ACTION_DISABLE = 2;

    /** ClashMetaForAndroid (CMFA) and compatible forks. */
    public static final int CLIENT_CMFA = 0;
    /** FlClash (https://github.com/chen08209/FlClash). */
    public static final int CLIENT_FLCLASH = 1;
    /** Any other client / fork, identified by a user supplied package name. */
    public static final int CLIENT_CUSTOM = 2;

    public static final String DEFAULT_CLASH_PACKAGE = "com.github.metacubex.clash.meta";
    public static final String DEFAULT_FLCLASH_PACKAGE = "com.follow.clash";

    private static final String PREFS = "settings";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_RULES = "rules";
    private static final String KEY_DEFAULT_ACTION = "default_action";
    private static final String KEY_CLASH_PACKAGE = "clash_package";
    private static final String KEY_FLCLASH_PACKAGE = "flclash_package";
    private static final String KEY_CUSTOM_PACKAGE = "custom_package";
    private static final String KEY_CLIENT_TYPE = "client_type";
    private static final String KEY_LAST_APPLIED = "last_applied";

    private final SharedPreferences prefs;

    public Settings(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public int getDefaultAction() {
        return prefs.getInt(KEY_DEFAULT_ACTION, ACTION_KEEP);
    }

    public void setDefaultAction(int action) {
        prefs.edit().putInt(KEY_DEFAULT_ACTION, action).apply();
    }

    /**
     * @return {@link #CLIENT_CMFA}, {@link #CLIENT_FLCLASH} or
     *         {@link #CLIENT_CUSTOM}.
     */
    public int getClientType() {
        return normalizeClientType(prefs.getInt(KEY_CLIENT_TYPE, CLIENT_CMFA));
    }

    public void setClientType(int clientType) {
        int normalized = normalizeClientType(clientType);
        if (normalized == getClientType()) {
            return;
        }
        // The other client is a different app: the remembered state no longer
        // describes it, so force a fresh evaluation.
        prefs.edit()
                .putInt(KEY_CLIENT_TYPE, normalized)
                .putInt(KEY_LAST_APPLIED, ACTION_KEEP)
                .apply();
    }

    private static int normalizeClientType(int clientType) {
        switch (clientType) {
            case CLIENT_FLCLASH:
                return CLIENT_FLCLASH;
            case CLIENT_CUSTOM:
                return CLIENT_CUSTOM;
            default:
                return CLIENT_CMFA;
        }
    }

    /**
     * @return the pre-filled package name for {@code clientType}; the custom
     *         client has no default and falls back to the CMFA package.
     */
    public static String getDefaultPackage(int clientType) {
        return clientType == CLIENT_FLCLASH ? DEFAULT_FLCLASH_PACKAGE : DEFAULT_CLASH_PACKAGE;
    }

    /**
     * @return the package name of the currently selected client.
     */
    public String getClashPackage() {
        int clientType = getClientType();
        String fallback = getDefaultPackage(clientType);
        String value = prefs.getString(packageKey(clientType), fallback);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    /** Stores the package name of the currently selected client. */
    public void setClashPackage(String packageName) {
        prefs.edit().putString(packageKey(getClientType()), packageName).apply();
    }

    private static String packageKey(int clientType) {
        switch (clientType) {
            case CLIENT_FLCLASH:
                return KEY_FLCLASH_PACKAGE;
            case CLIENT_CUSTOM:
                return KEY_CUSTOM_PACKAGE;
            default:
                return KEY_CLASH_PACKAGE;
        }
    }

    /**
     * The state this app last asked ClashMeta to be in, used to avoid sending
     * redundant intents (each intent briefly wakes up ClashMeta).
     *
     * @return {@link #ACTION_KEEP} when unknown.
     */
    public int getLastApplied() {
        return prefs.getInt(KEY_LAST_APPLIED, ACTION_KEEP);
    }

    public void setLastApplied(int action) {
        prefs.edit().putInt(KEY_LAST_APPLIED, action).apply();
    }

    public List<Rule> getRules() {
        List<Rule> rules = new ArrayList<>();
        String raw = prefs.getString(KEY_RULES, null);
        if (raw == null || raw.isEmpty()) {
            return rules;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                String ssid = object.optString("ssid", "");
                if (!ssid.isEmpty()) {
                    rules.add(new Rule(ssid, object.optBoolean("enable", true)));
                }
            }
        } catch (JSONException ignored) {
            // Corrupted preferences: fall back to an empty rule set.
        }
        return rules;
    }

    public void setRules(List<Rule> rules) {
        JSONArray array = new JSONArray();
        try {
            for (Rule rule : rules) {
                JSONObject object = new JSONObject();
                object.put("ssid", rule.ssid);
                object.put("enable", rule.enableClash);
                array.put(object);
            }
        } catch (JSONException ignored) {
            return;
        }
        prefs.edit().putString(KEY_RULES, array.toString()).apply();
    }

    /**
     * @return the action configured for {@code ssid}, falling back to the
     *         default action when no rule matches.
     */
    public int resolveAction(String ssid) {
        if (ssid != null) {
            for (Rule rule : getRules()) {
                if (rule.matches(ssid)) {
                    return rule.enableClash ? ACTION_ENABLE : ACTION_DISABLE;
                }
            }
        }
        return getDefaultAction();
    }
}
