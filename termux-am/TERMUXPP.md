# TermuxPP Android command helper

This fork defaults the Android caller identity to `dev.pschmitt.termux`.
The Java namespace remains `com.termux.termuxam`: the APK is loaded by
`app_process`, not installed as an Android companion app. The upstream
`@TERMUX_APP_PACKAGE@` build-time substitution remains supported.

With JDK 17 and Android SDK/build-tools 35, build on a remote worker. From the
monorepo root, use the integrated Gradle project:

```sh
./gradlew :termux-am:assembleRelease
```

The unsigned helper APK is at
`termux-am/app/build/outputs/apk/release/termux-am-release-unsigned.apk`. The
standalone helper checkout retains its own wrapper; there, use
`./gradlew :app:assembleRelease`.
Use the monorepo's [`scripts/package-helpers.py`](../scripts/package-helpers.py)
to package the helper with its launcher and the matching native API tools. See
[the bootstrap guide](../docs/en/aarch64-bootstrap.md).
The packager substitutes the runtime prefix and preserves Android 14+'s
read-only dex requirement. Do not install this helper APK with Android's package
installer.

After installing the device-mode debs inside TermuxPP, run
`apt-mark hold termux-am termux-api` to prevent incompatible stock replacements.
Real API battery status and cold-start broadcast tests passed with this helper.
