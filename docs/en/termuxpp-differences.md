---
page_ref: /docs/apps/termux/termuxpp-differences.html
---

# TermuxPP and Upstream Termux

This is a behavior comparison between this TermuxPP tree and the current
[`termux/termux-app` upstream source](https://github.com/termux/termux-app/blob/master/app/build.gradle).
It records compatibility choices and intentional fork differences, not every
source-code change. Recheck both implementations when updating this page.

## Differences

| Area | Upstream Termux | TermuxPP | Compatibility or impact |
| --- | --- | --- | --- |
| Terminal rendering | Upstream currently lacks DECSET 2026 synchronized output; [PR #5441](https://github.com/termux/termux-app/pull/5441) proposes support. | TermuxPP supports mode queries and synchronized frames, coalesces screen notifications, and releases a stalled frame after 100 ms. Reset and process exit flush immediately. | Adapted from upstream PR #5441 to the fork's existing mode handling. Sources: [`TerminalEmulator`](../../terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java) and [`TerminalSession`](../../terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java). |
| Android identity | The core uses the `com.termux` application ID and shared user ID. | The core application ID and shared user ID are `dev.pschmitt.termux`; the Java namespace remains `com.termux`. Companion Android IDs use the `dev.pschmitt.termux.*` family. | Intentional fork identity. Android data, permissions, and signatures are distinct. Install TermuxPP companions signed with the same certificate as the core; do not mix APKs from the two projects. |
| `RUN_COMMAND` permission | The core declares `com.termux.permission.RUN_COMMAND`. | The core service requires `dev.pschmitt.termux.permission.RUN_COMMAND`. A `com.termux.permission.RUN_COMMAND` alias is also declared for Tasker/Locale hosts that only request the stock name. | Compatibility alias, with a co-install limitation: Android rejects the duplicate permission declaration if stock Termux and TermuxPP core are installed together. The alias does not replace the fork-specific service permission. |
| Prefix paths | The app data and prefix use the stock `com.termux` identity. | The prefix is under the TermuxPP app data directory. Fork shell environments preload `libtermux-pkg-redirect.so`, which remaps selected hard-coded stock app paths and `LD_PRELOAD` entries for upstream package binaries. | Compatibility layer for stock-path assumptions in packages. It does not change Android package identity, permissions, or API receiver identity. |
| Bootstrap archive | The app downloads the pinned upstream bootstrap archive for each supported ABI. | The same upstream archives remain the default. A build may supply a SHA-256-verified custom AArch64 archive; the override does not affect the other ABIs. | Opt-in TermuxPP build capability. Published APKs use the upstream archive unless the build explicitly supplies the custom inputs. See [Custom AArch64 Bootstrap](aarch64-bootstrap.md). |
| Termux:API | API commands address the upstream app and its socket. | The companion uses `dev.pschmitt.termux.api`; the native tools target that receiver, socket, and prefix. | Intentional runtime identity change. The matching companion and helper tools are required; stock `termux-api` tools are not interchangeable. |
| TermuxAm | The stock helper identifies its caller as `com.termux`. | The helper defaults its caller identity to `dev.pschmitt.termux`. Its Java namespace remains `com.termux.termuxam`, because the APK is loaded by `app_process`, not installed as a companion app. | Intentional runtime identity change. Filesystem path redirection cannot change the Android caller identity. |
| APT helper packages | The stock `termux-am` and `termux-api` packages address stock Termux identities. | TermuxPP helper builds retain the package names `termux-am` and `termux-api` so existing APT requirements resolve, while the helper contents target TermuxPP. | Package-name compatibility does not make the packages interchangeable. Install the fork-built packages and hold them against incompatible upstream upgrades. See [API helper tools](../../termux-api-package/TERMUXPP.md) and [TermuxAm](../../termux-am/TERMUXPP.md). |
| Kill intent | No equivalent; the app can only be exited through its notification or force-stopped. | `RunCommandService` accepts `dev.pschmitt.termux.KILL` (same `RUN_COMMAND` permission and `allow-external-apps` policy). It SIGKILLs all shells and then kills the app process. Boolean extra `dev.pschmitt.termux.KILL_RESTART` schedules a relaunch of the main activity through `AlarmManager`. Tasker hosts cannot hold the fork's `RUN_COMMAND` permission, so the TermuxPP:Tasker plug-in has a "Kill Termux" action (with a restart checkbox) that sends the kill from the plug-in's shared UID. | TermuxPP-only addition. The relaunch is best effort: it relied on the app's "display over other apps" grant on the test device, and Android may otherwise block the background activity start. Source: [`RunCommandService`](../../app/src/main/java/com/termux/app/RunCommandService.java), [`TermuxService#actionKill`](../../app/src/main/java/com/termux/app/TermuxService.java). |
| ADB shell | No equivalent TermuxPP endpoint. | Debugging settings explicitly enable app-private loopback enrollment (`8022`) and mTLS PTY/management (`8023`) endpoints. Token-authenticated enrollment requires on-device fingerprint-only approval of each concrete client certificate. Subsequent enrollment preserves the server identity. Both the GUI and authenticated CLI list and revoke individual certificate authorizations; disabling access deletes the token, allowlist, and device TLS material. | The host CLI supports `--mode auto|mtls|run-as`: auto prefers Android `run-as` for debuggable installations and selects mTLS only when Android reports the app is not debuggable. The opt-in mTLS endpoint works without SSH or `run-as`. The host verifies the server certificate and loopback IP identity. The device requires an exact approved leaf-certificate pin, certificate validity/clientAuth usage, and proof of key possession through TLS. The token is required only for enrollment; approved certificate sessions do not prompt for it. CA membership alone never authorizes a client. |
| Tasker and Locale | The plug-in protocol is the stock Termux Tasker/Locale protocol. | The TermuxPP plug-in keeps the protocol action names while using its fork-specific app identity and shared UID. | Protocol compatibility is retained. Existing Tasker tasks may still need to select the TermuxPP plug-in again because Android stores the plug-in package identity. See [Tasker integration](../../termux-tasker/TERMUXPP.md). |
| Updates | Upstream distributes its app through its own release channels. | Settings > Updates can check and install the TermuxPP core, API, and Tasker rolling `latest` builds through Shizuku, after an explicit user action. | TermuxPP-only updater and release feeds. It does not update absent apps or alter APT package updates. See [Self-update over Shizuku](self-update.md). |

## Experimental full-Nix session (not an APK default)

The optional [`nix-runtime` prototype](../../nix-runtime/README.md) provides an
isolated Linux/glibc Nix store and shell through a patched droid-nix launcher.
It is separate from native Android/Bionic nixpp bundles and does not change
normal shell startup, bootstrap selection, companion identities or permissions.
Its preparer supports an explicit guest username and `/home/<user>`; the session
launcher preserves the original `nix` default for existing runtime settings.
The optional [`login.sh`](../../nix-runtime/login.sh) selects the configured
home's Nix-profile Zsh. This is guest identity only, not a separate Android UID
or a change to upstream-compatible native recovery. An opt-in native
[Android bridge](../../nix-runtime/android-bridge.py), started before entering
the guest by [`start-android-bridge.sh`](../../nix-runtime/start-android-bridge.sh),
exposes only ping, broker status, filtered battery status and toast. It validates
same-UID Unix-socket peers and bounds requests/helper execution; it is not an
arbitrary native command service. A `bridge.enabled` marker opts `login.sh` into
starting it. `SESSION_TMP_DIR` can select a short native directory for guest
`/tmp`, avoiding Android-translated Unix-socket path limits. These additions do
not change Android permissions or enable the runtime in the APK by default.
It has been exercised on a 4 KiB-page AArch64 device, including dynamic Nix builds
and real terminal job control. It is not a security sandbox or production-qualified
replacement for the native environment; APK integration and automatic full-Nix
startup remain unimplemented. This is experimental fork tooling, not an upstream
Termux compatibility feature.

## Terminal More menu

Upstream uses Android's context menu for terminal actions. TermuxPP uses a
rounded bottom sheet with a compact session card, an Autofill / Open URL / Share
shortcut card, and grouped icon-led rows. Open URL is disabled when the terminal
contains no URLs. Autofill remains conditional on Android support. Keep screen on
has an On/Off state pill: it stays visible while On and moves under **More actions**
while Off. Reset and Kill are also under **More actions**.
Settings remains visible in its own group at the bottom. Tapping Kill retains its
confirmation, and holding it retains the immediate-kill action. Share and
Autofill choices retain their existing preview cards. The presentation is an
intentional fork difference; action dispatch and authorization are unchanged.

Sources: [upstream context menu](https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/app/TermuxActivity.java),
[`TerminalContextMenu`](../../app/src/main/java/com/termux/app/terminal/TerminalContextMenu.java),
and [`TermuxContextMenuSheet`](../../app/src/main/java/com/termux/app/terminal/TermuxContextMenuSheet.java).

## Using the ADB shell

The CLI supports three transports selected with `--mode`:

- `auto` (default): probe Android `run-as`, use it for a debuggable installation,
  otherwise use mTLS. Only an explicit "package not debuggable" response selects
  mTLS; ADB failures and shell-launch failures are reported without fallback.
- `run-as`: require a debuggable APK. No token, certificate enrollment, or enabled
  in-app ADB shell endpoint is needed. Android's authorized ADB connection and
  `run-as` checks control access. The CLI initializes HOME, PREFIX, PATH,
  LD_LIBRARY_PATH and fork prefix redirection before starting the selected login shell.
  The bootstrap must already be installed. Native libraries are resolved from
  the current APK location, not a path cached across app upgrades.
- `mtls`: explicitly use the authenticated endpoint, including on debug builds.

For example, `python3 scripts/termuxpp-adb-shell.py --serial SERIAL --mode run-as`.
Provisioning, listing authorizations, and revocation always use mTLS in auto mode;
combining these operations with explicit `--mode run-as` is an argument error.
Both shell transports require an interactive terminal. The `run-as` transport
preserves the remote exit status.

Both transports resolve the configured shell symlink in order:
`~/.config/termuxpp/shell`, `~/.config/termux/shell`, then `~/.termux/shell`.
Without a configured shell, they fall back to `$PREFIX/bin/bash`, then
`$PREFIX/bin/sh`. A broken or non-executable configured shell is an error, not
an excuse to silently start a different shell. Use `--shell zsh` (resolved under
`$PREFIX/bin`) or `--shell /absolute/path/to/shell` to override it for one session.
The executable receives login-shell startup, and `SHELL` names the resolved
executable. The option is not a shell command and cannot include arguments.
The mTLS override requires an updated app.

### mTLS enrollment and access

1. In TermuxPP, open **Settings > Debugging** and enable **ADB shell access**. Access is off by default. The app generates a random 256-bit token and shows it once; copy it without sharing it.
2. On the host, run `python3 scripts/termuxpp-adb-shell.py --serial SERIAL --provision --name WORKSTATION`. The helper prompts for the token without echoing it, creates a 90-day client certificate, and prints its SHA-256 fingerprint. Open the approval notification on the phone, compare that fingerprint, and authenticate using an enrolled fingerprint. PIN and face authentication are not accepted; unavailable authentication, cancellation, backgrounding, or a two-minute timeout denies enrollment. The first approved enrollment installs a loopback-only server identity, encrypting the server private key to a device-generated RSA provisioning key; later enrollments cannot replace it. The helper retains no server private key and stores client credentials in a mode-`0700` directory with mode-`0600` files.
3. Run `python3 scripts/termuxpp-adb-shell.py --serial SERIAL --mode mtls` for an interactive mTLS shell. The helper forwards the enrollment and TLS ports, verifies the pinned server certificate and `127.0.0.1` identity, and presents the approved client certificate without requesting the enrollment token. Token-free sessions require the updated app and CLI; existing approved certificates remain usable without re-enrollment. Older CLIs can still use the legacy token handshake with the updated app. A new CLI connecting to an old app reports that the app must be updated; it does not silently save or request the token. Neither token nor private-key material is placed in command arguments or output.
4. Review authorizations under **Settings > Debugging > ADB shell authorizations**, or run `python3 scripts/termuxpp-adb-shell.py --serial SERIAL --list-authorizations`. Revoke a client in the GUI or use `--revoke FULL_LOWERCASE_SHA256_FINGERPRINT` from an authorized CLI. Revocation disconnects affected active clients; GUI revocation conservatively disconnects all clients. Local credential files are not deleted by remote revocation. Turning access off stops both listeners, clears the allowlist and token, and deletes the device TLS material. Re-enabling requires fresh enrollment.

The host requires `adb`, Python 3, and OpenSSL. Credentials are stored separately for each ADB serial; use `--credential-dir` to select a directory explicitly when switching transports. The helper creates ephemeral local ADB forwards and removes only those it created. Certificates are generated locally; no private key is written to the repository. For certificate renewal, enroll into a new `--credential-dir` and then revoke the old fingerprint. Existing client credentials are never silently overwritten. At most 32 certificates are approved, with up to four concurrent TLS connections. Certificates enforce wall-clock validity; correct the host/device clock rather than disabling verification. The one-year server certificate currently requires disabling and re-enabling access, followed by re-enrollment, to renew it.

### Shell completions

The CLI ships Bash, Zsh, and Fish integrations in [`scripts/completions`](../../scripts/completions).
They complete options, transport values, connected/authorized ADB device serials,
and credential directories. They register both `termuxpp-adb-shell` and
`termuxpp-adb-shell.py` (invoke the executable directly, rather than through
`python3`, for command-name completion). Load them in an interactive shell:

```bash
# Bash
source <(python3 scripts/termuxpp-adb-shell.py --completions bash)
# Zsh, after compinit
source <(python3 scripts/termuxpp-adb-shell.py --completions zsh)
# Fish
python3 scripts/termuxpp-adb-shell.py --completions fish | source
```

Keep the `completions` directory alongside the Python script when installing it.
OpenSSL is needed only for mTLS enrollment, not for `run-as`.

**Validation status:** the run-as transport requires real-device acceptance testing;
mocked tests do not prove Android's native-library access or PTY behavior.
Real-device mTLS acceptance on a non-debuggable build passed on 2026-11-16:
incorrect-token rejection, fingerprint-approved enrollment, authorization listing,
a shell command over mTLS, revocation, and denial of the revoked client.
A successful APK build or mocked unit test alone does not prove those behaviors.
The checked-in development signing key is public and must not be treated as a
production signing identity.

## Source references

- ADB shell access: [`AdbShellService`](../../app/src/main/java/com/termux/app/AdbShellService.java), [`PtyProcess`](../../terminal-emulator/src/main/java/com/termux/terminal/PtyProcess.java), and [`termuxpp-adb-shell.py`](../../scripts/termuxpp-adb-shell.py).

- Core package identity, bootstrap selection, and optional archive verification: [`app/build.gradle`](../../app/build.gradle), [`app/src/main/AndroidManifest.xml`](../../app/src/main/AndroidManifest.xml), and [`docs/en/aarch64-bootstrap.md`](aarch64-bootstrap.md).
- Shell path redirection: [`TermuxShellEnvironment.java`](../../termux-shared/src/main/java/dev/pschmitt/termux/shared/termux/shell/command/environment/TermuxShellEnvironment.java) and [`termux-pkg-redirect.c`](../../app/src/main/cpp/termux-pkg-redirect.c).
- API and Tasker identities: [`termux-api/app/build.gradle`](../../termux-api/app/build.gradle), [`termux-api-package/TERMUXPP.md`](../../termux-api-package/TERMUXPP.md), and [`termux-tasker/TERMUXPP.md`](../../termux-tasker/TERMUXPP.md).
- TermuxAm caller identity and helper packaging: [`termux-am/TERMUXPP.md`](../../termux-am/TERMUXPP.md) and [`scripts/package-helpers.py`](../../scripts/package-helpers.py).
- Upstream identity reference: [`termux/termux-app` app build](https://github.com/termux/termux-app/blob/master/app/build.gradle) and [manifest](https://github.com/termux/termux-app/blob/master/app/src/main/AndroidManifest.xml).

## Properties settings and configuration filename

TermuxPP adds **Settings > TermuxPP > Properties**, with controls for every supported
app property: boolean choices, enumerated choices (including gesture and volume-key
actions and extra-key styles), and guided text dialogs for numeric values, colors,
paths and key sequences. Text dialogs explain formats, ranges and defaults, use
appropriate keyboards, and keep invalid input open for correction. Extra keys have
a full-width live keyboard preview using the terminal's own extra-key renderer,
including the configured key style, capitalization, active colors, corner radius
and row height. Tap a preview key to edit it without sending terminal input.
Compact row controls add, remove or reorder rows and keys; choose common keys or
literal text; and configure labels, macros and swipe-up actions without writing
JSON. An optional **Advanced JSON** editor preserves support for complex layouts. Existing custom key attributes are retained. Layout changes remain a draft
until **Save**, and **Cancel** leaves the file unchanged.

These controls edit the properties file itself, not a competing Android preference
store. Clear a text field or select **App default** to remove an override; the layout
editor has **Restore default layout** followed by **Save**. Settings such as
transcript size may require a new session or an app restart.

The canonical file is **`~/.config/termuxpp/termuxpp.properties`**. Reads use the
first existing file in this order, without merging:

1. `~/.config/termuxpp/termuxpp.properties`
2. `~/.config/termuxpp/termux.properties` (previous fork filename)
3. `~/.config/termux/termux.properties`
4. `~/.termux/termux.properties`

On the first GUI change, the selected legacy file is copied into the canonical
file before applying the change. The legacy original is retained. Comments and
unknown properties are preserved; updates use an atomic write. Once the canonical
file exists, it takes precedence even when an override is reset. Shell edits and
`termux-reload-settings` continue to work. The adapted first-run profile script
creates the new canonical filename only when none of the supported files exists.

This is an intentional fork difference. Upstream retains its `termux.properties`
filename and legacy configuration locations. Sources: [upstream constants](https://github.com/termux/termux-app/blob/master/termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java),
[fork constants](../../termux-shared/src/main/java/dev/pschmitt/termux/shared/termux/TermuxConstants.java),
[properties GUI](../../app/src/main/java/com/termux/app/fragments/settings/termux/PropertiesPreferencesFragment.java),
[file editor](../../app/src/main/java/com/termux/app/settings/TermuxPropertiesEditor.java),
and [helper adaptation rules](../../app/src/main/resources/termuxpp/helper-rewrites.tsv).

## Scope

A matching signing certificate and shared UID are required for TermuxPP's core
and companions. Stock Termux packages, APKs, and Android helpers remain a
separate family. The path redirect and unchanged Tasker protocol preserve
specific compatibility points; neither makes the two app identities
interchangeable.
