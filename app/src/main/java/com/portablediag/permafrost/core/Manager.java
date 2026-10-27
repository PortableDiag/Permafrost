package com.portablediag.permafrost.core;

import android.content.Context;

import com.portablediag.permafrost.model.ManagedApp;
import com.portablediag.permafrost.model.Mode;
import com.portablediag.permafrost.model.Store;

/**
 * High-level operations that keep the persisted {@link Store} in sync with the
 * real device state. All methods are blocking and touch root — call them off the
 * main thread.
 */
public class Manager {

    /**
     * Put an app to sleep according to its mode. Returns null on success, else an
     * error message. Never touches an app that is unlocked for updating.
     */
    public static String makeDormant(Context ctx, ManagedApp app) {
        Store store = Store.get(ctx);
        String err;
        if (app.mode == Mode.GHOST) {
            // Re-snapshot on EVERY re-ghost. Backing up only the first time meant
            // everything the app wrote afterwards was thrown away by the very
            // uninstall that follows, and the next wake silently restored the
            // original snapshot instead. Ghost.backup() stages and verifies before
            // it replaces the previous backup, so a failure here leaves the old
            // one intact — and we must NOT uninstall on top of a failed backup.
            err = Ghost.backup(ctx, app.packageName);
            if (err != null) return err;
            app.hasBackup = true;
            err = Ghost.uninstall(app.packageName);
        } else {
            err = Freezer.freeze(app.packageName);
        }
        if (err == null) {
            app.dormant = true;
            app.updateUnlocked = false;
            store.put(app);
        }
        return err;
    }

    /**
     * Wake an app (thaw or reinstall) without launching. Returns null on success.
     */
    public static String wake(Context ctx, ManagedApp app) {
        Store store = Store.get(ctx);
        String err;
        if (app.mode == Mode.GHOST) {
            err = Ghost.restore(ctx, app.packageName);
        } else {
            err = Freezer.thaw(app.packageName);
        }
        if (err == null) {
            app.dormant = false;
            store.put(app);
        }
        return err;
    }

    /**
     * Bring a package under management in {@code mode} and put it to sleep.
     * Returns null on success. The app stays managed even if the first freeze
     * fails, exactly as the picker has always left it.
     */
    public static String add(Context ctx, String pkg, Mode mode) {
        ManagedApp app = new ManagedApp(pkg, InstalledApps.labelOf(ctx, pkg), mode);
        Store.get(ctx).put(app);
        return makeDormant(ctx, app);
    }

    /**
     * Wake the app if it is dormant, launch it, and hand it to the watcher unless
     * it is unlocked for updating. {@code viaRoot} starts it through {@code am}
     * instead of an intent — needed when Permafrost itself is in the background
     * (the API), where Android blocks an ordinary activity start.
     */
    public static String launch(Context ctx, ManagedApp app, boolean viaRoot) {
        String err = null;
        if (isDormantOnDevice(ctx, app)) {
            err = wake(ctx, app);
        }
        if (err != null) return err;
        if (viaRoot) {
            Freezer.launchViaRoot(app.packageName);
        } else {
            Freezer.launch(ctx, app.packageName);
        }
        if (!app.updateUnlocked) WatcherService.watch(ctx, app.packageName);
        return null;
    }

    /** Wake the app and keep it awake — no auto re-freeze — so it can be updated. */
    public static String unlockForUpdate(Context ctx, ManagedApp app) {
        String err = null;
        if (isDormantOnDevice(ctx, app)) {
            err = wake(ctx, app);
        }
        if (err == null) {
            app.updateUnlocked = true;
            app.dormant = false;
            Store.get(ctx).put(app);
        }
        return err;
    }

    /**
     * Switch an app between Freeze and Ghost. A dormant app is woken in the old
     * mode first and put back to sleep in the new one.
     */
    public static String changeMode(Context ctx, ManagedApp app, Mode newMode) {
        if (newMode == app.mode) return null;
        boolean wasDormant = app.dormant && !app.updateUnlocked;
        // If currently dormant, lift it in the OLD mode first.
        if (isDormantOnDevice(ctx, app)) {
            String err = wake(ctx, app);
            if (err != null) return err;
        }
        // Discard any stale ghost backup when leaving ghost mode.
        if (app.mode == Mode.GHOST && newMode != Mode.GHOST) {
            Ghost.deleteBackup(ctx, app.packageName);
            app.hasBackup = false;
        }
        app.mode = newMode;
        Store.get(ctx).put(app);
        // Re-apply dormancy in the NEW mode if it was dormant before.
        if (wasDormant) {
            return makeDormant(ctx, app);
        }
        return null;
    }

    /** Restore the app to a normal, usable state and stop managing it. */
    public static void unmanage(Context ctx, ManagedApp app) {
        if (isDormantOnDevice(ctx, app)) {
            wake(ctx, app);
        }
        Ghost.deleteBackup(ctx, app.packageName);
        if (app.hasShortcut) Shortcuts.unpin(ctx, app.packageName);
        Store.get(ctx).remove(app.packageName);
    }

    /** Whether the app is presently dormant on the device (source of truth: device). */
    public static boolean isDormantOnDevice(Context ctx, ManagedApp app) {
        if (app.mode == Mode.GHOST) {
            return !Freezer.isInstalled(ctx, app.packageName);
        }
        return Freezer.isFrozen(app.packageName);
    }
}
