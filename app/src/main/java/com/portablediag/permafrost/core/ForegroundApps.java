package com.portablediag.permafrost.core;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Process;
import android.provider.Settings;

/**
 * Detects the current foreground package via {@link UsageStatsManager}. Requires
 * the "Usage access" special permission (PACKAGE_USAGE_STATS).
 */
public class ForegroundApps {

    /** Whether Usage Access has been granted to us. */
    public static boolean hasUsageAccess(Context ctx) {
        AppOpsManager appOps = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) return false;
        int mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                ctx.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /** Intent to open the system Usage Access settings screen. */
    public static android.content.Intent usageAccessSettings() {
        return new android.content.Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
    }

    /**
     * The package currently in the foreground, or null if unknown. Works by
     * scanning recent MOVE_TO_FOREGROUND events and taking the latest.
     */
    public static String current(Context ctx, long lookbackMs) {
        UsageStatsManager usm =
                (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return null;
        long now = System.currentTimeMillis();
        UsageEvents events = usm.queryEvents(now - lookbackMs, now);
        UsageEvents.Event e = new UsageEvents.Event();
        String last = null;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            int type = e.getEventType();
            if (type == UsageEvents.Event.MOVE_TO_FOREGROUND
                    || type == UsageEvents.Event.ACTIVITY_RESUMED) {
                last = e.getPackageName();
            }
        }
        return last;
    }
}
