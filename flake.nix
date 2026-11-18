{
  description = "TermuxPP reproducible Android development environment";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { nixpkgs, ... }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };
      android = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [
          "35"
          "36"
        ];
        buildToolsVersions = [
          "35.0.0"
          "36.0.0"
        ];
        includeNDK = true;
        ndkVersions = [ "29.0.14206865" ];
        includeEmulator = false;
        includeSystemImages = false;
      };
      sdk = "${android.androidsdk}/libexec/android-sdk";
      gradle = pkgs.gradle_8;
      java = pkgs.jdk17_headless;
      bootstrapVersion = "2026.02.12-r1%2Bapt.android-7";
      gradleTasks = [
        ":app:assembleDebug"
      ];
      bootstraps = {
        aarch64 = pkgs.fetchurl {
          url = "https://github.com/termux/termux-packages/releases/download/bootstrap-${bootstrapVersion}/bootstrap-aarch64.zip";
          hash = "sha256-6irrqIGeUX23EfjDI2nonnxSzuc+B5MP+RGF4auT9PM=";
        };
        arm = pkgs.fetchurl {
          url = "https://github.com/termux/termux-packages/releases/download/bootstrap-${bootstrapVersion}/bootstrap-arm.zip";
          hash = "sha256-o49NOy9zX4O+K/VO/0Y+htwyo+L5+GHBVXxDeNJJwBg=";
        };
        i686 = pkgs.fetchurl {
          url = "https://github.com/termux/termux-packages/releases/download/bootstrap-${bootstrapVersion}/bootstrap-i686.zip";
          hash = "sha256-9bwLAlufO0ILX8ru/AZPiI9fIqDW/XCQ9KrAwz6zVVs=";
        };
        x86_64 = pkgs.fetchurl {
          url = "https://github.com/termux/termux-packages/releases/download/bootstrap-${bootstrapVersion}/bootstrap-x86_64.zip";
          hash = "sha256-t/0PLjpN5TS+MUT5+RrMdoYw/EY+rxNKsuZMVF6DT3o=";
        };
      };
      apk = pkgs.stdenvNoCC.mkDerivation (finalAttrs: {
        pname = "termuxpp-debug-apk";
        version = "0.118.0";
        outputs = [ "out" ];
        src = ./.;
        nativeBuildInputs = [
          gradle
          java
          pkgs.patchelf
        ];
        ANDROID_HOME = sdk;
        ANDROID_SDK_ROOT = sdk;
        ANDROID_USER_HOME = "/tmp/termuxpp-android-user";
        JAVA_HOME = java.home;
        TERMUX_GIT_REVISION = "0000000";
        TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS = "0";
        TERMUX_APK_VERSION_TAG = "apt-android-7-debug";
        SOURCE_DATE_EPOCH = "1";
        LANG = "C.UTF-8";
        LC_ALL = "C.UTF-8";
        TZ = "UTC";

        mitmCache = gradle.fetchDeps {
          pkg = finalAttrs.finalPackage;
          data = ./nix/deps.json;
          bwrapFlags = "--dir /bin --symlink ${pkgs.bash}/bin/bash /bin/sh";
        };
        gradleBuildTask = pkgs.lib.concatStringsSep " " gradleTasks;
        gradleUpdateTask = pkgs.lib.concatStringsSep " " gradleTasks;
        preGradleUpdate = ''
          gradleFlagsArray+=(--write-verification-metadata sha256)
        '';
        gradleFlags = [
          "--no-daemon"
          "-Pandroid.aapt2FromMavenOverride=${sdk}/build-tools/36.0.0/aapt2"
        ];

        preBuild = ''
          mkdir -p "$ANDROID_USER_HOME"
          install -Dm644 ${bootstraps.aarch64} app/src/main/cpp/bootstrap-aarch64.zip
          install -Dm644 ${bootstraps.arm} app/src/main/cpp/bootstrap-arm.zip
          install -Dm644 ${bootstraps.i686} app/src/main/cpp/bootstrap-i686.zip
          install -Dm644 ${bootstraps.x86_64} app/src/main/cpp/bootstrap-x86_64.zip
        '';
        installPhase = ''
          mkdir -p "$out"
          cp app/build/outputs/apk/debug/termux-app_apt-android-7-debug_universal.apk "$out/termuxpp-debug.apk"
        '';
      });
      termuxAm = pkgs.stdenvNoCC.mkDerivation (finalAttrs: {
        pname = "termuxpp-termux-am-helper";
        version = "0.8.1";
        src = ./.;
        nativeBuildInputs = [
          gradle
          java
        ];
        ANDROID_HOME = sdk;
        ANDROID_SDK_ROOT = sdk;
        ANDROID_USER_HOME = "/tmp/termuxpp-android-user";
        JAVA_HOME = java.home;
        LANG = "C.UTF-8";
        LC_ALL = "C.UTF-8";
        TZ = "UTC";

        mitmCache = gradle.fetchDeps {
          pkg = finalAttrs.finalPackage;
          data = ./nix/deps.json;
          bwrapFlags = "--dir /bin --symlink ${pkgs.bash}/bin/bash /bin/sh";
        };
        gradleBuildTask = ":termux-am:assembleRelease -x :termux-am:lintVitalAnalyzeRelease -x :termux-am:lintVitalReportRelease -x :termux-am:lintVitalRelease";
        gradleUpdateTask = ":termux-am:assembleRelease -x :termux-am:lintVitalAnalyzeRelease -x :termux-am:lintVitalReportRelease -x :termux-am:lintVitalRelease";
        gradleFlags = [
          "--no-daemon"
          "-Pandroid.aapt2FromMavenOverride=${sdk}/build-tools/35.0.0/aapt2"
        ];
        preBuild = ''
          mkdir -p "$ANDROID_USER_HOME"
        '';
        installPhase = ''
          mkdir -p "$out"
          cp termux-am/app/build/outputs/apk/release/termux-am-release-unsigned.apk \
            "$out/termux-am-release-unsigned.apk"
        '';
      });
      termuxApiNative = pkgs.stdenv.mkDerivation {
        pname = "termuxpp-termux-api-native-aarch64";
        version = "0.60.0";
        src = ./termux-api-package;
        nativeBuildInputs = [
          pkgs.cmake
          pkgs.ninja
        ];
        cmakeFlags = [
          "-DCMAKE_TOOLCHAIN_FILE=${sdk}/ndk/29.0.14206865/build/cmake/android.toolchain.cmake"
          "-DANDROID_ABI=arm64-v8a"
          "-DANDROID_PLATFORM=android-24"
          "-DCMAKE_INSTALL_PREFIX=/data/data/dev.pschmitt.termux/files/usr"
          "-DCMAKE_POLICY_VERSION_MINIMUM=3.5"
        ];
        installPhase = ''
          DESTDIR="$out" cmake --install .
        '';
      };
      helperDebs =
        name: bootstrap:
        pkgs.runCommand name
          {
            nativeBuildInputs = [ pkgs.python3 ];
          }
          ''
            mkdir -p "$out"
            ${pkgs.python3}/bin/python3 ${./scripts/package-helpers.py} \
              --am-source ${./termux-am} \
              --am-apk ${termuxAm}/termux-am-release-unsigned.apk \
              --api-prefix ${termuxApiNative}/data/data/dev.pschmitt.termux/files/usr \
              --out-dir "$out" ${pkgs.lib.optionalString bootstrap "--bootstrap"}
          '';
      helperDeb =
        name: source: filename:
        pkgs.runCommand name { } ''
          mkdir -p "$out"
          cp ${source}/${filename} "$out/"
        '';
      deviceHelperDebs = helperDebs "termuxpp-helper-debs-device-aarch64" false;
      bootstrapHelperDebs = helperDebs "termuxpp-helper-debs-bootstrap-aarch64" true;
    in
    {
      packages.${system} = {
        default = apk;
        inherit apk;
        termux-am-device =
          helperDeb "termuxpp-termux-am-device-deb-aarch64" deviceHelperDebs
            "termux-am_0.8.1+termuxpp1_all.deb";
        termux-api-device =
          helperDeb "termuxpp-termux-api-device-deb-aarch64" deviceHelperDebs
            "termux-api_0.60.0+termuxpp1_aarch64.deb";
        termux-am-bootstrap =
          helperDeb "termuxpp-termux-am-bootstrap-deb-aarch64" bootstrapHelperDebs
            "termux-am_0.8.1+termuxpp1_all.deb";
        termux-api-bootstrap =
          helperDeb "termuxpp-termux-api-bootstrap-deb-aarch64" bootstrapHelperDebs
            "termux-api_0.60.0+termuxpp1_aarch64.deb";
      };
      apps.${system}.update-deps = {
        type = "app";
        program = "${apk.mitmCache.updateScript}";
      };
      devShells.${system}.default = pkgs.mkShell {
        packages = [
          gradle
          java
          android.androidsdk
          android.platform-tools
          pkgs.git
          pkgs.patchelf
        ];
        ANDROID_HOME = sdk;
        ANDROID_SDK_ROOT = sdk;
        JAVA_HOME = java.home;
        TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS = "0";
        TERMUX_APK_VERSION_TAG = "apt-android-7-debug";
        shellHook = ''
          export PATH="${sdk}/platform-tools:${sdk}/build-tools/36.0.0:$PATH"
          export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/36.0.0/aapt2''${GRADLE_OPTS:+ $GRADLE_OPTS}"
        '';
      };
    };
}
