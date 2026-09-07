package com.portablediag.permafrost.core;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import androidx.activity.result.ActivityResultLauncher;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.portablediag.permafrost.R;
import com.portablediag.permafrost.model.Store;

/**
 * Optional lock in front of Permafrost's own UI: biometric if the user has one
 * enrolled, otherwise the device PIN / pattern / password.
 *
 * <p>Deliberately <em>not</em> applied to {@code ProxyActivity} — a frost icon
 * has to stay a one-tap wake, and prompting there would gate every launch.
 * The lock protects the screens that thaw, re-freeze, un-manage and list apps.
 *
 * <p>The unlocked state is process-wide and lives only in memory, so it dies
 * with the process. {@link #onActivityStop()} / {@link #onActivityStart()} count
 * visible activities; the lock re-arms once the last one has been gone longer
 * than {@link #GRACE_MS}. The grace exists so a rotation or an activity handover
 * — both of which briefly drop the count to zero — does not re-prompt.
 */
public final class AppLock {

    /** What this device can actually challenge the user with. */
    public enum Method { NONE, BIOMETRIC, CREDENTIAL }

    /** Result of a challenge. {@code false} means refused, cancelled or errored. */
    public interface Callback {
        void onResult(boolean unlocked);
    }

    private static final long GRACE_MS = 2000L;

    /** Biometrics we accept. STRONG is not required — this gates a UI, not a key. */
    private static final int BIOMETRICS = BiometricManager.Authenticators.BIOMETRIC_WEAK;

    private static boolean unlocked;
    private static int visible;
    private static long leftAt;
    /** True while a challenge is on screen, so its own activity churn can't re-lock us. */
    private static boolean authInProgress;

    private AppLock() {}

    public static boolean isEnabled(Context ctx) {
        return Store.get(ctx).appLockEnabled();
    }

    /**
     * Whether a frost-icon tap is challenged too. Off by default and meaningless
     * on its own — the master lock has to be on as well.
     */
    public static boolean locksFrostIcons(Context ctx) {
        Store store = Store.get(ctx);
        return store.appLockEnabled() && store.lockFrostIcons();
    }

    /**
     * What the device can challenge with right now. Re-read every time: the user
     * can enrol or remove a screen lock while Permafrost is installed.
     */
    public static Method available(Context ctx) {
        BiometricManager bm = BiometricManager.from(ctx);
        if (bm.canAuthenticate(BIOMETRICS) == BiometricManager.BIOMETRIC_SUCCESS) {
            return Method.BIOMETRIC;
        }
        KeyguardManager km = ctx.getSystemService(KeyguardManager.class);
        if (km != null && km.isDeviceSecure()) return Method.CREDENTIAL;
        return Method.NONE;
    }

    // ---- session state ----

    /**
     * Evaluated on read rather than only on an activity transition, because
     * {@code ProxyActivity} asks this question without ever entering the
     * visible-activity count.
     */
    public static boolean isUnlocked() {
        expire();
        return unlocked;
    }

    public static void markUnlocked() {
        unlocked = true;
        // Start the grace window from the unlock, so a wake through a frost icon
        // leaves a usable session behind it rather than expiring instantly.
        leftAt = SystemClock.elapsedRealtime();
    }

    /** Re-arms the lock once Permafrost has genuinely been away long enough. */
    private static void expire() {
        if (!unlocked || authInProgress || visible > 0) return;
        if (SystemClock.elapsedRealtime() - leftAt > GRACE_MS) unlocked = false;
    }

    /** Called when a locked activity becomes visible; re-arms after a real absence. */
    public static void onActivityStart() {
        expire();
        visible++;
    }

    /** Called when a locked activity stops; records when the last one went away. */
    public static void onActivityStop() {
        visible--;
        if (visible <= 0) {
            visible = 0;
            leftAt = SystemClock.elapsedRealtime();
        }
    }

    /** Clears the challenge-in-flight guard, whatever the outcome. */
    public static void authFinished() {
        authInProgress = false;
    }

    // ---- the challenge ----

    /**
     * Challenges the user. The biometric path answers through {@code cb}; the
     * credential path answers through {@code credential}'s own result callback,
     * so callers must funnel both into one place.
     *
     * <p>If the device has nothing to challenge with — the user removed their
     * screen lock after enabling the setting — this fails <em>open</em> rather
     * than locking them out of their own app.
     */
    public static void prompt(FragmentActivity host,
                              ActivityResultLauncher<Intent> credential,
                              Callback cb) {
        Method m = available(host);
        if (m == Method.NONE) {
            cb.onResult(true);
            return;
        }
        authInProgress = true;
        if (m == Method.BIOMETRIC && tryBiometric(host, cb)) return;
        if (!launchCredential(host, credential)) {
            // Nothing worked; don't strand the user behind a lock we can't show.
            authInProgress = false;
            cb.onResult(true);
        }
    }

    /** @return true if the prompt was actually shown and will call back. */
    private static boolean tryBiometric(FragmentActivity host, Callback cb) {
        BiometricPrompt prompt = new BiometricPrompt(host,
                ContextCompat.getMainExecutor(host),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                        cb.onResult(true);
                    }

                    @Override
                    public void onAuthenticationError(int code, CharSequence msg) {
                        // Cancel, lockout or a hardware error. All are a refusal;
                        // a single bad finger arrives as onAuthenticationFailed
                        // instead and leaves the prompt up, so it needs no handling.
                        cb.onResult(false);
                    }
                });
        try {
            // DEVICE_CREDENTIAL gives the prompt its own "use PIN" fallback, so no
            // negative button may be set alongside it — setNegativeButtonText()
            // here would throw at build() time.
            prompt.authenticate(new BiometricPrompt.PromptInfo.Builder()
                    .setTitle(host.getString(R.string.lock_title))
                    .setSubtitle(host.getString(R.string.lock_subtitle))
                    .setAllowedAuthenticators(
                            BIOMETRICS | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build());
            return true;
        } catch (RuntimeException e) {
            // Some API 28/29 ROMs reject the combined authenticator set. Fall
            // through to the keyguard, which every version supports.
            return false;
        }
    }

    private static boolean launchCredential(FragmentActivity host,
                                            ActivityResultLauncher<Intent> credential) {
        KeyguardManager km = host.getSystemService(KeyguardManager.class);
        if (km == null || credential == null) return false;
        Intent i = km.createConfirmDeviceCredentialIntent(
                host.getString(R.string.lock_title), host.getString(R.string.lock_subtitle));
        if (i == null) return false;
        try {
            credential.launch(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
