package com.portablediag.permafrost.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.portablediag.permafrost.R;
import com.portablediag.permafrost.core.Manager;
import com.portablediag.permafrost.core.Root;
import com.portablediag.permafrost.core.Shortcuts;
import com.portablediag.permafrost.core.InstalledApps;
import com.portablediag.permafrost.model.ManagedApp;
import com.portablediag.permafrost.model.Mode;
import com.portablediag.permafrost.model.Store;

import java.util.ArrayList;
import java.util.List;

/** Pick one or more installed apps to bring under Permafrost's management. */
public class AppPickerActivity extends AppCompatActivity {

    private PickerAdapter adapter;
    private View progress;
    private MaterialButton addBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_picker);
        SystemBars.pad(findViewById(R.id.root));

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        progress = findViewById(R.id.progress);
        addBtn = findViewById(R.id.add);

        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PickerAdapter(this);
        list.setAdapter(adapter);

        loadApps();

        addBtn.setOnClickListener(v -> addSelected());
    }

    private void loadApps() {
        Store store = Store.get(this);
        List<InstalledApps.Entry> all = InstalledApps.launchable(this);
        List<InstalledApps.Entry> selectable = new ArrayList<>();
        for (InstalledApps.Entry e : all) {
            if (!store.isManaged(e.pkg)) selectable.add(e);
        }
        adapter.submit(selectable);
    }

    private void addSelected() {
        final List<String> pkgs = adapter.selected();
        if (pkgs.isEmpty()) {
            finish();
            return;
        }
        setBusy(true);
        final Mode defMode = Store.get(this).defaultMode();
        final Handler main = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            if (!Root.isAvailable()) {
                main.post(() -> {
                    setBusy(false);
                    Toast.makeText(this, R.string.err_no_root, Toast.LENGTH_LONG).show();
                });
                return;
            }
            final StringBuilder errors = new StringBuilder();
            final List<ManagedApp> added = new ArrayList<>();
            for (String pkg : pkgs) {
                String label = InstalledApps.labelOf(this, pkg);
                ManagedApp app = new ManagedApp(pkg, label, defMode);
                Store.get(this).put(app);
                String err = Manager.makeDormant(this, app);
                if (err != null) {
                    errors.append(label).append(": ").append(err).append('\n');
                } else {
                    added.add(app);
                }
            }
            main.post(() -> {
                setBusy(false);
                // Offer to place frost icons for everything that froze cleanly.
                for (ManagedApp a : added) {
                    if (Shortcuts.pin(this, a.packageName, a.label)) {
                        a.hasShortcut = true;
                        Store.get(this).put(a);
                    }
                }
                if (errors.length() > 0) {
                    Toast.makeText(this, errors.toString().trim(), Toast.LENGTH_LONG).show();
                }
                finish();
            });
        }).start();
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        addBtn.setEnabled(!busy);
    }
}
