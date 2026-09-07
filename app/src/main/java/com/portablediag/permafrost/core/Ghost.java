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
     * Where a backup is assembled before it is allowed to replace the live one.
     * A package name can never begin with a dot, so {@code .staging} cannot
     * collide with a real package's backup directory.
     */
    private static File stagingDir(Context ctx, String pkg) {
        return new File(new File(ghostRoot(ctx), ".staging"), pkg);
    }

    /**
     * Copy every APK (base + splits) and archive the data dirs. Returns null on
     * success, else an error message.
     *
     * <p>Called on <em>every</em> re-ghost, so it must never damage the backup it
     * is replacing. Everything is assembled in a staging directory and verified
     * complete before a single rename swaps it in; if any step fails, the
     * previous backup is still there, untouched, and the caller must refuse to
     * uninstall. An earlier version wrote straight into the live backup
     * directory after {@code rm -rf}-ing it, which left a window with no good
     * copy of an app that is about to be uninstalled.
     */
    public static String backup(Context ctx, String pkg) {
        File dir = backupDir(ctx, pkg);
        File stage = stagingDir(ctx, pkg);
        File prev = new File(stage.getParentFile(), pkg + ".prev");
        String dq = shq(dir.getAbsolutePath());
        String sq = shq(stage.getAbsolutePath());
        String pq = shq(prev.getAbsolutePath());

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
        cmds.add("rm -rf " + sq + " " + pq);
        cmds.add("mkdir -p " + sq);
        for (String apk : apkPaths) {
            cmds.add("cp " + shq(apk) + " " + sq + "/");
        }
        // Credential-encrypted internal data — the part that matters.
        //
        // Ask PackageManager where the data actually lives instead of assuming
        // /data/data/<pkg>: that path is a convention, and on at least one ROM it
        // was not visible from the app's own root shell even while the package was
        // installed, so the old `if [ -d ... ]` quietly produced no archive at all
        // and the uninstall went ahead anyway.
        //
        // If PackageManager reports a data dir, capturing it is MANDATORY. Any
        // failure aborts the whole backup, which stops the caller uninstalling.
        cmds.add("DD=$(dumpsys package " + pkg + " | sed -n 's/.*dataDir=//p' | head -1)");
        cmds.add("if [ -n \"$DD\" ]; then "
                + "tar -cf " + sq + "/data.tar -C \"$(dirname \"$DD\")\" \"$(basename \"$DD\")\" "
                + "|| { echo \"cannot archive data dir $DD\" >&2; exit 1; }; "
                + "[ -s " + sq + "/data.tar ] "
                + "|| { echo \"data archive for $DD came out empty\" >&2; exit 1; }; "
                + "fi");
        // External app-private data / obb. Best-effort: these live on shared
        // storage and survive an uninstall on most ROMs anyway.
        cmds.add("if [ -d /sdcard/Android/data/" + pkg + " ]; then "
                + "tar -cf " + sq + "/extdata.tar -C /sdcard/Android/data " + pkg + " || true; fi");
        cmds.add("if [ -d /sdcard/Android/obb/" + pkg + " ]; then "
                + "tar -cf " + sq + "/obb.tar -C /sdcard/Android/obb " + pkg + " || true; fi");
        // Prove the staged copy is complete BEFORE it may replace a good one.
        cmds.add("ls " + sq + "/*.apk > /dev/null 2>&1");
        cmds.add("chmod -R 700 " + sq);
        // Swap: two renames in the same directory. If the second somehow fails,
        // put the previous backup back rather than leaving nothing behind.
        cmds.add("if [ -d " + dq + " ]; then mv " + dq + " " + pq + "; fi");
        cmds.add("mv " + sq + " " + dq + " || (mv " + pq + " " + dq + " 2>/dev/null; exit 1)");
        cmds.add("rm -rf " + pq);

        Root.Result r = Root.run(cmds.toArray(new String[0]));
        if (!r.ok()) {
            // set -e aborted before the swap, so the previous backup still stands.
            Root.run("rm -rf " + sq);
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
        //
        // The data dir is not always visible the instant install-commit returns,
        // so poll briefly rather than stat once. A single stat that came back
        // empty used to skip the whole restore and still report success, handing
        // the user a factory-fresh app and calling it a win.
        cmds.add("DD=''; NEWUID=''");
        cmds.add("for i in 1 2 3 4 5 6 7 8 9 10; do "
                + "DD=$(dumpsys package " + pkg + " | sed -n 's/.*dataDir=//p' | head -1); "
                + "[ -n \"$DD\" ] && NEWUID=$(stat -c %u \"$DD\" 2>/dev/null || echo ''); "
                + "[ -n \"$NEWUID\" ] && break; sleep 0.2; done");
        cmds.add("if [ -f " + dq + "/data.tar ]; then "
                + "[ -n \"$NEWUID\" ] || { echo \"data dir never appeared for " + pkg + " (DD=$DD)\" >&2; exit 1; }; "
                + "rm -rf \"$DD\"/* \"$DD\"/.[!.]*; "
                + "tar -xf " + dq + "/data.tar -C \"$(dirname \"$DD\")\" "
                + "|| { echo \"data restore failed into $DD\" >&2; exit 1; }; "
                + "chown -R $NEWUID:$NEWUID \"$DD\"; "
                + "restorecon -R \"$DD\" 2>/dev/null || true; fi");
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
