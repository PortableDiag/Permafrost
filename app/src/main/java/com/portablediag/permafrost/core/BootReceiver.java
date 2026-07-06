package com.portablediag.permafrost.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.portablediag.permafrost.model.ManagedApp;
import com.portablediag.permafrost.model.Store;

/**
 * On boot, re-assert dormancy for every managed app that isn't unlocked for
 * updating. Disabled state normally survives reboot on its own; this is a
 * belt-and-braces pass that also re-ghosts anything left installed.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        final Context app = context.getApplicationContext();
        final android.content.BroadcastReceiver.PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                if (!Root.isAvailable()) return;
                for (ManagedApp m : Store.get(app).all()) {
                    if (m.updateUnlocked) continue;
                    if (!Manager.isDormantOnDevice(app, m)) {
                        Manager.makeDormant(app, m);
                    } else {
                        m.dormant = true;
                        Store.get(app).put(m);
                    }
                }
            } finally {
                pr.finish();
            }
        }).start();
    }
}
