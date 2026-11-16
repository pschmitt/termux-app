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
        platformVersions = [ "36" ];
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
        ":termux-api:assembleDebug"
        ":termux-tasker:assembleDebug"
        ":termux-boot:assembleDebug"
        ":termux-styling:assembleDebug"
        ":termux-widget:assembleDebug"
        ":termux-float:assembleDebug"
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
        outputs = [
          "out"
          "api"
          "tasker"
          "boot"
          "styling"
          "widget"
          "float"
        ];
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
          mkdir -p "$out" "$api" "$tasker" "$boot" "$styling" "$widget" "$float"
          cp app/build/outputs/apk/debug/termux-app_apt-android-7-debug_universal.apk "$out/termuxpp-debug.apk"
          cp termux-api/app/build/outputs/apk/debug/*.apk "$api/"
          cp termux-tasker/app/build/outputs/apk/debug/*.apk "$tasker/"
          cp termux-boot/app/build/outputs/apk/debug/*.apk "$boot/"
          cp termux-styling/app/build/outputs/apk/debug/*.apk "$styling/"
          cp termux-widget/app/build/outputs/apk/debug/*.apk "$widget/"
          cp termux-float/app/build/outputs/apk/debug/*.apk "$float/"
        '';
      });
      companionApk =
        name: source:
        pkgs.runCommand "termuxpp-${name}-debug-apk-${apk.version}" { } ''
          mkdir -p "$out"
          cp ${source}/*.apk "$out/"
        '';
    in
    {
      packages.${system} = {
        default = apk;
        inherit apk;
        api = companionApk "api" apk.api;
        tasker = companionApk "tasker" apk.tasker;
        boot = companionApk "boot" apk.boot;
        styling = companionApk "styling" apk.styling;
        widget = companionApk "widget" apk.widget;
        float = companionApk "float" apk.float;
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
