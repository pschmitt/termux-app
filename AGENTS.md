# Agent Instructions

## Android builds on remote workers

Run Gradle on a remote worker inside the repository's Android dev shell (`nix develop`, also available as `nix develop .#android`); do not invoke `./gradlew` outside it. The shell provides JDK 17, Gradle, Android platforms 35/36, build-tools 35.0.0/36.0.0 and NDK 29.0.14206865, sets `ANDROID_HOME`/`ANDROID_SDK_ROOT`, and points AGP at the SDK's Nix-provided `aapt2` through `GRADLE_OPTS`. Example from the repository root on the worker:

```sh
nix develop --accept-flake-config -c ./gradlew --no-daemon \
  :termux-shared:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
```

A full debug build in this shell compiled the native NDK targets for arm64-v8a, armeabi-v7a, x86 and x86_64 without any NDK patching. Do not treat Java-only tasks as proof the APK build works.

### Keeping the TermuxPP comparison current

[`docs/en/termuxpp-differences.md`](docs/en/termuxpp-differences.md) is the maintained comparison between TermuxPP and upstream Termux. When a change affects runtime behavior, Android package identity, permissions, prefix/bootstrap handling, companion or helper integration, package names, or update feeds, verify the resulting behavior against the current source and upstream, then update the comparison in the same change. Keep compatibility behavior distinct from intentional fork differences and refresh source links when implementation moves.
