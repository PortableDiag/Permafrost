package com.portablediag.permafrost.ui;

import android.os.Bundle;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.portablediag.permafrost.R;
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

    @Override
    public void onResume() {
        super.onResume();
        if (usagePref != null) {
            boolean ok = ForegroundApps.hasUsageAccess(requireContext());
            usagePref.setSummary(ok ? R.string.granted : R.string.not_granted);
        }
    }
}
