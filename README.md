# PBar

PBar is an Android app for showing lightweight floating progress indicators over other apps. It is designed to surface progress from ongoing download notifications and provide a compact visual indicator for media playback notifications.

## Features

- Floating progress line or ring overlay.
- Reads progress exposed by ongoing notifications from other apps.
- Detects media notifications and shows a playback indicator when the source exposes that state.
- Adjustable overlay style and placement.
- Material 3 Expressive interface with Home, Appearance, and Updates sections.
- In-app update page for checking the latest GitHub release.
- Layout adapts to portrait and landscape.
- Respects Android navigation-bar and display cutout insets.

## Requirements

- Android 8.0 (API 26) or newer.
- Notification access to read progress/media notifications.
- Display-over-other-apps permission to show the floating indicator.

PBar only displays information that Android and source apps expose through notifications. It cannot infer exact download or song progress when the source app does not publish it.

## Build

Open the project in Android Studio or run Gradle from the project root. GitHub Actions builds and publishes APK releases from the main branch.

## Permissions and privacy

PBar needs notification access to read notification content and progress, and overlay permission to draw its indicator over other apps. Notification data is processed on-device for the indicator. The updater contacts the public GitHub Releases API for this repository.


## Release signing

Release APKs use the encrypted signing material in `signing/keys.tar.enc.b64`. Add the matching `SIGNING_PASSPHRASE` repository secret under **Settings → Secrets and variables → Actions** before running the release workflow. The private key is decrypted only in the temporary GitHub Actions runner during signing.
