# Permafrost — freeze apps until you want them

Permafrost keeps chosen apps **dormant** so they can't run, phone-home, or sit in
the background — until *you* tap a frost icon to wake one. When you leave the
app, it re-freezes itself automatically. Built for the "apps you're forced to
install to use a service" problem.

Requires **root**.

## Two methods (global default + per-app override)

**Method A — Freeze** *(recommended)*
Disables the app via `pm disable-user`. Instant, keeps all data, reversible. The
app vanishes from the launcher; Permafrost puts a **frost icon** in its place.

**Method B — Ghost**
Backs up the APK(s) **and** data, then *uninstalls* the app entirely. On launch
it reinstalls and restores. Slower per launch and more fragile (data restore
fixes ownership + SELinux labels for the new uid), but the app is fully gone
from disk between uses. Advanced option.

Pick a default in **Settings**; override it per app on each app's detail screen.

## How a frost icon works

1. Tap the frosted home-screen icon (the original icon, cooled and snowflake-badged).
2. `ProxyActivity` thaws (or reinstalls) the target and launches it.
3. `WatcherService` polls the foreground app via **Usage Access**; once you leave
   the target for the configured delay, it re-freezes (or re-ghosts) it.

## Updating a frozen app

On the app's detail screen, **Unlock for update** thaws it and suppresses
auto-refreeze. Update it however you like (Play Store / sideload), then
**Re-freeze now**.

## Permissions

- **Root** (`su`) — the freeze/ghost primitives (`pm disable-user`, `pm enable`,
  `pm uninstall`, `pm install`, data `tar`).
- **Usage access** (`PACKAGE_USAGE_STATS`) — to notice when you leave an app.
- **Query all packages** — to list installable apps.
- **Foreground service** — the re-freeze watcher.

## Build

Requirements: JDK 17, Android SDK (platform 35, build-tools 34.0.0).

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

Signing is read from `keystore.properties` (self-signed `permafrost-release.jks`
included for sideload testing).

- `minSdk` 26, `targetSdk` 35, package `com.portablediag.permafrost`.
- No third-party runtime libraries beyond AndroidX + Material 3.

## Layout

```
core/    Root shell, Freezer (Method A), Ghost (Method B), Manager,
         WatcherService, ForegroundApps, IconFrost, Shortcuts, BootReceiver
model/   ManagedApp, Mode, Store (JSON in SharedPreferences)
ui/      MainActivity, AppPickerActivity, AppDetailActivity, SettingsActivity
ProxyActivity   the tap target behind every frost icon
```

## Troubleshooting

If the app ever crashes, a report is saved to
`Android/data/com.portablediag.permafrost/files/crashes/` and shown in a dialog
(with copy/share) the next time you open Permafrost.

## Caveats

- Root only. No device-owner / no-root path yet.
- Ghost mode reinstalls on every launch (slow); a few apps dislike restored data.
- Re-freeze has a ~1s detection lag by design (Usage Access polling).
