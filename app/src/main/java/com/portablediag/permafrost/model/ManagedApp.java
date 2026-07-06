package com.portablediag.permafrost.model;

import org.json.JSONException;
import org.json.JSONObject;

/** One app that Permafrost is managing, plus its persisted state. */
public class ManagedApp {
    public String packageName;
    public String label;
    public Mode mode = Mode.FREEZE;

    /** True when the app is currently dormant (disabled, or ghosted/uninstalled). */
    public boolean dormant = true;
    /** True while the user has temporarily thawed the app to update it. */
    public boolean updateUnlocked = false;
    /** True once a home-screen frost icon has been requested for this app. */
    public boolean hasShortcut = false;
    /** For GHOST mode: whether a backup (apk + data) currently exists on disk. */
    public boolean hasBackup = false;

    public ManagedApp() {}

    public ManagedApp(String packageName, String label, Mode mode) {
        this.packageName = packageName;
        this.label = label;
        this.mode = mode;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("pkg", packageName);
        o.put("label", label);
        o.put("mode", mode.name());
        o.put("dormant", dormant);
        o.put("updateUnlocked", updateUnlocked);
        o.put("hasShortcut", hasShortcut);
        o.put("hasBackup", hasBackup);
        return o;
    }

    public static ManagedApp fromJson(JSONObject o) {
        ManagedApp a = new ManagedApp();
        a.packageName = o.optString("pkg");
        a.label = o.optString("label");
        a.mode = Mode.fromName(o.optString("mode"), Mode.FREEZE);
        a.dormant = o.optBoolean("dormant", true);
        a.updateUnlocked = o.optBoolean("updateUnlocked", false);
        a.hasShortcut = o.optBoolean("hasShortcut", false);
        a.hasBackup = o.optBoolean("hasBackup", false);
        return a;
    }
}
