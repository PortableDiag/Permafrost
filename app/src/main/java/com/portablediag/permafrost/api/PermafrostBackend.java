package com.portablediag.permafrost.api;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.pm.PackageInfoCompat;

import com.portablediag.permafrost.core.AppLock;
import com.portablediag.permafrost.core.CrashHandler;
import com.portablediag.permafrost.core.ForegroundApps;
import com.portablediag.permafrost.core.Freezer;
import com.portablediag.permafrost.core.IconFrost;
import com.portablediag.permafrost.core.InstalledApps;
import com.portablediag.permafrost.core.Manager;
import com.portablediag.permafrost.core.Root;
import com.portablediag.permafrost.core.Shortcuts;
import com.portablediag.permafrost.model.ManagedApp;
import com.portablediag.permafrost.model.Mode;
import com.portablediag.permafrost.model.Store;
import com.portablediag.permafrost.ui.Palette;
import com.portablediag.permafrost.ui.Themes;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The real {@link ApiBackend}: every call goes through the same {@link Manager}
 * code the buttons use.
 *
 * <p>Root operations from the API run one at a time. Two requests freezing and
 * waking the same app at once would race in {@code pm}, and nothing a caller
 * wants is faster in parallel.
 */
public class PermafrostBackend implements ApiBackend {

    private static final Object ROOT_OPS = new Object();

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    public PermafrostBackend(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    private Store store() {
        return Store.get(ctx);
    }

    @Override
    public String versionName() {
        PackageInfo pi = packageInfo();
        return pi == null ? "" : pi.versionName;
    }

    @Override
    public int versionCode() {
        PackageInfo pi = packageInfo();
        return pi == null ? 0 : (int) PackageInfoCompat.getLongVersionCode(pi);
    }

    private PackageInfo packageInfo() {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public int sdk() {
        return Build.VERSION.SDK_INT;
    }

    @Override
    public String selfPackage() {
        return ctx.getPackageName();
    }

    @Override
    public boolean rootAvailable() {
        return Root.isAvailable();
    }

    @Override
    public boolean hasUsageAccess() {
        return ForegroundApps.hasUsageAccess(ctx);
    }

    @Override
    public List<ManagedApp> managed() {
        return store().all();
    }

    @Override
    public ManagedApp managed(String pkg) {
        return store().get(pkg);
    }

    /** One {@code pm list packages -d} for the whole list, not one per frozen app. */
    @Override
    public Map<String, Boolean> dormantOnDevice(List<ManagedApp> apps) {
        Map<String, Boolean> out = new HashMap<>();
        if (apps.isEmpty() || !Root.isAvailable()) return out;
        Set<String> disabled = null;
        for (ManagedApp a : apps) {
            if (a.mode == Mode.GHOST) {
                out.put(a.packageName, !Freezer.isInstalled(ctx, a.packageName));
                continue;
            }
            if (disabled == null) {
                disabled = new HashSet<>();
                for (String line : Root.lines("pm list packages -d")) {
                    if (line.startsWith("package:")) disabled.add(line.substring(8));
                }
            }
            out.put(a.packageName, disabled.contains(a.packageName));
        }
        return out;
    }

    @Override
    public boolean isInstalled(String pkg) {
        return Freezer.isInstalled(ctx, pkg);
    }

    @Override
    public List<InstalledApp> installed() {
        List<InstalledApp> out = new ArrayList<>();
        for (InstalledApps.Entry e : InstalledApps.launchable(ctx)) {
            out.add(new InstalledApp(e.pkg, e.label));
        }
        return out;
    }

    @Override
    public String add(String pkg, Mode mode) {
        synchronized (ROOT_OPS) {
            return Manager.add(ctx, pkg, mode);
        }
    }

    @Override
    public String freeze(ManagedApp app) {
        synchronized (ROOT_OPS) {
            return Manager.makeDormant(ctx, app);
        }
    }

    @Override
    public String wake(ManagedApp app) {
        synchronized (ROOT_OPS) {
            return Manager.wake(ctx, app);
        }
    }

    @Override
    public String launch(ManagedApp app) {
        synchronized (ROOT_OPS) {
            return Manager.launch(ctx, app, true);
        }
    }

    @Override
    public String unlockForUpdate(ManagedApp app) {
        synchronized (ROOT_OPS) {
            return Manager.unlockForUpdate(ctx, app);
        }
    }

    @Override
    public String changeMode(ManagedApp app, Mode mode) {
        synchronized (ROOT_OPS) {
            return Manager.changeMode(ctx, app, mode);
        }
    }

    @Override
    public void unmanage(ManagedApp app) {
        synchronized (ROOT_OPS) {
            Manager.unmanage(ctx, app);
        }
    }

    @Override
    public boolean pinShortcut(ManagedApp app) {
        boolean ok;
        try {
            ok = Shortcuts.pin(ctx, app.packageName, app.label);
        } catch (Exception e) {
            // Some launchers throw rather than refuse when the caller is in the background.
            ok = false;
        }
        if (ok) {
            app.hasShortcut = true;
            store().put(app);
        }
        return ok;
    }

    @Override
    public byte[] frostIconPng(String pkg) {
        Bitmap b = IconFrost.loadCached(ctx, pkg);
        if (b == null) b = IconFrost.frostedIcon(ctx, pkg);
        if (b == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }

    @Override
    public String crashReport() {
        File f = CrashHandler.latest(ctx);
        if (f == null || !f.exists()) return null;
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- settings ----

    @Override
    public Mode defaultMode() {
        return store().defaultMode();
    }

    @Override
    public void setDefaultMode(Mode m) {
        store().setDefaultMode(m);
    }

    @Override
    public int refreezeDelaySec() {
        return store().refreezeDelaySec();
    }

    @Override
    public void setRefreezeDelaySec(int s) {
        store().setRefreezeDelaySec(s);
    }

    @Override
    public List<Object[]> palettes() {
        List<Object[]> out = new ArrayList<>();
        for (Palette p : Palette.values()) {
            out.add(new Object[]{p.id, p.label, p.followsMode, p.mono});
        }
        return out;
    }

    @Override
    public String palette() {
        return Themes.current(ctx).id;
    }

    @Override
    public String darkMode() {
        return store().darkMode();
    }

    @Override
    public void setTheme(String palette, String darkMode) {
        // Saved now so the response reads it back; applied on the main thread,
        // because changing the night mode recreates any open screen.
        if (palette != null) store().setPalette(palette);
        if (darkMode != null) store().setDarkMode(darkMode);
        main.post(() -> Themes.change(ctx, null, null));
    }

    @Override
    public boolean appLock() {
        return AppLock.isEnabled(ctx);
    }

    @Override
    public boolean lockFrostIcons() {
        return AppLock.locksFrostIcons(ctx);
    }

    @Override
    public String lockMethod() {
        switch (AppLock.available(ctx)) {
            case BIOMETRIC: return "biometric";
            case CREDENTIAL: return "credential";
            default: return "none";
        }
    }

    @Override
    public void setAppLock(boolean on) {
        store().setAppLockEnabled(on);
    }

    @Override
    public void setLockFrostIcons(boolean on) {
        store().setLockFrostIcons(on);
    }
}
