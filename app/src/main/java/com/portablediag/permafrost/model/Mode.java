package com.portablediag.permafrost.model;

/** How a managed app is kept dormant between uses. */
public enum Mode {
    /** Method A: disabled via {@code pm disable-user}. Instant, keeps all data. */
    FREEZE,
    /** Method B: backed up then uninstalled; reinstalled on launch. */
    GHOST;

    public static Mode fromName(String s, Mode fallback) {
        if (s == null) return fallback;
        try {
            return Mode.valueOf(s);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
