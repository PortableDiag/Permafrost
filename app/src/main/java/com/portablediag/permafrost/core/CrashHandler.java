package com.portablediag.permafrost.core;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Process;

import androidx.core.content.pm.PackageInfoCompat;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/**
 * Global uncaught-exception handler. Writes a readable crash report to the app's
 * external files dir (so it can be pulled without root or shown in-app on the
 * next launch), then hands off to the previous handler so the system still does
 * its normal thing.
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private final Context ctx;
    private final Thread.UncaughtExceptionHandler previous;

    private CrashHandler(Context ctx, Thread.UncaughtExceptionHandler previous) {
        this.ctx = ctx;
        this.previous = previous;
    }

    public static void install(Context context) {
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        if (prev instanceof CrashHandler) return;
        Thread.setDefaultUncaughtExceptionHandler(
                new CrashHandler(context.getApplicationContext(), prev));
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            writeReport(t, e);
        } catch (Throwable ignored) {
            // Never let the crash handler itself crash the crash.
        }
        if (previous != null) {
            previous.uncaughtException(t, e);
        } else {
            Process.killProcess(Process.myPid());
            System.exit(10);
        }
    }

    private void writeReport(Thread t, Throwable e) {
        File dir = crashDir(ctx);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        pw.flush();

        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        StringBuilder sb = new StringBuilder();
        sb.append("Permafrost crash report\n");
        sb.append("Time:    ").append(when).append('\n');
        sb.append("App:     ").append(appVersion(ctx)).append('\n');
        sb.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Device:  ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("Thread:  ").append(t.getName()).append('\n');
        sb.append("\n").append(sw);

        File out = new File(dir, "crash-" + System.currentTimeMillis() + ".txt");
        try (FileWriter fw = new FileWriter(out)) {
            fw.write(sb.toString());
        } catch (Exception ignored) {
        }
    }

    private static File crashDir(Context ctx) {
        File ext = ctx.getExternalFilesDir(null);
        File base = ext != null ? ext : ctx.getFilesDir();
        return new File(base, "crashes");
    }

    private static String appVersion(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName + " (" + PackageInfoCompat.getLongVersionCode(pi) + ")";
        } catch (Exception e) {
            return "?";
        }
    }

    /** The most recent crash report file, or null if none. */
    public static File latest(Context ctx) {
        File[] files = crashDir(ctx).listFiles((d, n) -> n.startsWith("crash-") && n.endsWith(".txt"));
        if (files == null || files.length == 0) return null;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        return files[0];
    }

    /**
     * Read the newest report, delete all reports, and return the text — so it is
     * shown exactly once. Returns null if there was none.
     */
    public static String consumeLatest(Context ctx) {
        File f = latest(ctx);
        if (f == null) return null;
        String text = read(f);
        File[] files = crashDir(ctx).listFiles();
        if (files != null) {
            for (File file : files) //noinspection ResultOfMethodCallIgnored
                file.delete();
        }
        return text;
    }

    private static String read(File f) {
        try {
            byte[] data = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0, r;
                while (off < data.length && (r = in.read(data, off, data.length - off)) != -1) off += r;
            }
            return new String(data);
        } catch (Exception e) {
            return null;
        }
    }
}
