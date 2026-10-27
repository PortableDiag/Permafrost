package com.portablediag.permafrost.api;

import com.portablediag.permafrost.model.ManagedApp;
import com.portablediag.permafrost.model.Mode;

import java.util.List;
import java.util.Map;

/**
 * Everything the API can do, with no Android type in sight, so {@link ApiRouter}
 * can be driven by a fake. Every mutating call is something a button in the UI
 * already does; they return null on success or the same error text the UI would
 * toast. All of them block and may touch root.
 */
public interface ApiBackend {

    /** One launchable app on the device. */
    final class InstalledApp {
        public final String pkg;
        public final String label;

        public InstalledApp(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    String versionName();

    int versionCode();

    int sdk();

    String selfPackage();

    boolean rootAvailable();

    boolean hasUsageAccess();

    List<ManagedApp> managed();

    ManagedApp managed(String pkg);

    /** Dormancy as the device reports it, per package. Empty when root is unavailable. */
    Map<String, Boolean> dormantOnDevice(List<ManagedApp> apps);

    boolean isInstalled(String pkg);

    List<InstalledApp> installed();

    String add(String pkg, Mode mode);

    String freeze(ManagedApp app);

    String wake(ManagedApp app);

    String launch(ManagedApp app);

    String unlockForUpdate(ManagedApp app);

    String changeMode(ManagedApp app, Mode mode);

    void unmanage(ManagedApp app);

    /** Ask the launcher to pin a frost icon. False when it refused or can't. */
    boolean pinShortcut(ManagedApp app);

    /** The frosted icon as a PNG, or null. */
    byte[] frostIconPng(String pkg);

    /** The latest crash report, without consuming it; null when there is none. */
    String crashReport();

    // ---- settings ----

    Mode defaultMode();

    void setDefaultMode(Mode m);

    int refreezeDelaySec();

    void setRefreezeDelaySec(int s);

    /** The house palettes, in the picker's order: {id, label, followsMode, mono}. */
    List<Object[]> palettes();

    String palette();

    String darkMode();

    /** Either may be null to leave it unchanged. */
    void setTheme(String palette, String darkMode);

    boolean appLock();

    boolean lockFrostIcons();

    /** "biometric", "credential" or "none" — what the device can challenge with. */
    String lockMethod();

    void setAppLock(boolean on);

    void setLockFrostIcons(boolean on);
}
