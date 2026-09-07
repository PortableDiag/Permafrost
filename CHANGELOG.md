# Changelog

All notable changes to Permafrost are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Entries for 1.0 through 1.3 were reconstructed from the git history and the
release diffs; the file did not exist while those versions shipped.

## [1.5] — 2026-09-06

Ghost mode data safety. **If you use Ghost mode, this release matters.**

### Fixed

- **Ghost mode now re-snapshots the app every time it goes dormant.** It used to
  back up only the *first* time: everything the app wrote afterwards was thrown
  away by the very uninstall that followed, and the next wake silently restored
  the original snapshot. A ghosted messaging app forgot every message read; a
  ghosted account app forgot you had logged in. Nothing told you.
- **A failed backup can no longer destroy the backup it was replacing.** The new
  snapshot is assembled in a staging directory and checked complete before a
  single rename swaps it in. The previous version wrote straight into the live
  backup directory after deleting it, leaving a window in which an app that was
  about to be uninstalled had no good copy anywhere.
- **Permafrost will no longer uninstall an app whose data it could not capture.**
  The app's data directory is now located by asking the package manager rather
  than assuming `/data/data/<package>`, and if that directory exists but cannot
  be archived, the whole operation fails and the app is left installed and
  running. Previously the archive step could quietly produce nothing and the
  uninstall went ahead regardless — which is unrecoverable data loss.
- **Restoring no longer reports success when it restored nothing.** The data
  directory is not always visible the instant the reinstall commits, so it is now
  polled briefly instead of checked once; if a backup contains data that could
  not be put back, that is an error rather than a silent skip that left you with
  a factory-fresh app. Hidden files are also cleared before the restore, so stale
  dotfiles no longer survive and merge into the restored set.

### Known limitation

On at least one device, Permafrost's own root shell cannot read other apps'
internal data directories at all, even though an `adb` root shell on the same
device can. **Ghost mode cannot work there**, and as of this release it says so
by refusing, rather than uninstalling and losing the data. Freeze mode
(the default, and the recommended one) is unaffected.

## [1.4] — 2026-09-06

Tagged `v1.4`. Version bumped to `1.4` / versionCode 5.

The lock was exercised on an Android 15 / API 35 emulator: both the biometric
and the device-credential paths, the in-prompt PIN fallback, refusal, the
re-lock after backgrounding and after process death, and the frost-icon gate in
both positions.

A full freeze → wake → auto-refreeze cycle was then run on a rooted Android 15
handset: the frost-icon trampoline thawed the target (`enabled=3` → `1`),
launched it, and the watcher returned it to dormancy about four seconds after
the app was left. Ghost mode was not exercised and is unchanged by this
release.

### Added

- **Optional app lock.** *Settings → Security → Require unlock to open
  Permafrost*, off by default. When on, every Permafrost screen — the managed-app
  list, the picker, an app's detail screen and Settings — asks for a
  **biometric** if one is enrolled, and falls back to the **device PIN, pattern
  or password** otherwise. The switch stays disabled until the device has a
  screen lock at all.
- **Also lock frost icons**, a second switch under the same category. Off by
  default and inert unless the main lock is on. With it on, `ProxyActivity`
  challenges the user before waking the app, so a frozen app cannot be launched
  from its icon by someone holding the phone.
- While the lock is enabled the window is marked `FLAG_SECURE`, so the list of
  frozen apps does not appear in the recent-apps thumbnail.

### Notes

- **Frost icons stay one-tap by default.** Gating `ProxyActivity` puts a prompt
  in front of every wake, which is a real cost to the whole point of a frost
  icon — so it is opt-in rather than part of the main lock.
- `ProxyActivity` is now a `FragmentActivity` (it has to host the prompt). With
  the icon lock off it behaves exactly as before.
- The lock **fails open** if the device's screen lock is removed after the
  setting was enabled — Permafrost will not lock a user out of their own app.
- Re-locks once Permafrost has been off screen for more than two seconds; the
  grace window keeps a rotation or an activity handover from re-prompting.
- Cancelling the prompt finishes the whole task rather than just the top
  activity, so the screen underneath is never exposed.
- Adds one AndroidX dependency, `androidx.biometric:biometric:1.1.0`, and the
  `USE_BIOMETRIC` permission.

## [1.3] — 2026-07-08

