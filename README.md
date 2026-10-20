# PBar

PBar is an Android progress overlay inspired by the idea of a quick-glance status-bar indicator. It uses PBar's own implementation and UI to track supported downloads, media playback, and Google Clock timers.

## Features

- Progress-bar line overlay only; circular ring mode has been removed.
- Horizontal or vertical line placement, edge positioning, adjustable thickness, rounded ends, and opacity.
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
- No persistent PBar foreground-service notification; the overlay uses a regular started service, like NetSpeed.
- Accessibility permission is optional and used for fullscreen window-boundary detection. PBar does not read or store on-screen text.

PBar uses Android's active MediaSession data for playback progress and notification progress for downloads and timers. It can only show exact media progress when the source exposes usable duration and position metadata; otherwise it uses an animated indeterminate indicator. Media bars respect the configured app colour or the default/custom colour.

The floating overlay runs without a persistent foreground-service notification to keep the notification drawer clear. Android may stop regular background services under memory or battery pressure; if the overlay stops, reopen PBar or exempt it from battery optimisation in system settings. This is a deliberate trade-off compared with a foreground service.

## Build

Open the project in Android Studio or build with Gradle 9.6.0. GitHub Actions builds and publishes APK releases from the main branch.

## Permissions and privacy

Notification data is processed on-device for the indicator. The optional accessibility helper checks foreground window bounds and package names to hide the overlay in fullscreen mode; it does not collect screen text. The updater contacts the public GitHub Releases API and downloads the selected APK. Android verifies the package signature during installation.

## Release signing

Release APKs use the encrypted signing material in `signing/keys.tar.enc.b64`. Add the matching `SIGNING_PASSPHRASE` repository secret under **Settings → Secrets and variables → Actions** before running the release workflow. The private key is decrypted only in the temporary GitHub Actions runner during signing.
