package com.portablediag.permafrost.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.color.DynamicColors;
import com.portablediag.permafrost.R;
import com.portablediag.permafrost.model.Store;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Applies the chosen house {@link Palette}.
 *
 * <p>Material You, Ocean and Terminal follow the light/dark setting; every other
 * palette is one look, so the night mode is forced to match it and dialogs and
 * widgets that read the night qualifier agree with the palette. Status- and
 * navigation-bar icons follow the palette's background, not the system mode.
 */
public final class Themes {

    public static final String[] DARK_MODES = {"system", "light", "dark"};

    /** Bumped on every change, so a screen left open under an old palette rebuilds. */
    private static int generation;

    private Themes() {}

    public static Palette current(Context ctx) {
        return Palette.fromId(Store.get(ctx).palette(), Palette.MATERIAL_YOU);
    }

    /** Set the process-wide night mode for the current palette. Recreates started activities. */
    public static void applyNightMode(Context ctx) {
        Palette p = current(ctx);
        int mode;
        if (!p.followsMode) {
            mode = p.lightOnly ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_YES;
        } else {
            switch (Store.get(ctx).darkMode()) {
                case "light": mode = AppCompatDelegate.MODE_NIGHT_NO; break;
                case "dark": mode = AppCompatDelegate.MODE_NIGHT_YES; break;
                default: mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM; break;
            }
        }
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode);
        }
    }

    /** Save a new palette and/or light-dark choice and apply it. Null leaves that one as is. */
    public static void change(Context ctx, String palette, String darkMode) {
        Store store = Store.get(ctx);
        if (palette != null) store.setPalette(palette);
        if (darkMode != null) store.setDarkMode(darkMode);
        generation++;
        applyNightMode(ctx);
        // A night-mode change recreates started screens by itself; a palette
        // change between two dark palettes doesn't, so rebuild them here.
        for (Activity a : new ArrayList<>(started)) {
            if (!a.isFinishing()) a.recreate();
        }
    }

    /** Screens currently started — the ones a change must rebuild while visible. Main thread only. */
    private static final Set<Activity> started = new HashSet<>();

    static void onStart(Activity a) {
        started.add(a);
    }

    static void onStop(Activity a) {
        started.remove(a);
    }

    public static int generation() {
        return generation;
    }

    /** Call before {@code setContentView}. Returns the generation it applied. */
    public static int apply(Activity a) {
        Palette p = current(a);
        if (p.isDynamic()) {
            DynamicColors.applyToActivityIfAvailable(a);
        } else {
            int style = isNight(a) || p.lightStyle == 0 ? p.darkStyle : p.lightStyle;
            a.getTheme().applyStyle(style, true);
        }
        if (p.mono) a.getTheme().applyStyle(R.style.ThemeOverlay_Permafrost_Mono, true);
        return generation;
    }

    /** Light or dark bar icons to suit the palette's background. */
    public static void bars(Activity a) {
        Palette p = current(a);
        boolean lightBg = p.lightOnly || (p.followsMode && !isNight(a));
        WindowInsetsControllerCompat c =
                WindowCompat.getInsetsController(a.getWindow(), a.getWindow().getDecorView());
        c.setAppearanceLightStatusBars(lightBg);
        c.setAppearanceLightNavigationBars(lightBg);
    }

    private static boolean isNight(Context ctx) {
        int ui = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return ui == Configuration.UI_MODE_NIGHT_YES;
    }
}
