package com.portablediag.permafrost.api;

import com.portablediag.permafrost.model.ManagedApp;
import com.portablediag.permafrost.model.Mode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An in-memory phone: what the router sees, with every call it made recorded in {@link #calls}. */
class FakeBackend implements ApiBackend {

    final Map<String, ManagedApp> apps = new LinkedHashMap<>();
    final Map<String, Boolean> frozen = new HashMap<>();
    final List<InstalledApp> installed = new ArrayList<>(Arrays.asList(
            new InstalledApp("com.example.compass", "Compass"),
            new InstalledApp("com.example.notes", "Notes")));
    final List<String> calls = new ArrayList<>();

    boolean root = true;
    boolean usageAccess = true;
    /** The next mutating call fails with this, as the device would. */
    String failWith;
    String lockMethod = "biometric";
    boolean appLock;
    boolean lockIcons;
    Mode defaultMode = Mode.FREEZE;
    int delay = 2;
    String palette = "Material You";
    String darkMode = "system";
    String crash;

    ManagedApp manage(String pkg, Mode mode, boolean dormant) {
        ManagedApp a = new ManagedApp(pkg, pkg, mode);
        a.dormant = dormant;
        apps.put(pkg, a);
        frozen.put(pkg, dormant);
        return a;
    }

    private String done(String call, ManagedApp a, Boolean dormantAfter) {
        calls.add(call + " " + (a == null ? "" : a.packageName));
        if (failWith != null) {
            String e = failWith;
            failWith = null;
            return e;
        }
        if (a != null && dormantAfter != null) {
            a.dormant = dormantAfter;
            frozen.put(a.packageName, dormantAfter);
        }
        return null;
    }

    @Override public String versionName() { return "9.9"; }
    @Override public int versionCode() { return 99; }
    @Override public int sdk() { return 35; }
    @Override public String selfPackage() { return "com.portablediag.permafrost"; }
    @Override public boolean rootAvailable() { return root; }
    @Override public boolean hasUsageAccess() { return usageAccess; }
    @Override public List<ManagedApp> managed() { return new ArrayList<>(apps.values()); }
    @Override public ManagedApp managed(String pkg) { return apps.get(pkg); }

    @Override
    public Map<String, Boolean> dormantOnDevice(List<ManagedApp> list) {
        Map<String, Boolean> out = new HashMap<>();
        if (!root) return out;
        for (ManagedApp a : list) out.put(a.packageName, frozen.get(a.packageName));
        return out;
    }

    @Override
    public boolean isInstalled(String pkg) {
        for (InstalledApp i : installed) if (i.pkg.equals(pkg)) return true;
        return false;
    }

    @Override public List<InstalledApp> installed() { return installed; }

    @Override
    public String add(String pkg, Mode mode) {
        ManagedApp a = manage(pkg, mode, false);
        return done("add", a, true);
    }

    @Override public String freeze(ManagedApp a) { return done("freeze", a, true); }
    @Override public String wake(ManagedApp a) { return done("wake", a, false); }
    @Override public String launch(ManagedApp a) { return done("launch", a, false); }

    @Override
    public String unlockForUpdate(ManagedApp a) {
        String e = done("unlock-update", a, false);
        if (e == null) a.updateUnlocked = true;
        return e;
    }

    @Override
    public String changeMode(ManagedApp a, Mode mode) {
        String e = done("mode " + mode, a, null);
        if (e == null) a.mode = mode;
        return e;
    }

    @Override
    public void unmanage(ManagedApp a) {
        calls.add("unmanage " + a.packageName);
        apps.remove(a.packageName);
        frozen.put(a.packageName, false);
    }

    @Override public boolean pinShortcut(ManagedApp a) { calls.add("pin " + a.packageName); return true; }

    @Override
    public byte[] frostIconPng(String pkg) {
        return apps.containsKey(pkg) ? new byte[]{(byte) 0x89, 'P', 'N', 'G'} : null;
    }

    @Override public String crashReport() { return crash; }
    @Override public Mode defaultMode() { return defaultMode; }
    @Override public void setDefaultMode(Mode m) { defaultMode = m; }
    @Override public int refreezeDelaySec() { return delay; }
    @Override public void setRefreezeDelaySec(int s) { delay = s; }

    @Override
    public List<Object[]> palettes() {
        return Arrays.asList(
                new Object[]{"Material You", "Material You", true, false},
                new Object[]{"Zoro", "Zoro", false, false},
                new Object[]{"Phosphor P31", "Phosphor P31", false, true});
    }

    @Override public String palette() { return palette; }
    @Override public String darkMode() { return darkMode; }

    @Override
    public void setTheme(String p, String d) {
        if (p != null) palette = p;
        if (d != null) darkMode = d;
    }

    @Override public boolean appLock() { return appLock; }
    @Override public boolean lockFrostIcons() { return lockIcons; }
    @Override public String lockMethod() { return lockMethod; }
    @Override public void setAppLock(boolean on) { appLock = on; }
    @Override public void setLockFrostIcons(boolean on) { lockIcons = on; }
}
