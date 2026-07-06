package com.portablediag.permafrost.core;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Enumerates launchable, non-system apps a user might want to freeze. */
public class InstalledApps {

    public static class Entry {
        public final String pkg;
        public final String label;

        Entry(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    /**
     * All launchable apps except this app itself. Includes disabled ones (managed
     * frozen apps) so they can still be found. Sorted by label.
     */
    public static List<Entry> launchable(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Set<String> seen = new HashSet<>();
        List<Entry> out = new ArrayList<>();

        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        int flags = PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_UNINSTALLED_PACKAGES;
        for (ResolveInfo ri : pm.queryIntentActivities(main, flags)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || pkg.equals(ctx.getPackageName())) continue;
            if (!seen.add(pkg)) continue;
            String label = ri.loadLabel(pm).toString();
            out.add(new Entry(pkg, label));
        }
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    public static String labelOf(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES);
            return pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** Icon for an app; falls back to a cached frosted icon for ghosted apps. */
    public static Drawable iconOf(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES);
            return pm.getApplicationIcon(ai);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }
}
