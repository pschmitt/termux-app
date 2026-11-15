# TermuxPP Android command helper

This fork defaults the Android caller identity to `dev.pschmitt.termux`.
The Java namespace remains `com.termux.termuxam`: the APK is loaded by
`app_process`, not installed as an Android companion app. The upstream
`@TERMUX_APP_PACKAGE@` build-time substitution remains supported.

With JDK 17 and Android SDK/build-tools 35, build on a remote worker:

```sh
./gradlew :app:assembleRelease
```

The unsigned helper APK is at
`app/build/outputs/apk/release/app-release-unsigned.apk`.
Use the TermuxPP core repository's `scripts/package-helpers.py` to package it
with its launcher and the matching native API tools. See
[the bootstrap guide](https://github.com/pschmitt/termuxpp-app/blob/main/docs/en/aarch64-bootstrap.md).
The packager substitutes the runtime prefix and preserves Android 14+'s
read-only dex requirement. Do not install this helper APK with Android's package
installer.

After installing the device-mode debs inside TermuxPP, run
`apt-mark hold termux-am termux-api` to prevent incompatible stock replacements.
Real API battery status and cold-start broadcast tests passed with this helper.
