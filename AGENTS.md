# Agent Instructions

## Android builds on remote workers

Run Gradle inside the repository's Nix-provided Android SDK environment on the worker; do not invoke `./gradlew` outside that shell. A verified full debug build used JDK 17, Android platform 36, build-tools 35.0.0 and 36.0.0, and NDK 29.0.14206865. The SDK was composed with `androidenv.composeAndroidPackages` and its `androidsdk` output's `libexec/android-sdk` directory was used for both `ANDROID_HOME` and `ANDROID_SDK_ROOT`.

For AGP, pass `-Pandroid.aapt2FromMavenOverride="$ANDROID_HOME/build-tools/36.0.0/aapt2"` so it uses the SDK's Nix-provided `aapt2`. Example from the repository root, inside the worker's Nix shell:

```sh
export ANDROID_HOME=/nix/store/<composed-androidsdk-output>/libexec/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/36.0.0:$PATH"
./gradlew :app:testDebugUnitTest :app:assembleDebug \
  -Pandroid.aapt2FromMavenOverride="$ANDROID_HOME/build-tools/36.0.0/aapt2"
```

Set `ANDROID_HOME` directly to that SDK directory; deriving it from an unset `ANDROID_SDK_ROOT` can leave the path empty and cause `SDK location not found` (and an invalid aapt2 override). A verified full build compiled the native NDK targets and completed successfully with this SDK setup. Do not treat Java-only tasks as proof the APK build works. An NDK `make` executable failure has been observed on NixOS: `Could not start dynamically linked executable` followed by `NixOS cannot run dynamically linked executables intended for generic linux environments out of the box` (exit 127). Setting `NIX_LD` and `NIX_LD_LIBRARY_PATH` did not resolve it in that attempt. No workaround for this failure is verified; do not assume `NIX_LD` fixes it. Capture the exact failing command and ELF/interpreter details before changing the toolchain or environment.
