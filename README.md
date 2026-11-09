# HTML to APK

Turn a single HTML file into an installable Android app, entirely on your phone.

Pick an HTML file, name the app, optionally pick an icon, tap **Build APK**,
then save and install it. The apps it builds are packaged properly and signed
v1+v2+v3, so they install on Android 16.

## Features

- Converts a single self-contained HTML file into an APK
- Custom app icon picker (any image, scaled to every launcher density)
- App name and package name auto-fill from the file name
- Donations page with the author's PayPal QR code
- Converted apps respect the status bar and navigation bar (no content hidden
  under the clock or the menu bar)

## Signing

Each phone generates its own signing key the first time you build an app
(stored in the Android KeyStore, never leaves the device, never in this repo).
Apps built on the same phone share that phone's key, so they can update each
other. If you clear the app's data or switch phones, the key is gone — apps
built before that can no longer be updated in place and need to be
uninstalled first.

## Building

Requires JDK 17 and the Android SDK (API 36, build-tools 36).

```sh
# point Gradle at your Android SDK, e.g.:
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

./gradlew :app:assembleRelease
```

The `:template` module (the little WebView wrapper every converted app is
built from) is compiled from source and its APK is copied into the app's
assets automatically during the build. The release APK is unsigned — sign it
with your own key, or let F-Droid sign it.

## Project layout

- `app/` — the converter app (UI + conversion core)
- `template/` — the WebView wrapper used for converted apps
- `fastlane/metadata/` — F-Droid listing text and icon
- `fdroid/metadata/` — F-Droid build recipe (`com.livewire.converter.yml`)

## License

Copyright 2026 Elijah Wougamon. Licensed under the Apache License, Version 2.0.
See [LICENSE](LICENSE).
