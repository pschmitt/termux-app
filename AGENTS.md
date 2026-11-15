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

Set `ANDROID_HOME` directly to that SDK directory; deriving it from an unset `ANDROID_SDK_ROOT` can leave the path empty and cause `SDK location not found` (and an invalid aapt2 override). A verified full build compiled the native NDK targets for arm64-v8a, armeabi-v7a, x86, and x86_64, and completed successfully. Do not treat Java-only tasks as proof the APK build works.

### NixOS NDK prebuilt executable failure

The NDK can fail to launch its prebuilt Linux `make` with `Could not start dynamically linked executable` / `NixOS cannot run dynamically linked executables intended for generic linux environments out of the box` (exit 127). Setting `NIX_LD` and `NIX_LD_LIBRARY_PATH` did not fix this in a reported attempt. A full build was later verified after patching the NDK's prebuilt ELF executables on a writable SDK copy from a Nix shell containing `glibc`, `zlib`, and `patchelf`. This is a worker-local workaround, not a repository/toolchain change:

- Resolve the loader's glibc output with `nix eval --raw nixpkgs#glibc.outPath`; do not search `/nix/store` for an unprefixed `glibc-*` name.
- Identify the NDK's `prebuilt/linux-x86_64` and LLVM's `toolchains/llvm/prebuilt/linux-x86_64` directories, plus the store paths for glibc, zlib, and the NDK-bundled Python library.
- For each regular executable ELF under both NDK directories, use `patchelf --set-interpreter "$GLIBC/lib/ld-linux-x86-64.so.2"` and set its RPATH to include `$GLIBC/lib`, `$ZLIB/lib`, the directory containing `libpython3.11.so.1.0`, `$LLVM/lib64`, `$LLVM/lib`, and `$NDK/prebuilt/linux-x86_64/lib`.
- Verify that all relevant executables, including `make`, `clang`, Python, and `ld.lld`, launch before rerunning Gradle. Missing `libz.so.1` and `libpython3.11.so.1.0` were encountered while iterating; resolving one executable alone was not enough.

The successful run used NDK 29.0.14206865, JDK 17, composed SDK platforms 34/35/36 and build-tools 34.0.0/35.0.0/36.0.0, then ran `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug -Pandroid.aapt2FromMavenOverride="$ANDROID_HOME/build-tools/36.0.0/aapt2"`. Unit tests, all four ABI native build tasks, and APK assembly succeeded. `:app:lintDebug` was separately reported to fail on existing lint findings; do not conflate that with the successful test/build tasks.
