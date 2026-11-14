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

## Install and verify

Sign with the same certificate as the installed TermuxPP core. The checked-in
upstream test key matches the core's development test key but is **public and
untrusted**; production builds require the same private key as the core.

The plug-in appears separately from stock Termux in Tasker. Existing tasks
must reselect the TermuxPP plug-in: saved upstream package references are not
migrated automatically. Scripts live in TermuxPP's `~/.termux/tasker`.

Grant Tasker the fork's command execution permission when Android requests it.
Keep the existing command permission and external-execution policy checks;
configure `allow-external-apps` explicitly only when required by your workflow.
Test a foreground and background script, arguments, stdin, stdout, stderr,
exit status, and result delivery. Check that unauthorized callers remain denied
and that stock Termux tasks still target stock Termux independently.
