package com.portablediag.permafrost;

import android.app.Application;

import com.portablediag.permafrost.core.CrashHandler;

/** Installs the global crash handler as early as possible. */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashHandler.install(this);
    }
}
