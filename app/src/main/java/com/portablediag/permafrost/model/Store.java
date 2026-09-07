package com.portablediag.permafrost.model;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists the set of managed apps and global settings in SharedPreferences
 * as a small JSON blob. Single source of truth, read/written on the main thread
 * (the data set is tiny).
 */
public class Store {
    private static final String PREFS = "permafrost";
    private static final String KEY_APPS = "apps";
    private static final String KEY_DEFAULT_MODE = "default_mode";
    private static final String KEY_REFREEZE_DELAY = "refreeze_delay_sec";
    private static final String KEY_ONBOARDED = "onboarded";
    private static final String KEY_APP_LOCK = "app_lock";
    private static final String KEY_APP_LOCK_ICONS = "app_lock_icons";

    private final SharedPreferences prefs;
    // Preserve insertion order for a stable list.
    private final Map<String, ManagedApp> apps = new LinkedHashMap<>();

    private static Store instance;

    public static synchronized Store get(Context ctx) {
        if (instance == null) {
            instance = new Store(ctx.getApplicationContext());
        }
        return instance;
    }

    private Store(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    private void load() {
        apps.clear();
        String raw = prefs.getString(KEY_APPS, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                ManagedApp a = ManagedApp.fromJson(arr.getJSONObject(i));
                if (a.packageName != null && !a.packageName.isEmpty()) {
                    apps.put(a.packageName, a);
                }
            }
        } catch (JSONException ignored) {
        }
    }

    private void persist() {
        JSONArray arr = new JSONArray();
        for (ManagedApp a : apps.values()) {
            try {
                arr.put(a.toJson());
            } catch (JSONException ignored) {
            }
        }
        prefs.edit().putString(KEY_APPS, arr.toString()).apply();
    }

    public synchronized List<ManagedApp> all() {
        return new ArrayList<>(apps.values());
    }

    public synchronized ManagedApp get(String pkg) {
        return apps.get(pkg);
    }

    public synchronized boolean isManaged(String pkg) {
        return apps.containsKey(pkg);
    }

    public synchronized void put(ManagedApp a) {
        apps.put(a.packageName, a);
        persist();
    }

    public synchronized void remove(String pkg) {
        apps.remove(pkg);
        persist();
    }

    /** Persist any field changes made on an object returned from {@link #get}. */
    public synchronized void save() {
        persist();
    }

    // ---- global settings ----

    public Mode defaultMode() {
        return Mode.fromName(prefs.getString(KEY_DEFAULT_MODE, Mode.FREEZE.name()), Mode.FREEZE);
    }

    public void setDefaultMode(Mode m) {
        prefs.edit().putString(KEY_DEFAULT_MODE, m.name()).apply();
    }

    /** Seconds the target must be off-screen before we re-freeze it. */
    public int refreezeDelaySec() {
        return prefs.getInt(KEY_REFREEZE_DELAY, 2);
    }

    public void setRefreezeDelaySec(int sec) {
        prefs.edit().putInt(KEY_REFREEZE_DELAY, sec).apply();
    }

    /** Require a biometric or device-credential unlock to open Permafrost's UI. */
    public boolean appLockEnabled() {
        return prefs.getBoolean(KEY_APP_LOCK, false);
    }

    public void setAppLockEnabled(boolean v) {
        prefs.edit().putBoolean(KEY_APP_LOCK, v).apply();
    }

    /** Challenge a frost-icon tap too. Only meaningful while the lock is on. */
    public boolean lockFrostIcons() {
        return prefs.getBoolean(KEY_APP_LOCK_ICONS, false);
    }

    public void setLockFrostIcons(boolean v) {
        prefs.edit().putBoolean(KEY_APP_LOCK_ICONS, v).apply();
    }

    public boolean isOnboarded() {
        return prefs.getBoolean(KEY_ONBOARDED, false);
    }

    public void setOnboarded(boolean v) {
        prefs.edit().putBoolean(KEY_ONBOARDED, v).apply();
    }
}
