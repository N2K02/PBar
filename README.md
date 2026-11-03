# PBar

PBar is an Android progress overlay inspired by the idea of a quick-glance status-bar indicator. It uses PBar's own implementation and UI to track supported downloads, media playback, and Google Clock timers.

## Features

- Progress-bar line overlay only; circular ring mode has been removed.
- Horizontal progress line in both portrait and landscape, with independent top/bottom positions and safe offsets below the status bar or above a visible navigation bar, plus adjustable thickness, rounded ends, and opacity.
- Automatic source notification colour, a custom global hex colour, and preset or custom hex colours per app.
- Per-app customisation for orientation, position, thickness, and colour.
- Per-app allow/ignore controls.
- Download/transfer, media, and Google Clock timer tracking switches.
- Media-session playback position estimation where Android exposes duration and position.
- Fullscreen auto-hide using an optional accessibility helper.
- Optional lock-screen display, subject to Android/device restrictions.
- Material 3 Expressive interface with Home, Appearance, Apps, and Updates sections, plus a Quit button that stops the overlay until PBar is opened again.
- In-app GitHub Releases updater.
- Portrait and landscape layouts; respects display cutouts and navigation insets.

## Requirements

- Android 8.0 (API 26) or newer.
- Notification access to read progress/media/timer notifications.
- Display-over-other-apps permission to show the indicator.
- A sticky foreground service keeps the user-enabled overlay process running and provides an ongoing notification. Notification access and display-over-other-apps permission are still required for the overlay.
- Accessibility permission is optional and used for fullscreen window-boundary detection. PBar does not read or store on-screen text.

PBar uses Android's active MediaSession data for playback progress and notification progress for downloads and timers. It can only show exact media progress when the source exposes usable duration and position metadata; otherwise it uses an animated indeterminate indicator. Media bars respect the configured app colour or the default/custom colour.

The floating overlay remains hosted by Android's notification-listener service, while a separate sticky foreground service improves process priority when the app is enabled. Android can restart that service after reclaiming the process; it rechecks the saved enabled and permission state before resuming. PBar does not request notification permission: on Android 13 and newer, the service can still run when notification permission is denied, though Android may hide its notification from the drawer and show the active service in system controls. If an aggressive battery manager still stops PBar, exempt it from battery optimisation in system settings.

## Build

Open the project in Android Studio or build with Gradle 9.6.0. GitHub Actions builds and publishes APK releases from the main branch.

## Permissions and privacy

Notification data is processed on-device for the indicator. The optional accessibility helper checks foreground window bounds and package names to hide the overlay in fullscreen mode; it does not collect screen text. The updater contacts the public GitHub Releases API and downloads the selected APK. Android verifies the package signature during installation.

## Release signing

Release APKs use the encrypted signing material in `signing/keys.tar.enc.b64`. Add the matching `SIGNING_PASSPHRASE` repository secret under **Settings → Secrets and variables → Actions** before running the release workflow. The private key is decrypted only in the temporary GitHub Actions runner during signing.
