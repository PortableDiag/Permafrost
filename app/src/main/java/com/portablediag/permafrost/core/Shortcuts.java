package com.portablediag.permafrost.core;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;

import com.portablediag.permafrost.ProxyActivity;

/**
 * Places a "frost icon" on the home screen: a pinned launcher shortcut whose
 * icon is the frosted app icon and whose tap target is {@link ProxyActivity},
 * carrying the target package.
 */
public class Shortcuts {

    public static final String EXTRA_PACKAGE = "com.portablediag.permafrost.PACKAGE";

    public static boolean isSupported(Context ctx) {
        ShortcutManager sm = ctx.getSystemService(ShortcutManager.class);
        return sm != null && sm.isRequestPinShortcutSupported();
    }

    /**
     * Ask the launcher to pin a frost shortcut for {@code pkg}. The frosted icon
     * is generated (and cached) here so it works even once the target is ghosted
     * away.
     */
    public static boolean pin(Context ctx, String pkg, String label) {
        ShortcutManager sm = ctx.getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) return false;

        Bitmap frosted = IconFrost.frostedIcon(ctx, pkg);
        if (frosted == null) frosted = IconFrost.loadCached(ctx, pkg);
        Icon icon = null;
        if (frosted != null) {
            IconFrost.cacheFrosted(ctx, pkg, frosted);
            icon = Icon.createWithBitmap(frosted);
        }

        Intent launch = new Intent(ctx, ProxyActivity.class);
        launch.setAction(Intent.ACTION_VIEW);
        launch.putExtra(EXTRA_PACKAGE, pkg);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        ShortcutInfo.Builder b = new ShortcutInfo.Builder(ctx, "frost_" + pkg)
                .setShortLabel(label == null ? pkg : label)
                .setIntent(launch);
        if (icon != null) b.setIcon(icon);

        return sm.requestPinShortcut(b.build(), null);
    }
}
