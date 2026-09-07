package com.portablediag.permafrost;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.FragmentActivity;

import com.portablediag.permafrost.core.AppLock;
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
 *
 * <p>A {@link FragmentActivity} only so it can host a {@link AppLock} challenge
 * when the user has opted into locking frost icons; that setting is off by
 * default, and with it off this behaves exactly as it always has.
 */
public class ProxyActivity extends FragmentActivity {

    private ActivityResultLauncher<Intent> credential;
    private String pkg;
    /** The keyguard path stops and restarts us; the wake must not run twice. */
    private boolean acted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pkg = getIntent().getStringExtra(Shortcuts.EXTRA_PACKAGE);
        if (pkg == null) {
            finish();
            return;
        }
        // Must be registered before the activity starts, whether or not it's used.
        credential = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> onAuthResult(result.getResultCode() == RESULT_OK));
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (pkg == null || acted) return;
        if (!AppLock.locksFrostIcons(this) || AppLock.isUnlocked()) {
            acted = true;
            wake(pkg);
            return;
        }
        acted = true;
        AppLock.prompt(this, credential, this::onAuthResult);
    }

    /** Both challenge paths — biometric and keyguard — land here. */
    private void onAuthResult(boolean ok) {
        AppLock.authFinished();
        if (!ok) {
            finish();
            return;
        }
        AppLock.markUnlocked();
        wake(pkg);
    }

    private void wake(final String pkg) {
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