Tagged `v1.3` and released 2026-07-17. The tag points at the last commit on
`main` at release time, two commits after the version bump itself — so the
shortcut-removal change below is part of 1.3 despite landing after the number
changed.

### Added

- **Search in the managed-app list.** A `SearchView` in the home screen's app
  bar filters live by app name or package.
- **Search in the freeze picker**, backed by a text field above the list. Ticked
  apps stay selected while the query changes, so a selection can be built up
  across several searches before freezing.

### Changed

- Removing an app from Permafrost now also **disables its frost shortcut**.
  Android does not let an app pull a pinned shortcut out of a launcher's grid,
  so the shortcut is disabled rather than deleted: the icon becomes greyed and
  non-launchable, most launchers then drop it on their own, and a stale tap
  shows a short "no longer managed" message instead of silently launching an app
  the user believes is still frozen. On API 30+ removal of the long-lived pin is
  also requested, for launchers that honour it.

## [1.2] — 2026-07-06

### Added

- **In-app crash reporter.** An uncaught-exception handler writes a readable
  report to `Android/data/com.portablediag.permafrost/files/crashes/` — the
  external files dir, so it can be pulled off the device without root — then
  chains to the previous handler so the system still does its normal thing. Each
  report records the time, app version, Android release and SDK level, device
  make and model, thread name and stack trace.
- The next launch shows the most recent report **once**, in a dialog with copy,
  share and dismiss actions, and clears the crash directory afterwards.

## [1.1] — 2026-07-06

### Fixed

- **Opening the app picker crashed the app.** `AppPickerActivity` was missing
  from the manifest, so launching it threw `ActivityNotFoundException`.
- **Bottom controls were hidden behind the Android 15 navigation bar.** Every
  activity root is now padded by the system-bar and display-cutout insets, so
  the picker's action button and the home screen's FAB are reachable again.

### Changed

- The root (`su`) prompt is now triggered when the main screen opens, rather
  than on the first freeze — so the superuser dialog appears at launch instead
  of interrupting an operation. The check runs off the UI thread and its result
  is cached.

## [1.0] — 2026-07-06

Initial release.

### Added

- **Two dormancy methods**, selectable as a global default and overridable per
  app:
  - **Freeze (Method A)** — disables the app with `pm disable-user --user 0`,
    falling back to `pm disable` on ROMs that need it. Instant, preserves all
    app data, reversible, and persists across reboots.
  - **Ghost (Method B)** — copies every APK (base and splits) and archives the
    app's internal data, external app-private data and OBB directories, then
    uninstalls the app entirely. On wake it reinstalls (through an install
    session when the app is split) and restores the data, fixing ownership and
    SELinux labels for the package's new uid.
- **Frost icons** — pinned launcher shortcuts whose icon is the app's own icon
  cooled toward blue, washed with a frost sheen and badged with a snowflake. The
  generated icon is cached to disk so it survives the target being uninstalled
  in Ghost mode.
- **`ProxyActivity`**, the transparent tap target behind every frost icon: it
  thaws or reinstalls the target, launches it, and hands off to the watcher.
- **Automatic re-freeze.** A foreground service polls the foreground app roughly
  once a second via Usage Access and returns the target to dormancy once the
  user has left it for a configurable delay.
- **Unlock for update** — thaws an app and suppresses automatic re-freezing so
  it can be updated from anywhere, with a **Re-freeze now** action to re-lock it.
- **Boot re-assert.** On `BOOT_COMPLETED`, dormancy is re-applied to every
  managed app that is not unlocked for updating.
- **Settings** — default method and re-freeze delay (0–15 seconds, default 2),
  plus live root and Usage Access status.
- Home screen listing managed apps with their method and current state, a
  permission banner when Usage Access is missing, and a per-app detail screen for
  method, dormancy, launching, update unlock, icon placement and removal.
- Removing an app restores it to a normal, usable state and deletes any Ghost
  backup.

`v1.3` is the only tag in the repository — 1.0, 1.1 and 1.2 were same-day
iterations that were never tagged individually, so there are no comparison links
for them.

[1.5]: https://github.com/PortableDiag/Permafrost/compare/v1.4...v1.5
[1.4]: https://github.com/PortableDiag/Permafrost/compare/v1.3...v1.4
[1.3]: https://github.com/PortableDiag/Permafrost/releases/tag/v1.3
