---
page_ref: /docs/apps/termux/self-update.html
---

# Self-update over Shizuku

TermuxPP can replace itself and its six companion apps (Termux:API, Termux:Tasker, Float, Boot,
Styling and Widget) with newer builds without opening a file manager: **Settings > Updates**. The
companions have no updater of their own; this one screen manages all seven apps. Nothing is checked
or downloaded in the background. Every step starts with a tap, and every install asks for a
confirmation first.

## Using it

1. Install and start [Shizuku](https://shizuku.rikka.app/) (wireless debugging or root) and, when
   the Updates screen says so, tap the Shizuku line to allow TermuxPP.
2. Tap **Check for updates**. This contacts GitHub, once per installed app, and shows the installed
   commit and the result for TermuxPP and each installed companion app.
3. Tap an app that has an update, read the confirmation and confirm. Apps are updated one at a
   time: there is no "update all" and no promise that several apps are installed together.

Companions that are not installed show "Not installed"; the updater never installs an app that is
absent. Without Shizuku (not installed, not running, permission missing or denied) the screen says
exactly which, updates cannot be started and nothing is changed on the device.

**Replacing any one of the seven apps ends the processes of all of them**, because they share one
user id (`dev.pschmitt.termux`). Open terminal sessions and running Tasker actions stop. Settings,
data and saved Tasker profiles are untouched: nothing is cleared or downgraded.

## Channel and what "newer" means

There is one channel, **Latest**. All seven apps share the rolling `latest` prerelease in the
[TermuxPP monorepo](https://github.com/pschmitt/termuxpp/releases/tag/latest). Its
`.github/workflows/latest_release.yml` builds them from the same `main` commit and publishes one
release containing the five core APK variants, `termux-api-app_debug.apk`,
`termux-tasker-app_debug.apk`, `termux-float-app_debug.apk`, `termux-boot-app_debug.apk`,
`termux-styling-app_debug.apk`, `termux-widget-app_debug.apk`, and a `SHA256SUMS` file covering
every APK. The release body names the full commit of the build.

Companion version codes are fixed, and version names are not used to order rolling builds. The
updater compares commits instead: the installed app's own commit against the release commit, using
GitHub's compare API. For TermuxPP that is `BuildConfig.GIT_REVISION`, written by
`app/build.gradle`. Each companion declares its own commit in the manifest meta-data
`dev.pschmitt.termux.GIT_REVISION` (value `git-<commit>`), so the commit of a pinned shared Gradle
project is never mistaken for the app's identity.

Older TermuxPP builds still point add-on updates at their separate stable release repositories.
Those feeds are being retained for older clients; after TermuxPP itself updates, the new updater uses
the monorepo rolling feed for every companion.

| Result | Meaning |
| --- | --- |
| Same commit | Up to date. |
| `latest` is ahead of the install | Update available. |
| Install is at or ahead of `latest` | Up to date. Nothing is ever offered that would go back. |
| Histories diverged, GitHub does not know the install's commit, or the install has no clean commit (local build with uncommitted changes, no git) | Not eligible. No update is offered. |
| Network or API error | The check failed; the status shows why. |

Because only the commit decides, a build you made yourself from a pushed commit is eligible. One
made from a dirty tree is not, by design.

## What is checked before anything is installed

- The apps and repository are a fixed allowlist in `UpdateTarget`: TermuxPP and all six companions
  use `pschmitt/termuxpp`, with distinct fixed package IDs and APK filenames.
  Nothing in a release or in an installed app can add a target or change which repository or package
  a target means; one target can never install another's APK.
- Metadata comes only from `api.github.com` and files only from the fixed release download URL of
  the target's repository, over HTTPS. Redirects are followed by hand and each hop must stay on `github.com`
  or `*.githubusercontent.com`, https and the default port. Metadata, `SHA256SUMS` and the APK are
  size-limited. Asset file names are matched exactly against names the app builds itself
  (`termux-app_<variant>-debug_<abi|universal>.apk` for the core, the fixed names above for the
  companions) and never used as a path or in a command.
- The APK must match its entry in the release's `SHA256SUMS` and the size the release lists.
- The archive must be the target's package, declare the shared user id `dev.pschmitt.termux`, be
  signed with the same certificate digests as both the installed copy and TermuxPP itself, and have
  a version code that is not lower than the installed one (an equal one is allowed because rolling
  builds share it).
- Android's package installer enforces the signing rule again.
- The update runs as a session of the Shizuku (shell) user with a normal replace; no verifier or
  other protection is disabled. The downloaded file stays in the app's private cache and is deleted
  after the install and after every failure.

## Limits

- The checksums sit in the same release as the APK, so they protect against corruption and
  truncation, not against someone who can publish to the release. The trust anchor is HTTPS to
  GitHub and write access to the repository.
- The repository's development key (`testkey_untrusted.jks`) is public, so every debug build shares
  one signing identity. Matching certificates here mean "same key as the installed app", not "built
  by the maintainer". Treat the published APKs as exactly as trustworthy as the repository.
- The rolling feed contains all apps after the release workflow has run once on `main`. If a
  companion has no comparable commit metadata, the updater can still offer its fixed APK as an
  explicit reinstall; the normal checksum, package, shared-UID, signer and installability checks
  still apply. Current workflow builds embed each companion's own commit so later checks can compare
  revisions.
- CI builds embed the upstream bootstrap archive unless the bootstrap inputs described in
  `docs/en/aarch64-bootstrap.md` are supplied to the workflow. An update does not re-extract the
  bootstrap of an existing installation, but a fresh install from the published APK would get the
  upstream one.
- Hidden installer APIs are reached by reflection and could break on a future Android version; the
  failure is then reported and nothing is installed.
- Only Shizuku is supported; there is no fallback to the system's install prompt.
- Shizuku needs Android 7 (API 24) or newer. On older systems the app still installs and runs, and
  the Updates screen reports the updater as unavailable.
