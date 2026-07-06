package com.portablediag.permafrost.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.portablediag.permafrost.R;
import com.portablediag.permafrost.model.ManagedApp;
import com.portablediag.permafrost.model.Store;

import java.util.HashMap;
import java.util.Map;

/**
 * Foreground service that watches launched apps and re-freezes each one once the
 * user leaves it. Polls the foreground package roughly once a second via
 * {@link ForegroundApps}. Keeps running only while at least one app is being
 * watched.
 */
public class WatcherService extends Service {

    private static final String ACTION_WATCH = "com.portablediag.permafrost.WATCH";
    private static final String EXTRA_PKG = "pkg";
    private static final String CHANNEL = "watcher";
    private static final int NOTIF_ID = 42;

    private static final long POLL_MS = 1000L;
    /** If a target never comes to the foreground within this window, give up and re-freeze. */
    private static final long SEEN_TIMEOUT_MS = 20_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Watch> watches = new HashMap<>();
    private boolean looping = false;

    private static class Watch {
        boolean seen;
        long startedAt;
        long leftAt; // 0 = currently foreground / not yet left
    }

    /** Start (or add to) watching a package for re-freeze on exit. */
    public static void watch(Context ctx, String pkg) {
        Intent i = new Intent(ctx, WatcherService.class);
        i.setAction(ACTION_WATCH);
        i.putExtra(EXTRA_PKG, pkg);
        ctx.startForegroundService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIF_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_WATCH.equals(intent.getAction())) {
            String pkg = intent.getStringExtra(EXTRA_PKG);
            if (pkg != null) {
                Watch w = new Watch();
                w.startedAt = System.currentTimeMillis();
                watches.put(pkg, w);
                updateNotification();
            }
        }
        if (!looping) {
            looping = true;
            handler.postDelayed(tick, POLL_MS);
        }
        return START_STICKY;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                pollOnce();
            } catch (Exception ignored) {
            }
            if (watches.isEmpty()) {
                looping = false;
                stopSelf();
            } else {
                handler.postDelayed(this, POLL_MS);
            }
        }
    };

    private void pollOnce() {
        if (watches.isEmpty()) return;
        long now = System.currentTimeMillis();
        String me = getPackageName();
        // Look back to the oldest active watch so a long stay is still detected.
        long oldest = now;
        for (Watch w : watches.values()) oldest = Math.min(oldest, w.startedAt);
        long lookback = Math.max(POLL_MS * 3, now - oldest + 2000);
        String fg = ForegroundApps.current(this, lookback);

        for (Map.Entry<String, Watch> e : new HashMap<>(watches).entrySet()) {
            String pkg = e.getKey();
            Watch w = e.getValue();

            if (pkg.equals(fg)) {
                w.seen = true;
                w.leftAt = 0;
                continue;
            }
            // Our own UI/proxy in front is neutral — don't count as "left".
            if (me.equals(fg)) {
                continue;
            }
            if (w.seen) {
                if (w.leftAt == 0) w.leftAt = now;
                if (now - w.leftAt >= refreezeDelayMs()) {
                    refreeze(pkg);
                }
            } else if (now - w.startedAt >= SEEN_TIMEOUT_MS) {
                // Launch apparently failed; restore dormant state anyway.
                refreeze(pkg);
            }
        }
    }

    private long refreezeDelayMs() {
        return Math.max(0, Store.get(this).refreezeDelaySec()) * 1000L;
    }

    private void refreeze(String pkg) {
        watches.remove(pkg);
        updateNotification();
        final Context app = getApplicationContext();
        new Thread(() -> {
            ManagedApp m = Store.get(app).get(pkg);
            if (m != null && !m.updateUnlocked) {
                Manager.makeDormant(app, m);
            }
        }).start();
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, getString(R.string.watcher_channel),
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        int n = watches.size();
        String text = getResources().getQuantityString(
                R.plurals.watching_apps, Math.max(n, 1), Math.max(n, 1));
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_snowflake)
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification());
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
