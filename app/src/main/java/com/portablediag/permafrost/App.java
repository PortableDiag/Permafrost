package com.portablediag.permafrost;

import android.app.Application;

import com.portablediag.permafrost.core.CrashHandler;
import com.portablediag.permafrost.ui.Themes;

/** Installs the global crash handler as early as possible, and the palette's night mode. */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashHandler.install(this);
        Themes.applyNightMode(this);
    }
}
