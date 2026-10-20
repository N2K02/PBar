# PBar

PBar is an Android progress overlay inspired by the idea of a quick-glance status-bar indicator. It uses PBar's own implementation and UI to track supported downloads, media playback, and Google Clock timers.

## Features

- Progress line or circular ring overlay.
- Horizontal or vertical line placement, edge positioning, adjustable thickness, rounded ends, and opacity.
- Adjustable ring size, stroke, and camera-hole X/Y calibration.
- Automatic source notification colour, plus per-app colour presets.
- Per-app customisation for indicator shape, orientation, position, thickness, and ring calibration.
- Per-app allow/ignore controls.
- Download/transfer, media, and Google Clock timer tracking switches.
- Media-session playback position estimation where Android exposes duration and position.
- Fullscreen auto-hide using an optional accessibility helper.
- Optional lock-screen display, subject to Android/device restrictions.
- Material 3 Expressive interface with Home, Appearance, Apps, and Updates sections.
- In-app GitHub Releases updater.
- Portrait and landscape layouts; respects display cutouts and navigation insets.

## Requirements

- Android 8.0 (API 26) or newer.
- Notification access to read progress/media/timer notifications.
- Display-over-other-apps permission to show the indicator.
- Accessibility permission is optional and used for fullscreen window-boundary detection. PBar does not read or store on-screen text.

PBar can only show exact progress when Android or the source app exposes suitable progress, timer, or media-session metadata. If a source exposes no usable duration/position, PBar uses an animated indeterminate indicator instead.

## Build

Open the project in Android Studio or build with Gradle 9.6.0. GitHub Actions builds and publishes APK releases from the main branch.

## Permissions and privacy

Notification data is processed on-device for the indicator. The optional accessibility helper checks foreground window bounds and package names to hide the overlay in fullscreen mode; it does not collect screen text. The updater contacts the public GitHub Releases API and downloads the selected APK. Android verifies the package signature during installation.

## Release signing

Release APKs use the encrypted signing material in `signing/keys.tar.enc.b64`. Add the matching `SIGNING_PASSPHRASE` repository secret under **Settings → Secrets and variables → Actions** before running the release workflow. The private key is decrypted only in the temporary GitHub Actions runner during signing.
