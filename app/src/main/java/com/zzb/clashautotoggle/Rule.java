package com.zzb.clashautotoggle;

/**
 * A single "when connected to this Wi-Fi, do that" rule.
 */
public final class Rule {

    public final String ssid;
    /** {@code true} = start ClashMeta, {@code false} = stop ClashMeta. */
    public final boolean enableClash;

    public Rule(String ssid, boolean enableClash) {
        this.ssid = ssid;
        this.enableClash = enableClash;
    }

    public boolean matches(String otherSsid) {
        return otherSsid != null && otherSsid.equalsIgnoreCase(ssid);
    }
}
