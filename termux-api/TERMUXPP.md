# TermuxPP:API

This fork targets `dev.pschmitt.termux` and installs as
`dev.pschmitt.termux.api`. The API Java namespace is also
`dev.pschmitt.termux.api`; shared library classes retain `dev.pschmitt.termux.shared`.

## Build

In this monorepo, build the API companion from the repository root. It uses the
root's TermuxPP shared Gradle projects rather than a nested `termux-app`
submodule:

```sh
./gradlew :termux-api:assembleDebug :termux-api:lintDebug
```

The original standalone companion checkout retains its own wrapper and pinned
`termux-app` submodule; build that checkout from its root after initializing
submodules. Do not substitute upstream Maven artifacts, whose paths and package
identities target stock Termux.

Run substantial builds on a remote build worker, not a workstation.

## Install and verify

All TermuxPP apps sharing `dev.pschmitt.termux` must use the same signing
certificate. The checked-in upstream test key is identical to the core's test
key and is **public and untrusted**, suitable only for development. Production
builds must use the same private signing key as the installed TermuxPP core.

Install alongside stock Termux:API, not over it. Install the command-line tools
from <https://github.com/pschmitt/termuxpp-api-package>; the stock `termux-api`
package targets the upstream app and is not interchangeable with this fork.
Grant Android permissions only as needed. Test `termux-battery-status`, clipboard
access, and a notification, including after force-stopping and reopening the app.
Verify that stock Termux and its API continue to operate independently.

The stock `termux-am` package is another compatibility dependency: its embedded
`FakeContext` identifies the caller as `com.termux`. Use the matching
[TermuxAm fork](https://github.com/pschmitt/TermuxAm), whose default caller is
`dev.pschmitt.termux`. Filesystem path rewriting alone cannot fix this identity.

The core repository's `scripts/package-helpers.py` packages both helpers as
normal `termux-am` and `termux-api` debs. Install the device-mode packages inside
TermuxPP, then run `apt-mark hold termux-am termux-api`. This satisfies nixpp's
APT package requirements without allowing stock upgrades to replace the fork
helpers. See the core's `docs/en/aarch64-bootstrap.md` for build and installation.

On-device smoke tests cover battery status with the API app already running,
battery status after force-stopping it (broadcast fallback), and toast delivery.

No permissions or shared-UID authorization checks have been relaxed.

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
  `latest` prerelease with one APK named `termux-api-app_debug.apk`, `SHA256SUMS` over it, and the full
  commit in the release notes. The feed exists once that workflow has run on `main`.

The shared user id and the signing certificate must match the core's: the core refuses
any APK that does not, and an update ends the processes of all three apps.
