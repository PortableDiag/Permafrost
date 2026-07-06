package com.portablediag.permafrost.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.portablediag.permafrost.R;
import com.portablediag.permafrost.core.ForegroundApps;
import com.portablediag.permafrost.core.Root;
import com.portablediag.permafrost.model.ManagedApp;
import com.portablediag.permafrost.model.Store;

import java.util.List;

/** Home screen: the list of managed apps plus permission status and an add button. */
public class MainActivity extends AppCompatActivity {

    private RecyclerView list;
    private View empty;
    private ManagedAppAdapter adapter;
    private MaterialCardView banner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        SystemBars.pad(findViewById(R.id.root));

        // Prompt for root up front so the su dialog appears at launch, not on
        // the first freeze. Runs off the UI thread; the result is cached.
        new Thread(() -> {
            boolean ok = Root.isAvailable();
            if (!ok) {
                runOnUiThread(() -> android.widget.Toast.makeText(
                        this, R.string.err_no_root, android.widget.Toast.LENGTH_LONG).show());
            }
        }).start();

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        banner = findViewById(R.id.banner);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ManagedAppAdapter(this, app -> {
            Intent i = new Intent(this, AppDetailActivity.class);
            i.putExtra(AppDetailActivity.EXTRA_PKG, app.packageName);
            startActivity(i);
        });
        list.setAdapter(adapter);

        ExtendedFloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(v ->
                startActivity(new Intent(this, AppPickerActivity.class)));

        MaterialButton grant = findViewById(R.id.banner_action);
        grant.setOnClickListener(v ->
                startActivity(ForegroundApps.usageAccessSettings()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        List<ManagedApp> apps = Store.get(this).all();
        adapter.submit(apps);
        empty.setVisibility(apps.isEmpty() ? View.VISIBLE : View.GONE);

        // Permission banner: shown when usage access is missing (needed to
        // auto-refreeze). Root is checked lazily but surfaced here too.
        boolean usage = ForegroundApps.hasUsageAccess(this);
        banner.setVisibility(usage ? View.GONE : View.VISIBLE);
    }

    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
