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

> **Read this before using Ghost mode.**
>
> - **The backup is refreshed every time** the app goes dormant, as of 1.5.
>   Before that it was taken once and never again, so everything the app wrote
>   afterwards was silently discarded. If you used Ghost mode before 1.5, the
>   stored snapshot is whatever the app looked like the first time you froze it.
> - **Permafrost refuses to uninstall an app whose data it cannot archive.** If
>   Ghost mode reports a backup failure, that is the safety net working — the app
>   is left installed and running. On some devices Permafrost's root shell cannot
>   read other apps' data directories at all, and Ghost mode simply cannot work
>   there. Use Freeze mode.
> - **The backup is the only copy.** A ghosted app has been uninstalled, so its
>   APK and data exist nowhere but Permafrost's own private storage. Clearing
>   Permafrost's data, or uninstalling Permafrost, destroys every ghosted app
>   irrecoverably. Thaw your apps before removing Permafrost.
>
> Use **Freeze** unless you specifically need the app to be absent from disk.

Pick a default in **Settings**; override it per app on each app's detail screen.

Both the home list of managed apps and the picker (where you choose apps to
freeze) are **searchable** — filter live by app name or package. In the picker,
ticked apps stay selected while you search, so you can build a set across several
searches before freezing.

## How a frost icon works

1. Tap the frosted home-screen icon (the original icon, cooled and snowflake-badged).
2. `ProxyActivity` thaws (or reinstalls) the target and launches it.
3. `WatcherService` polls the foreground app via **Usage Access**; once you leave
   the target for the configured delay, it re-freezes (or re-ghosts) it.

Removing an app from Permafrost thaws it and disables its frost icon via
`Shortcuts.unpin`. Android won't let an app pull a pinned shortcut out of a
launcher's grid, so the icon is disabled rather than deleted — most launchers
then drop it on their own, and a stale tap shows a short "no longer managed"
message instead of launching the now-unmanaged app.

## Locking Permafrost

**Settings → Security → Require unlock to open Permafrost.** Off by default. With
it on, opening any Permafrost screen asks for your **fingerprint or face** if you
have one enrolled, and falls back to your **device PIN, pattern or password**
otherwise. The switch is greyed out until the device has a screen lock set.

- The lock covers the screens that can thaw, re-freeze, un-manage or list your
  apps — the home list, the picker, an app's detail screen and Settings.
- **Frost icons are not locked by default.** A frost icon is meant to be a
  one-tap wake, so out of the box the lock protects the management UI and not
  the launch. If you'd rather have both, turn on **Also lock frost icons** — a
  tap then asks to unlock before the app wakes, so a frozen app can't be
  launched by someone holding your phone. Also off by default, and it does
  nothing unless the main lock is on.
- Permafrost re-locks as soon as it has been off screen for a couple of seconds,
  and while it is locked its window is marked secure, so the managed-app list
  does not appear in the recent-apps thumbnail. Screenshots of Permafrost are
  blocked while the setting is on.
- Cancelling the prompt closes Permafrost rather than dropping you onto the
  screen underneath.
- If you later remove your screen lock entirely, Permafrost **opens without a
  prompt** rather than locking you out of your own app. Re-adding a screen lock
  restores the lock.

## Updating a frozen app

On the app's detail screen, **Unlock for update** thaws it and suppresses
auto-refreeze. Update it however you like (Play Store / sideload), then
**Re-freeze now**.

## Permissions

- **Root** (`su`) — the freeze/ghost primitives (`pm disable-user`, `pm enable`,
  `pm uninstall`, `pm install`, data `tar`).
- **Usage access** (`PACKAGE_USAGE_STATS`) — to notice when you leave an app.
  This is a *special* permission: it can't be requested with a normal dialog, so
  Permafrost shows a banner linking to the system settings screen. **Without it
  there is no automatic re-freeze** — apps still wake on tap, they just stay
  awake — so grant it if re-freezing appears to do nothing.
- **Biometric** (`USE_BIOMETRIC`) — only for the optional app lock above. Never
  requested unless you turn that setting on, and it guards nothing but
  Permafrost's own UI.
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

Signing is read from `keystore.properties`, pointing at a keystore in the project
root. **Neither is in this repository** — both are gitignored, and the release
`signingConfig` is only applied when `keystore.properties` exists, so a fresh
clone still builds; it just produces an unsigned release APK. Supply your own
keystore to sign.

- `minSdk` 26, `targetSdk` 35, package `com.portablediag.permafrost`.
- No third-party runtime libraries beyond AndroidX + Material 3
  (`androidx.biometric` backs the optional app lock).

## Layout

```
core/    Root shell, Freezer (Method A), Ghost (Method B), Manager,
         WatcherService, ForegroundApps, InstalledApps, IconFrost, Shortcuts,
         BootReceiver, CrashHandler, AppLock
model/   ManagedApp, Mode, Store (JSON in SharedPreferences)
ui/      LockedActivity (base), MainActivity, AppPickerActivity,
         AppDetailActivity, SettingsActivity, SettingsFragment,
         ManagedAppAdapter, PickerAdapter, SystemBars
ProxyActivity   the tap target behind every frost icon
App             installs the crash handler
```

The device is the source of truth for dormancy, not the stored flag: every
action re-checks the real state with `pm list packages -d` (Freeze) or a package
lookup (Ghost) before acting.

## Troubleshooting

If the app ever crashes, a report is saved to
`Android/data/com.portablediag.permafrost/files/crashes/` and shown in a dialog
(with copy/share) the next time you open Permafrost.

## Caveats

- Root only. No device-owner / no-root path yet.
- Ghost mode reinstalls on every launch (slow); a few apps dislike restored data.
  See the warning in the Ghost section above — it backs up once, and its backup
  is the app's only remaining copy.
- The app lock guards Permafrost's UI; frost icons stay one-tap unless you opt
  into **Also lock frost icons**. Either way the lock falls open if the device's
  screen lock is removed.
- Re-freeze has a ~1s detection lag by design (Usage Access polling), on top of
  the configured delay.
- Automatic re-freeze depends entirely on Usage Access; without it, apps wake and
  stay awake.
- No automated tests and no CI. Every root operation has to be verified on a real
  rooted device.
