package com.portablediag.permafrost.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.portablediag.permafrost.R;
import com.portablediag.permafrost.core.Ghost;
import com.portablediag.permafrost.core.Freezer;
import com.portablediag.permafrost.core.Manager;
import com.portablediag.permafrost.core.Root;
import com.portablediag.permafrost.core.Shortcuts;
import com.portablediag.permafrost.core.InstalledApps;
import com.portablediag.permafrost.core.WatcherService;
import com.portablediag.permafrost.model.ManagedApp;
import com.portablediag.permafrost.model.Mode;
import com.portablediag.permafrost.model.Store;

/** Manage a single app: mode, dormancy, home icon, update unlock, removal. */
public class AppDetailActivity extends AppCompatActivity {

    public static final String EXTRA_PKG = "pkg";

    private String pkg;
    private ManagedApp app;

    private TextView state;
    private MaterialButtonToggleGroup modeGroup;
    private MaterialButton dormancyBtn;
    private MaterialButton updateBtn;
    private MaterialButton launchBtn;
    private MaterialButton shortcutBtn;
    private MaterialButton removeBtn;
    private View progress;

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_detail);
        SystemBars.pad(findViewById(R.id.root));

        pkg = getIntent().getStringExtra(EXTRA_PKG);
        app = pkg == null ? null : Store.get(this).get(pkg);
        if (app == null) {
            finish();
            return;
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setTitle(app.label != null ? app.label : pkg);

        ((android.widget.ImageView) findViewById(R.id.icon))
                .setImageDrawable(InstalledApps.iconOf(this, pkg));
        ((TextView) findViewById(R.id.pkg)).setText(pkg);
        state = findViewById(R.id.state);
        progress = findViewById(R.id.progress);

        modeGroup = findViewById(R.id.mode_group);
        dormancyBtn = findViewById(R.id.btn_dormancy);
        updateBtn = findViewById(R.id.btn_update);
        launchBtn = findViewById(R.id.btn_launch);
        shortcutBtn = findViewById(R.id.btn_shortcut);
        removeBtn = findViewById(R.id.btn_remove);

        modeGroup.check(app.mode == Mode.GHOST ? R.id.mode_ghost : R.id.mode_freeze);
        modeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            Mode newMode = checkedId == R.id.mode_ghost ? Mode.GHOST : Mode.FREEZE;
            if (newMode != app.mode) changeMode(newMode);
        });

        dormancyBtn.setOnClickListener(v -> toggleDormancy());
        launchBtn.setOnClickListener(v -> launch());
        updateBtn.setOnClickListener(v -> toggleUpdate());
        shortcutBtn.setOnClickListener(v -> addShortcut());
        removeBtn.setOnClickListener(v -> confirmRemove());

        render();
    }

    private void render() {
        String modeStr = getString(app.mode == Mode.GHOST ? R.string.mode_ghost : R.string.mode_freeze);
        String stateStr;
        if (app.updateUnlocked) {
            stateStr = getString(R.string.state_update_unlocked);
        } else if (app.dormant) {
            stateStr = getString(app.mode == Mode.GHOST ? R.string.state_ghosted : R.string.state_frozen);
        } else {
            stateStr = getString(R.string.state_awake);
        }
        state.setText(modeStr + " · " + stateStr);

        dormancyBtn.setText(app.dormant ? R.string.action_wake : R.string.action_freeze_now);
        updateBtn.setText(app.updateUnlocked ? R.string.action_relock : R.string.action_unlock_update);
    }

    private void setBusy(boolean b) {
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
    }

    /** Run a blocking root op off the main thread, then re-render. */
    private void bg(Op op, String failFmt) {
        setBusy(true);
        new Thread(() -> {
            if (!Root.isAvailable()) {
                main.post(() -> {
                    setBusy(false);
                    Toast.makeText(this, R.string.err_no_root, Toast.LENGTH_LONG).show();
                });
                return;
            }
            final String err = op.run();
            main.post(() -> {
                setBusy(false);
                if (err != null) {
                    Toast.makeText(this, String.format(failFmt, err), Toast.LENGTH_LONG).show();
                }
                app = Store.get(this).get(pkg);
                if (app == null) {
                    finish();
                } else {
                    modeGroup.check(app.mode == Mode.GHOST ? R.id.mode_ghost : R.id.mode_freeze);
                    render();
                }
            });
        }).start();
    }

    private interface Op {
        String run();
    }

    private void toggleDormancy() {
        if (app.dormant) {
            bg(() -> Manager.wake(this, app), "Wake failed: %s");
        } else {
            bg(() -> Manager.makeDormant(this, app), "Freeze failed: %s");
        }
    }

    private void launch() {
        bg(() -> {
            String err = null;
            if (Manager.isDormantOnDevice(this, app)) {
                err = Manager.wake(this, app);
            }
            if (err == null) {
                Freezer.launch(this, pkg);
                if (!app.updateUnlocked) WatcherService.watch(this, pkg);
            }
            return err;
        }, "Launch failed: %s");
    }

    private void toggleUpdate() {
        if (app.updateUnlocked) {
            // Re-lock: make dormant again.
            bg(() -> Manager.makeDormant(this, app), "Re-freeze failed: %s");
        } else {
            // Unlock for update: wake and keep it awake (no auto-refreeze).
            bg(() -> {
                String err = null;
                if (Manager.isDormantOnDevice(this, app)) {
                    err = Manager.wake(this, app);
                }
                if (err == null) {
                    app.updateUnlocked = true;
                    app.dormant = false;
                    Store.get(this).put(app);
                }
                return err;
            }, "Unlock failed: %s");
        }
    }

    private void changeMode(Mode newMode) {
        bg(() -> {
            boolean wasDormant = app.dormant && !app.updateUnlocked;
            // If currently dormant, lift it in the OLD mode first.
            String err = null;
            if (Manager.isDormantOnDevice(this, app)) {
                err = Manager.wake(this, app);
                if (err != null) return err;
            }
            // Discard any stale ghost backup when leaving ghost mode.
            if (app.mode == Mode.GHOST && newMode != Mode.GHOST) {
                Ghost.deleteBackup(this, pkg);
                app.hasBackup = false;
            }
            app.mode = newMode;
            Store.get(this).put(app);
            // Re-apply dormancy in the NEW mode if it was dormant before.
            if (wasDormant) {
                return Manager.makeDormant(this, app);
            }
            return null;
        }, "Mode change failed: %s");
    }

    private void addShortcut() {
        if (Shortcuts.pin(this, pkg, app.label)) {
            app.hasShortcut = true;
            Store.get(this).put(app);
            Toast.makeText(this, R.string.shortcut_requested, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.shortcut_unsupported, Toast.LENGTH_LONG).show();
        }
    }

    private void confirmRemove() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_remove)
                .setMessage(R.string.remove_confirm)
                .setPositiveButton(R.string.action_remove, (d, w) -> doRemove())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void doRemove() {
        bg(() -> {
            // Restore the app to a normal, usable state before letting go.
            if (Manager.isDormantOnDevice(this, app)) {
                Manager.wake(this, app);
            }
            Ghost.deleteBackup(this, pkg);
            Store.get(this).remove(pkg);
            return null;
        }, "Remove failed: %s");
    }
}
