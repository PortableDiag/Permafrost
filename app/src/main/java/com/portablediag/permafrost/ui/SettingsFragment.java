package com.portablediag.permafrost.ui;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.text.InputType;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.EditTextPreference;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.portablediag.permafrost.R;
import com.portablediag.permafrost.api.ApiPrefs;
import com.portablediag.permafrost.api.ApiService;
import com.portablediag.permafrost.api.ApiStatus;
import com.portablediag.permafrost.core.AppLock;
import com.portablediag.permafrost.core.ForegroundApps;
import com.portablediag.permafrost.core.Root;
import com.portablediag.permafrost.model.Mode;
import com.portablediag.permafrost.model.Store;

/**
 * Settings built programmatically so every value reads and writes {@link Store}
 * directly — one source of truth, no key drift between the two prefs files.
 */
public class SettingsFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        final Store store = Store.get(requireContext());
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());

        addAppearance(screen, store);

        // --- Behaviour ---
        PreferenceCategory behaviour = new PreferenceCategory(requireContext());
        behaviour.setTitle(R.string.settings_behaviour);
        screen.addPreference(behaviour);

        ListPreference defMode = new ListPreference(requireContext());
        defMode.setKey("default_mode");
        defMode.setTitle(R.string.settings_default_method);
        defMode.setDialogTitle(R.string.settings_default_method);
        defMode.setEntries(new CharSequence[]{
                getString(R.string.mode_freeze_long), getString(R.string.mode_ghost_long)});
        defMode.setEntryValues(new CharSequence[]{Mode.FREEZE.name(), Mode.GHOST.name()});
        defMode.setValue(store.defaultMode().name());
        defMode.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        defMode.setOnPreferenceChangeListener((p, v) -> {
            store.setDefaultMode(Mode.fromName(v.toString(), Mode.FREEZE));
            return true;
        });
        behaviour.addPreference(defMode);

        SeekBarPreference delay = new SeekBarPreference(requireContext());
        delay.setKey("refreeze_delay");
        delay.setTitle(R.string.settings_refreeze_delay);
        delay.setSummary(R.string.settings_refreeze_delay_sum);
        delay.setMin(0);
        delay.setMax(15);
        delay.setSeekBarIncrement(1);
        delay.setShowSeekBarValue(true);
        delay.setValue(store.refreezeDelaySec());
        delay.setUpdatesContinuously(false);
        delay.setOnPreferenceChangeListener((p, v) -> {
            store.setRefreezeDelaySec((Integer) v);
            return true;
        });
        behaviour.addPreference(delay);

        // --- Security ---
        PreferenceCategory security = new PreferenceCategory(requireContext());
        security.setTitle(R.string.settings_security);
        screen.addPreference(security);

        // Re-read what the device can challenge with every time this screen is
        // built: the user can add or remove a screen lock at any point.
        AppLock.Method method = AppLock.available(requireContext());
        SwitchPreferenceCompat appLock = new SwitchPreferenceCompat(requireContext());
        appLock.setKey("app_lock");
        appLock.setTitle(R.string.settings_app_lock);
        appLock.setEnabled(method != AppLock.Method.NONE);
        appLock.setChecked(store.appLockEnabled() && method != AppLock.Method.NONE);
        appLock.setSummary(method == AppLock.Method.BIOMETRIC ? R.string.settings_app_lock_biometric
                : method == AppLock.Method.CREDENTIAL ? R.string.settings_app_lock_credential
                : R.string.settings_app_lock_unavailable);
        final SwitchPreferenceCompat lockIcons = new SwitchPreferenceCompat(requireContext());
        lockIcons.setKey("app_lock_icons");
        lockIcons.setTitle(R.string.settings_lock_icons);
        lockIcons.setSummary(R.string.settings_lock_icons_sum);
        // Only meaningful while the master lock is on, so it follows it.
        lockIcons.setEnabled(appLock.isChecked());
        lockIcons.setChecked(store.lockFrostIcons() && appLock.isChecked());
        lockIcons.setOnPreferenceChangeListener((p, v) -> {
            store.setLockFrostIcons((Boolean) v);
            return true;
        });

        appLock.setOnPreferenceChangeListener((p, v) -> {
            boolean on = (Boolean) v;
            store.setAppLockEnabled(on);
            lockIcons.setEnabled(on);
            return true;
        });
        security.addPreference(appLock);
        security.addPreference(lockIcons);

        addApi(screen);

        // --- Permissions / status ---
        PreferenceCategory status = new PreferenceCategory(requireContext());
        status.setTitle(R.string.settings_status);
        screen.addPreference(status);

        Preference usage = new Preference(requireContext());
        usage.setKey("usage_access");
        usage.setTitle(R.string.settings_usage_access);
        usage.setOnPreferenceClickListener(p -> {
            startActivity(ForegroundApps.usageAccessSettings());
            return true;
        });
        status.addPreference(usage);

        Preference root = new Preference(requireContext());
        root.setKey("root_status");
        root.setTitle(R.string.settings_root);
        root.setSelectable(false);
        status.addPreference(root);

        // Root check is blocking; run it off the UI thread.
        new Thread(() -> {
            boolean ok = Root.isAvailable();
            if (isAdded()) {
                requireActivity().runOnUiThread(() ->
                        root.setSummary(ok ? R.string.root_available : R.string.root_missing));
            }
        }).start();
        this.usagePref = usage;

        setPreferenceScreen(screen);
    }

    private Preference usagePref;

    // ---- Appearance ----

    private void addAppearance(PreferenceScreen screen, Store store) {
        Context ctx = requireContext();
        PreferenceCategory cat = new PreferenceCategory(ctx);
        cat.setTitle(R.string.settings_appearance);
        screen.addPreference(cat);

        Palette[] all = Palette.values();
        CharSequence[] labels = new CharSequence[all.length];
        CharSequence[] ids = new CharSequence[all.length];
        for (int i = 0; i < all.length; i++) {
            labels[i] = all[i].label;
            ids[i] = all[i].id;
        }
        final Palette current = Themes.current(ctx);
        ListPreference palette = new ListPreference(ctx);
        palette.setKey("palette");
        palette.setTitle(R.string.settings_palette);
        palette.setDialogTitle(R.string.settings_palette);
        palette.setEntries(labels);
        palette.setEntryValues(ids);
        palette.setValue(current.id);
        palette.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        palette.setOnPreferenceChangeListener((p, v) -> {
            Themes.change(ctx, v.toString(), null);
            return true;
        });
        cat.addPreference(palette);

        ListPreference dark = new ListPreference(ctx);
        dark.setKey("dark_mode");
        dark.setTitle(R.string.settings_dark_mode);
        dark.setDialogTitle(R.string.settings_dark_mode);
        dark.setEntries(new CharSequence[]{getString(R.string.dark_mode_system),
                getString(R.string.dark_mode_light), getString(R.string.dark_mode_dark)});
        dark.setEntryValues(Themes.DARK_MODES);
        dark.setValue(store.darkMode());
        // Only palettes with a light form follow it; say so rather than grey it out silently.
        dark.setEnabled(current.followsMode);
        if (current.followsMode) {
            dark.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        } else {
            dark.setSummary(getString(R.string.settings_dark_mode_fixed, current.label));
        }
        dark.setOnPreferenceChangeListener((p, v) -> {
            Themes.change(ctx, null, v.toString());
            return true;
        });
        cat.addPreference(dark);
    }

    // ---- Local API ----

    private SwitchPreferenceCompat apiSwitch;
    private Preference apiKey;
    private final Runnable apiListener = () -> {
        if (isAdded()) requireActivity().runOnUiThread(this::renderApi);
    };

    private void addApi(PreferenceScreen screen) {
        final Context ctx = requireContext();
        PreferenceCategory cat = new PreferenceCategory(ctx);
        cat.setTitle(R.string.settings_api);
        screen.addPreference(cat);

        apiSwitch = new SwitchPreferenceCompat(ctx);
        apiSwitch.setKey("api_enabled");
        apiSwitch.setTitle(R.string.settings_api_enable);
        apiSwitch.setChecked(ApiPrefs.enabled(ctx));
        apiSwitch.setOnPreferenceChangeListener((p, v) -> {
            boolean on = (Boolean) v;
            ApiPrefs.setEnabled(ctx, on);
            if (on) ApiService.startIfEnabled(ctx);
            else ApiService.stop(ctx);
            apiSwitch.setChecked(on);
            renderApi();
            return false; // already applied
        });
        cat.addPreference(apiSwitch);

        apiKey = new Preference(ctx);
        apiKey.setKey("api_key");
        apiKey.setTitle(R.string.settings_api_key);
        apiKey.setOnPreferenceClickListener(p -> {
            copyKey();
            return true;
        });
        cat.addPreference(apiKey);

        Preference newKey = new Preference(ctx);
        newKey.setKey("api_new_key");
        newKey.setTitle(R.string.settings_api_new_key);
        newKey.setSummary(R.string.settings_api_new_key_sum);
        newKey.setOnPreferenceClickListener(p -> {
            new AlertDialog.Builder(requireActivity())
                    .setTitle(R.string.settings_api_new_key)
                    .setMessage(R.string.settings_api_new_key_confirm)
                    .setPositiveButton(R.string.settings_api_new_key, (d, w) -> {
                        ApiPrefs.regenerateKey(ctx);
                        renderApi();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        });
        cat.addPreference(newKey);

        EditTextPreference port = new EditTextPreference(ctx);
        port.setKey("api_port_text");
        port.setPersistent(false);
        port.setTitle(R.string.settings_api_port);
        port.setDialogTitle(R.string.settings_api_port);
        port.setText(String.valueOf(ApiPrefs.port(ctx)));
        port.setSummaryProvider(p -> String.valueOf(ApiPrefs.port(ctx)));
        port.setOnBindEditTextListener(e -> e.setInputType(InputType.TYPE_CLASS_NUMBER));
        port.setOnPreferenceChangeListener((p, v) -> {
            int n;
            try {
                n = Integer.parseInt(v.toString().trim());
            } catch (NumberFormatException e) {
                n = -1;
            }
            if (n < ApiPrefs.MIN_PORT || n > ApiPrefs.MAX_PORT) {
                Toast.makeText(ctx, R.string.settings_api_port_bad, Toast.LENGTH_LONG).show();
                return false;
            }
            ApiPrefs.setPort(ctx, n);
            ApiService.restart(ctx);
            return true;
        });
        cat.addPreference(port);

        SwitchPreferenceCompat lan = new SwitchPreferenceCompat(ctx);
        lan.setKey("api_lan");
        lan.setPersistent(false);
        lan.setTitle(R.string.settings_api_lan);
        lan.setSummary(R.string.settings_api_lan_sum);
        lan.setChecked(ApiPrefs.lan(ctx));
        lan.setOnPreferenceChangeListener((p, v) -> {
            ApiPrefs.setLan(ctx, (Boolean) v);
            ApiService.restart(ctx);
            return true;
        });
        cat.addPreference(lan);

        Preference docs = new Preference(ctx);
        docs.setKey("api_docs");
        docs.setSummary(R.string.settings_api_docs);
        docs.setSelectable(false);
        cat.addPreference(docs);

        renderApi();
    }

    private void renderApi() {
        if (apiSwitch == null || !isAdded()) return;
        Context ctx = requireContext();
        boolean on = ApiPrefs.enabled(ctx);
        if (!on) {
            apiSwitch.setSummary(R.string.settings_api_off);
        } else if (ApiStatus.running()) {
            apiSwitch.setSummary(getString(R.string.settings_api_on, ApiStatus.address()));
        } else if (ApiStatus.error() != null) {
            apiSwitch.setSummary(getString(R.string.settings_api_error, ApiStatus.error()));
        } else {
            apiSwitch.setSummary(R.string.settings_api_starting);
        }
        String key = ApiPrefs.key(ctx);
        apiKey.setSummary(key.isEmpty() ? getString(R.string.settings_api_key_none) : key);
        apiKey.setEnabled(!key.isEmpty());
    }

    /** Marked sensitive, so Android 13+ doesn't preview the key in its clipboard overlay. */
    private void copyKey() {
        Context ctx = requireContext();
        String key = ApiPrefs.key(ctx);
        if (key.isEmpty()) return;
        ClipboardManager cm = ctx.getSystemService(ClipboardManager.class);
        if (cm == null) return;
        ClipData clip = ClipData.newPlainText("Permafrost API key", key);
        PersistableBundle extras = new PersistableBundle();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
        } else {
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        }
        clip.getDescription().setExtras(extras);
        cm.setPrimaryClip(clip);
        Toast.makeText(ctx, R.string.settings_api_key_copied, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onStart() {
        super.onStart();
        ApiStatus.addListener(apiListener);
        renderApi();
    }

    @Override
    public void onStop() {
        ApiStatus.removeListener(apiListener);
        super.onStop();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (usagePref != null) {
            boolean ok = ForegroundApps.hasUsageAccess(requireContext());
            usagePref.setSummary(ok ? R.string.granted : R.string.not_granted);
        }
    }
}
