package com.portablediag.permafrost.api;

import java.util.ArrayList;
import java.util.List;

/**
 * What the API is doing, for Settings to show. The service publishes here; a
 * listener redraws the screen if it is open.
 */
public final class ApiStatus {

    private static boolean running;
    private static String address = "";
    /** Why it is not running, when the user can fix it (a port already in use). */
    private static String error;

    private static final List<Runnable> listeners = new ArrayList<>();

    private ApiStatus() {}

    public static synchronized boolean running() {
        return running;
    }

    public static synchronized String address() {
        return address;
    }

    public static synchronized String error() {
        return error;
    }

    public static void addListener(Runnable l) {
        synchronized (listeners) {
            listeners.add(l);
        }
    }

    public static void removeListener(Runnable l) {
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    static void update(boolean isRunning, String addr, String err) {
        synchronized (ApiStatus.class) {
            running = isRunning;
            address = addr;
            error = err;
        }
        List<Runnable> copy;
        synchronized (listeners) {
            copy = new ArrayList<>(listeners);
        }
        for (Runnable l : copy) l.run();
    }
}
