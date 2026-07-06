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
            if (!Ghost.hasBackup(ctx, app.packageName)) {
                err = Ghost.backup(ctx, app.packageName);
                if (err != null) return err;
                app.hasBackup = true;
            }
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

    /** Whether the app is presently dormant on the device (source of truth: device). */
    public static boolean isDormantOnDevice(Context ctx, ManagedApp app) {
        if (app.mode == Mode.GHOST) {
            return !Freezer.isInstalled(ctx, app.packageName);
        }
        return Freezer.isFrozen(app.packageName);
    }
}
