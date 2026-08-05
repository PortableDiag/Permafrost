# Changelog

All notable changes to Permafrost are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Entries for 1.0 through 1.3 were reconstructed from the git history and the
release diffs; the file did not exist while those versions shipped.

## [Unreleased]

Nothing yet.

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

[Unreleased]: https://github.com/PortableDiag/Permafrost/compare/v1.3...HEAD
[1.3]: https://github.com/PortableDiag/Permafrost/releases/tag/v1.3
