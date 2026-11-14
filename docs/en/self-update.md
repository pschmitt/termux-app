---
page_ref: /docs/apps/termux/self-update.html
---

# Self-update over Shizuku

TermuxPP can replace itself with a newer build without opening a file manager: **Settings >
TermuxPP > Update**. Nothing is checked or downloaded in the background. Every step starts with a
tap, and the install itself asks for a confirmation first.

## Using it

1. Install and start [Shizuku](https://shizuku.rikka.app/) (wireless debugging or root) and, when
   the Update screen says so, tap the Shizuku line to allow TermuxPP.
2. Tap **Check for updates**. This contacts GitHub once.
3. If a newer build exists the status line says so. Tap **Update now**, confirm, and wait. The app
   restarts when the install finishes; open terminal sessions end then.

Without Shizuku (not installed, not running, permission missing or denied) the screen says exactly
which, the update button stays disabled and nothing is changed on the device.

## Channel and what "newer" means

There is one channel, **Latest**. It is the rolling prerelease named `latest` of
[pschmitt/termux-app](https://github.com/pschmitt/termux-app/releases/tag/latest), republished by
`.github/workflows/latest_release.yml` on every push to `main`. Its body names the full commit of
the build.

The version name and version code never change between these builds, so they cannot order them.
The updater compares commits instead: the build's own commit (`BuildConfig.GIT_REVISION`, written
by `app/build.gradle`) against the release commit, using GitHub's compare API.

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

- Metadata comes only from `api.github.com` and files only from the fixed release download URL of
  this repository, over HTTPS. Redirects are followed by hand and each hop must stay on `github.com`
  or `*.githubusercontent.com`, https and the default port. Metadata, `SHA256SUMS` and the APK are
  size-limited. Asset file names are matched exactly against names the app builds itself
  (`termux-app_<variant>-debug_<abi|universal>.apk`) and never used as a path or in a command.
- The APK must match its entry in the release's `SHA256SUMS` and the size the release lists.
- The archive must be this package (`dev.pschmitt.termux`), signed with the same certificate
  digests as the installed app, with a version code that is not lower than the installed one (an
  equal one is allowed because rolling builds share it).
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
- The feed only exists after the workflow has run once on `main`; before that the check reports an
  error.
- CI builds embed the upstream bootstrap archive unless the bootstrap inputs described in
  `docs/en/aarch64-bootstrap.md` are supplied to the workflow. An update does not re-extract the
  bootstrap of an existing installation, but a fresh install from the published APK would get the
  upstream one.
- Hidden installer APIs are reached by reflection and could break on a future Android version; the
  failure is then reported and nothing is installed.
- Only Shizuku is supported; there is no fallback to the system's install prompt.
- Shizuku needs Android 7 (API 24) or newer. On older systems the app still installs and runs, and
  the Update screen reports the updater as unavailable.
