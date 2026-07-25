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

    public static final String DEFAULT_CLASH_PACKAGE = "com.github.metacubex.clash.meta";

    private static final String PREFS = "settings";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_RULES = "rules";
    private static final String KEY_DEFAULT_ACTION = "default_action";
    private static final String KEY_CLASH_PACKAGE = "clash_package";
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

    public String getClashPackage() {
        String value = prefs.getString(KEY_CLASH_PACKAGE, DEFAULT_CLASH_PACKAGE);
        return value == null || value.trim().isEmpty() ? DEFAULT_CLASH_PACKAGE : value.trim();
    }

    public void setClashPackage(String packageName) {
        prefs.edit().putString(KEY_CLASH_PACKAGE, packageName).apply();
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
