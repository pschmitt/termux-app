# TermuxPP:Tasker

This fork targets `dev.pschmitt.termux` and installs as
`dev.pschmitt.termux.tasker`. The plug-in Java namespace is also
`dev.pschmitt.termux.tasker`; shared classes retain `com.termux.shared`.
The Locale/Tasker protocol action names remain unchanged.

## Build

Clone with `git clone --recurse-submodules`, or run
`git submodule update --init --recursive` after cloning. The `termux-app`
submodule pins the TermuxPP shared libraries, including its command service
identity and private filesystem paths. Do not use upstream shared artifacts.

With JDK 17, Android SDK 35, and the NDK version in `gradle.properties`:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Run substantial builds on a remote build worker, not a workstation.

`ExecutionIntegrationTest` exercises an ordered plug-in broadcast using a nested
Locale configuration bundle, runs a temporary script through the real core
service, and checks completion stdout, stderr, and exit status 7. Build with
`:app:assembleDebugAndroidTest`, install both APKs, then run:

```sh
adb -s SERIAL shell am instrument -w \
    dev.pschmitt.termux.tasker.test/androidx.test.runner.AndroidJUnitRunner
```

This test passed on a device with the matching core and signing certificate.
It does not modify existing Tasker profiles.

`ExecutionIntegrationTest` fires from the plug-in's own UID, which Android always allows,
so it cannot detect host discovery or permission problems. `PluginHostDiscoveryTest`
resolves the fire/edit components like a real host and checks that the installed Tasker
(`net.dinglisch.android.taskerm`, override with
`-e termuxpp.hostPackage PACKAGE`) holds the receiver's permission.

## Install and verify

Sign with the same certificate as the installed TermuxPP core. The checked-in
upstream test key matches the core's development test key but is **public and
untrusted**; production builds require the same private key as the core.

The plug-in appears separately from stock Termux in Tasker. Existing tasks
must reselect the TermuxPP plug-in: saved upstream package references are not
migrated automatically. Scripts live in TermuxPP's `~/.config/termuxpp/tasker`. A relative
`Executable` is looked up in `~/.config/termuxpp/tasker`, then
`~/.config/termux/tasker`, then `~/.termux/tasker`; the first directory that has
the file wins, and all three count as the scripts directory for the
`allow-external-apps` check.

Plug-in hosts such as Tasker only request the stock `com.termux.permission.RUN_COMMAND`
permission and cannot be granted `dev.pschmitt.termux.permission.RUN_COMMAND`. The
plug-in's fire receiver is therefore protected with the stock permission name, which
Tasker holds once you granted it for stock Termux (Android app info, permissions).
The stock Termux app (or any app declaring that permission) must stay installed: if
the permission is undeclared, Tasker reports "plugin error: missing, disabled, not
exported or no permission receiver, or too many receivers". The core's own
`RunCommandService` still requires the TermuxPP permission and is only reached from
the plug-in's shared UID.
Keep the existing command permission and external-execution policy checks;
configure `allow-external-apps` explicitly only when required by your workflow.
Test a foreground and background script, arguments, stdin, stdout, stderr,
exit status, and result delivery. Check that unauthorized callers remain denied
and that stock Termux tasks still target stock Termux independently.

## Updates

This app has no updater of its own and no Shizuku dependency. The core app updates
it from its Settings > Updates screen (see the core's `docs/en/self-update.md`), on
request only, and only when the app is already installed.

To make that possible this repository does two things:

- The manifest carries the meta-data `dev.pschmitt.termux.GIT_REVISION`, with the value
  `git-<commit>` of the build (`-dirty` is appended for uncommitted changes and
  `git-unknown` when the revision is not known). Builds from a tree without `.git`
  must set `TERMUX_GIT_REVISION` (or `-PgitRevision=`) to a hex commit. A dirty or
  unknown build is never offered an update.
- `.github/workflows/latest_release.yml` publishes each push to `main` as the rolling
  `latest` prerelease with one APK named `termux-tasker-app_debug.apk`, `SHA256SUMS` over it, and the full
  commit in the release notes. The feed exists once that workflow has run on `main`.

The shared user id and the signing certificate must match the core's: the core refuses
any APK that does not, and an update ends the processes of all three apps.
