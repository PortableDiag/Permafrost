package com.portablediag.permafrost.api;

import android.content.Context;
import android.content.SharedPreferences;

import com.portablediag.permafrost.model.Store;

import java.security.SecureRandom;

/**
 * Whether the local API is on, what key opens it, and where it listens. Two
 * rules the other settings don't need:
 * <ul>
 *   <li><b>Off until someone turns it on</b>, and turning it off stops it. An app
 *       that quietly opens a socket is not something anybody asked for.</li>
 *   <li><b>The key is generated, never typed</b> — it guards every freeze, wake
 *       and uninstall Permafrost can perform.</li>
 * </ul>
 */
public final class ApiPrefs {

    private static final String KEY_ENABLED = "api_enabled";
    private static final String KEY_KEY = "api_key";
    private static final String KEY_PORT = "api_port";
    private static final String KEY_LAN = "api_lan";

    /** Clear of the other house apps' APIs (Lull 8787 ... ). */
    public static final int DEFAULT_PORT = 8791;
    public static final int MIN_PORT = 1024;
    public static final int MAX_PORT = 65535;

    /** 24 bytes of SecureRandom, hex — long enough that guessing it is not a strategy. */
    private static final int KEY_BYTES = 24;

    private ApiPrefs() {}

    private static SharedPreferences sp(Context c) {
        return Store.get(c).prefs();
    }

    public static boolean enabled(Context c) {
        return sp(c).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context c, boolean on) {
        // Turning it on with no key would leave an open door: generate one in the same breath.
        if (on && key(c).isEmpty()) regenerateKey(c);
        sp(c).edit().putBoolean(KEY_ENABLED, on).apply();
    }

    public static String key(Context c) {
        String k = sp(c).getString(KEY_KEY, "");
        return k == null ? "" : k;
    }

    /** Takes effect on the very next request; the old key stops working at once. */
    public static String regenerateKey(Context c) {
        byte[] bytes = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        String key = sb.toString();
        sp(c).edit().putString(KEY_KEY, key).apply();
        return key;
    }

    public static int port(Context c) {
        return clamp(sp(c).getInt(KEY_PORT, DEFAULT_PORT));
    }

    public static void setPort(Context c, int port) {
        sp(c).edit().putInt(KEY_PORT, clamp(port)).apply();
    }

    /**
     * False (the default) binds loopback only: reachable from this phone, and from
     * a computer through {@code adb forward}, and nowhere else.
     */
    public static boolean lan(Context c) {
        return sp(c).getBoolean(KEY_LAN, false);
    }

    public static void setLan(Context c, boolean on) {
        sp(c).edit().putBoolean(KEY_LAN, on).apply();
    }

    public static String bindAddress(Context c) {
        return lan(c) ? "0.0.0.0" : "127.0.0.1";
    }

    private static int clamp(int p) {
        return Math.max(MIN_PORT, Math.min(MAX_PORT, p));
    }
}
