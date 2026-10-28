package com.portablediag.permafrost.api;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import com.portablediag.permafrost.R;
import com.portablediag.permafrost.ui.SettingsActivity;

/**
 * Hosts the local API for as long as it is switched on — its own foreground
 * service, separate from {@code WatcherService}, which exists only while an app
 * is awake. The notification says plainly that Permafrost is listening on a
 * port: an open socket should never be a thing you have to go looking for.
 */
public class ApiService extends Service {

    private static final String TAG = "PermafrostApi";
    private static final String CHANNEL = "api";
    /** WatcherService uses 42. */
    private static final int NOTIF_ID = 43;
    private static final String ACTION_STOP = "com.portablediag.permafrost.api.STOP";

    private ApiServer server;

    /** Starts the API if it is enabled; a no-op otherwise, so callers needn't check first. */
    public static void startIfEnabled(Context ctx) {
        if (!ApiPrefs.enabled(ctx)) return;
        try {
            ctx.startForegroundService(new Intent(ctx, ApiService.class));
        } catch (Exception e) {
            // Android 12+ refuses a foreground start from the background outside
            // the exempt cases (boot, package replaced); the next launch retries.
            Log.w(TAG, "could not start the API service", e);
        }
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, ApiService.class));
    }

    /** For a port or LAN change, which needs a fresh socket. */
    public static void restart(Context ctx) {
        stop(ctx);
        startIfEnabled(ctx);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // The notification's own Stop: turn the setting off too, or the next launch reopens it.
            ApiPrefs.setEnabled(this, false);
            ApiStatus.update(false, "", null);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ApiPrefs.enabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (server != null && server.isRunning()) return START_STICKY;

        String address = ApiPrefs.bindAddress(this);
        int port = ApiPrefs.port(this);
        final Context app = getApplicationContext();
        PhoneApiAccess.revokeIfGone(app);
        ApiRouter router = new ApiRouter(new PermafrostBackend(app), () -> ApiPrefs.key(app),
                () -> PhoneApiAccess.grant(app));
        ApiServer api = new ApiServer(router, address, port);

        // Foreground first: a service that binds before it has posted its
        // notification can be killed with the socket open.
        startForegroundWith(api.address());
        try {
            api.start();
            server = api;
            ApiStatus.update(true, api.address(), null);
            return START_STICKY;
        } catch (Exception e) {
            Log.w(TAG, "could not listen on " + api.address(), e);
            ApiStatus.update(false, "", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            stopSelf();
            return START_NOT_STICKY;
        }
    }

    @Override
    public void onDestroy() {
        if (server != null) server.stop();
        server = null;
        ApiStatus.update(false, "", ApiStatus.error());
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startForegroundWith(String address) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, getString(R.string.api_channel), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, SettingsActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ApiService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle(getString(R.string.api_notification_title))
                .setContentText(getString(R.string.api_notification_text, address))
                .setSmallIcon(R.drawable.ic_snowflake)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, getString(R.string.api_stop), stop).build())
                .setOngoing(true)
                .setShowWhen(false)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }
}
