package com.portablediag.permafrost.core;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Method B: back an app up (APKs + data), uninstall it, and later reinstall +
 * restore it on demand.
 *
 * <p>Backups live under {@code <filesDir>/ghost/<pkg>/} so they survive the
 * app being uninstalled. Data is captured with {@code tar} and re-applied with
 * an ownership + SELinux-context fixup after reinstall, since the package's uid
 * changes on every install.
 *
 * <p>This is inherently more fragile than Method A: reinstalling is slow and a
 * few apps refuse to run with restored data. Treat it as an advanced option.
 */
public class Ghost {

    private static File ghostRoot(Context ctx) {
        return new File(ctx.getFilesDir(), "ghost");
    }

    public static File backupDir(Context ctx, String pkg) {
        return new File(ghostRoot(ctx), pkg);
    }

    public static boolean hasBackup(Context ctx, String pkg) {
        File d = backupDir(ctx, pkg);
        File[] apks = d.listFiles((dir, name) -> name.endsWith(".apk"));
        return apks != null && apks.length > 0;
    }

    /**
     * Copy every APK (base + splits) and archive the data dirs. Returns null on
     * success, else an error message.
     */
    public static String backup(Context ctx, String pkg) {
        File dir = backupDir(ctx, pkg);
        String dq = shq(dir.getAbsolutePath());

        List<String> apkPaths = new ArrayList<>();
        for (String line : Root.lines("pm path " + pkg)) {
            if (line.startsWith("package:")) {
                apkPaths.add(line.substring("package:".length()));
            }
        }
        if (apkPaths.isEmpty()) {
            return "backup failed: could not locate APK for " + pkg;
        }

        List<String> cmds = new ArrayList<>();
        cmds.add("set -e");
        cmds.add("rm -rf " + dq);
        cmds.add("mkdir -p " + dq);
        for (String apk : apkPaths) {
            cmds.add("cp " + shq(apk) + " " + dq + "/");
        }
        // Credential-encrypted internal data (the important one).
        cmds.add("if [ -d /data/data/" + pkg + " ]; then "
                + "tar -cf " + dq + "/data.tar -C /data/data " + pkg + " || true; fi");
        // External app-private data / obb, if present.
        cmds.add("if [ -d /sdcard/Android/data/" + pkg + " ]; then "
                + "tar -cf " + dq + "/extdata.tar -C /sdcard/Android/data " + pkg + " || true; fi");
        cmds.add("if [ -d /sdcard/Android/obb/" + pkg + " ]; then "
                + "tar -cf " + dq + "/obb.tar -C /sdcard/Android/obb " + pkg + " || true; fi");
        cmds.add("chmod -R 700 " + dq);

        Root.Result r = Root.run(cmds.toArray(new String[0]));
        if (!r.ok()) {
            return "backup failed: " + r.combined();
        }
        return null;
    }

    /** Uninstall the app, keeping the backup on disk. Returns null on success. */
    public static String uninstall(String pkg) {
        Root.Result r = Root.run("pm uninstall " + pkg);
        if (r.ok() && r.combined().toLowerCase().contains("success")) {
            return null;
        }
        // pm uninstall prints "Success" to stdout; if it's already gone treat as ok.
        if (r.combined().toLowerCase().contains("unable to find")
                || r.combined().toLowerCase().contains("not installed")) {
            return null;
        }
        return "uninstall failed: " + r.combined();
    }

    /**
     * Reinstall from backup and restore data. Returns null on success, else an
     * error message.
     */
    public static String restore(Context ctx, String pkg) {
        File dir = backupDir(ctx, pkg);
        File[] apks = dir.listFiles((d, name) -> name.endsWith(".apk"));
        if (apks == null || apks.length == 0) {
            return "restore failed: no backup APKs for " + pkg;
        }
        String dq = shq(dir.getAbsolutePath());

        List<String> cmds = new ArrayList<>();
        cmds.add("set -e");

        if (apks.length == 1) {
            cmds.add("pm install -r -d -g " + shq(apks[0].getAbsolutePath()));
        } else {
            // Split APKs need an install session.
            cmds.add("SID=$(pm install-create -r -d -g | tr -dc '0-9')");
            int idx = 0;
            for (File apk : apks) {
                cmds.add("pm install-write $SID split" + (idx++) + " "
                        + shq(apk.getAbsolutePath()));
            }
            cmds.add("pm install-commit $SID");
        }

        // Restore data and fix ownership + SELinux label to the new uid.
        cmds.add("NEWUID=$(stat -c %u /data/data/" + pkg + " 2>/dev/null || echo '')");
        cmds.add("if [ -f " + dq + "/data.tar ] && [ -n \"$NEWUID\" ]; then "
                + "rm -rf /data/data/" + pkg + "/*; "
                + "tar -xf " + dq + "/data.tar -C /data/data; "
                + "chown -R $NEWUID:$NEWUID /data/data/" + pkg + "; "
                + "restorecon -R /data/data/" + pkg + " 2>/dev/null || true; fi");
        cmds.add("if [ -f " + dq + "/extdata.tar ]; then "
                + "mkdir -p /sdcard/Android/data; "
                + "tar -xf " + dq + "/extdata.tar -C /sdcard/Android/data || true; fi");
        cmds.add("if [ -f " + dq + "/obb.tar ]; then "
                + "mkdir -p /sdcard/Android/obb; "
                + "tar -xf " + dq + "/obb.tar -C /sdcard/Android/obb || true; fi");

        Root.Result r = Root.run(cmds.toArray(new String[0]));
        if (!r.ok() || !Freezer.isInstalled(ctx, pkg)) {
            return "restore failed: " + r.combined();
        }
        return null;
    }

    /** Delete a stored backup from disk. */
    public static void deleteBackup(Context ctx, String pkg) {
        Root.run("rm -rf " + shq(backupDir(ctx, pkg).getAbsolutePath()));
    }

    /** Single-quote a path for safe shell use. */
    private static String shq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
