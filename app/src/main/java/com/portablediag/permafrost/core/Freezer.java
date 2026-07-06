package com.portablediag.permafrost.core;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * Method A: freeze/thaw an app by disabling it for the current user.
 *
 * <p>{@code pm disable-user --user 0 <pkg>} makes the app un-launchable and hides
 * its icons while preserving the install and all data. {@code pm enable <pkg>}
 * reverses it. Both persist across reboots.
 */
public class Freezer {

    /** Freeze (disable) the app. Returns null on success, else an error message. */
    public static String freeze(String pkg) {
        Root.Result r = Root.run("pm disable-user --user 0 " + pkg);
        // pm prints "new state: disabled-user" on success.
        if (r.ok() && (r.combined().contains("disabled") || r.combined().isEmpty())) {
            return null;
        }
        // Some ROMs need the plain form.
        Root.Result r2 = Root.run("pm disable " + pkg);
        if (r2.ok() && (r2.combined().contains("disabled") || r2.combined().isEmpty())) {
            return null;
        }
        return "freeze failed: " + r.combined();
    }

    /** Thaw (enable) the app. Returns null on success, else an error message. */
    public static String thaw(String pkg) {
        Root.Result r = Root.run("pm enable " + pkg);
        if (r.ok() && (r.combined().contains("enabled") || r.combined().isEmpty())) {
            return null;
        }
        return "thaw failed: " + r.combined();
    }

    /** True if the app is currently disabled for user 0. */
    public static boolean isFrozen(String pkg) {
        // `pm list packages -d` lists disabled packages.
        for (String line : Root.lines("pm list packages -d")) {
            if (line.equals("package:" + pkg)) return true;
        }
        return false;
    }

    /** True if the package is installed at all (enabled or disabled). */
    public static boolean isInstalled(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Launch the app's main activity. Tries the normal launch intent first;
     * if the PackageManager hasn't caught up to the just-enabled state, falls
     * back to launching via root.
     */
    public static void launch(Context ctx, String pkg) {
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        launchViaRoot(pkg);
    }

    /** Resolve and start the launcher activity through the root shell. */
    public static void launchViaRoot(String pkg) {
        Root.Result res = Root.run(
                "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER " + pkg
                        + " | tail -n 1");
        String comp = res.out == null ? "" : res.out.trim();
        if (!comp.isEmpty() && comp.contains("/")) {
            Root.run("am start -n " + comp);
        } else {
            // Last resort.
            Root.run("monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1");
        }
    }
}
