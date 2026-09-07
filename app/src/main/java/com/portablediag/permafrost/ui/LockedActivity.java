package com.portablediag.permafrost.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.portablediag.permafrost.R;
import com.portablediag.permafrost.core.AppLock;

/**
 * Base for every Permafrost screen that can thaw, re-freeze, un-manage or list
 * managed apps. When the lock is on, the content view is hidden and a challenge
 * is raised in {@code onStart}; refusing it closes the whole task rather than
 * dropping the user onto the screen underneath.
 *
 * <p>Gating in {@code onStart} rather than only in {@code MainActivity} matters
 * because Android can restore a task straight onto whichever activity was on top.
 */
public abstract class LockedActivity extends AppCompatActivity {

    private ActivityResultLauncher<Intent> credential;
    private boolean prompting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Must be registered before onStart, which is where the challenge is raised.
        credential = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> onAuthResult(result.getResultCode() == RESULT_OK));
    }

    @Override
    protected void onStart() {
        super.onStart();
        AppLock.onActivityStart();

        boolean enabled = AppLock.isEnabled(this);
        // Keep the managed-app list out of the recents thumbnail while locked.
        if (enabled) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }

        if (!enabled || AppLock.isUnlocked()) {
            showContent(true);
            onUnlocked();
            return;
        }
        if (prompting) return;
        prompting = true;
        showContent(false);
        AppLock.prompt(this, credential, this::onAuthResult);
    }

    @Override
    protected void onStop() {
        super.onStop();
        AppLock.onActivityStop();
    }

    /** Both challenge paths — biometric and keyguard — land here. */
    private void onAuthResult(boolean ok) {
        prompting = false;
        AppLock.authFinished();
        if (ok) {
            AppLock.markUnlocked();
            showContent(true);
            onUnlocked();
        } else {
            // Close the task, not just this activity: finishing alone would
            // reveal whatever locked screen is underneath.
            finishAffinity();
        }
    }

    private void showContent(boolean visible) {
        View root = findViewById(R.id.root);
        if (root != null) root.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }

    /**
     * Called once the screen is cleared to be shown. Subclasses put anything
     * that must not appear over the lock — dialogs, in particular — here.
     */
    protected void onUnlocked() {
    }
}
