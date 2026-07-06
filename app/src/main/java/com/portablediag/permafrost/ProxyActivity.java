package com.portablediag.permafrost;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.portablediag.permafrost.core.Freezer;
import com.portablediag.permafrost.core.Manager;
import com.portablediag.permafrost.core.Root;
import com.portablediag.permafrost.core.Shortcuts;
import com.portablediag.permafrost.core.WatcherService;
import com.portablediag.permafrost.model.ManagedApp;
import com.portablediag.permafrost.model.Store;

/**
 * The tap target behind every frost icon. Wakes the app (thaw or reinstall),
 * launches it, and hands off to {@link WatcherService} to re-freeze it once the
 * user leaves. Transparent and finishes immediately.
 */
public class ProxyActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String pkg = getIntent().getStringExtra(Shortcuts.EXTRA_PACKAGE);
        if (pkg == null) {
            finish();
            return;
        }

        final Store store = Store.get(this);
        ManagedApp app = store.get(pkg);
        if (app == null) {
            // Not managed anymore — just try to launch whatever is there.
            Freezer.launch(this, pkg);
            finish();
            return;
        }

        final ManagedApp target = app;
        final Handler main = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            if (!Root.isAvailable()) {
                main.post(() -> {
                    Toast.makeText(this, R.string.err_no_root, Toast.LENGTH_LONG).show();
                    finish();
                });
                return;
            }

            // Wake only if it's actually dormant; if already awake, just launch.
            String err = null;
            if (Manager.isDormantOnDevice(this, target)) {
                err = Manager.wake(this, target);
            } else {
                target.dormant = false;
                store.put(target);
            }

            final String fErr = err;
            main.post(() -> {
                if (fErr != null) {
                    Toast.makeText(this, getString(R.string.err_wake, fErr),
                            Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                Freezer.launch(this, pkg);
                // Don't auto-refreeze while the app is unlocked for updating.
                if (!target.updateUnlocked) {
                    WatcherService.watch(this, pkg);
                }
                finish();
            });
        }).start();
    }
}
