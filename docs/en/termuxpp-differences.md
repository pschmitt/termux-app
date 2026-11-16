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
| Android identity | The core uses the `com.termux` application ID and shared user ID. | The core application ID and shared user ID are `dev.pschmitt.termux`; the Java namespace remains `com.termux`. Companion Android IDs use the `dev.pschmitt.termux.*` family. | Intentional fork identity. Android data, permissions, and signatures are distinct. Install TermuxPP companions signed with the same certificate as the core; do not mix APKs from the two projects. |
| `RUN_COMMAND` permission | The core declares `com.termux.permission.RUN_COMMAND`. | The core service requires `dev.pschmitt.termux.permission.RUN_COMMAND`. A `com.termux.permission.RUN_COMMAND` alias is also declared for Tasker/Locale hosts that only request the stock name. | Compatibility alias, with a co-install limitation: Android rejects the duplicate permission declaration if stock Termux and TermuxPP core are installed together. The alias does not replace the fork-specific service permission. |
| Prefix paths | The app data and prefix use the stock `com.termux` identity. | The prefix is under the TermuxPP app data directory. Fork shell environments preload `libtermux-pkg-redirect.so`, which remaps selected hard-coded stock app paths and `LD_PRELOAD` entries for upstream package binaries. | Compatibility layer for stock-path assumptions in packages. It does not change Android package identity, permissions, or API receiver identity. |
| Bootstrap archive | The app downloads the pinned upstream bootstrap archive for each supported ABI. | The same upstream archives remain the default. A build may supply a SHA-256-verified custom AArch64 archive; the override does not affect the other ABIs. | Opt-in TermuxPP build capability. Published APKs use the upstream archive unless the build explicitly supplies the custom inputs. See [Custom AArch64 Bootstrap](aarch64-bootstrap.md). |
| Termux:API | API commands address the upstream app and its socket. | The companion uses `dev.pschmitt.termux.api`; the native tools target that receiver, socket, and prefix. | Intentional runtime identity change. The matching companion and helper tools are required; stock `termux-api` tools are not interchangeable. |
| TermuxAm | The stock helper identifies its caller as `com.termux`. | The helper defaults its caller identity to `dev.pschmitt.termux`. Its Java namespace remains `com.termux.termuxam`, because the APK is loaded by `app_process`, not installed as a companion app. | Intentional runtime identity change. Filesystem path redirection cannot change the Android caller identity. |
| APT helper packages | The stock `termux-am` and `termux-api` packages address stock Termux identities. | TermuxPP helper builds retain the package names `termux-am` and `termux-api` so existing APT requirements resolve, while the helper contents target TermuxPP. | Package-name compatibility does not make the packages interchangeable. Install the fork-built packages and hold them against incompatible upstream upgrades. See [API helper tools](../../termux-api-package/TERMUXPP.md) and [TermuxAm](../../termux-am/TERMUXPP.md). |
| Kill intent | No equivalent; the app can only be exited through its notification or force-stopped. | `RunCommandService` accepts `dev.pschmitt.termux.KILL` (same `RUN_COMMAND` permission and `allow-external-apps` policy). It SIGKILLs all shells and then kills the app process. Boolean extra `dev.pschmitt.termux.KILL_RESTART` schedules a relaunch of the main activity through `AlarmManager`. Tasker hosts cannot hold the fork's `RUN_COMMAND` permission, so the TermuxPP:Tasker plug-in has a "Kill Termux" action (with a restart checkbox) that sends the kill from the plug-in's shared UID. | TermuxPP-only addition. The relaunch is best effort: it relied on the app's "display over other apps" grant on the test device, and Android may otherwise block the background activity start. Source: [`RunCommandService`](../../app/src/main/java/com/termux/app/RunCommandService.java), [`TermuxService#actionKill`](../../app/src/main/java/com/termux/app/TermuxService.java). |
| Tasker and Locale | The plug-in protocol is the stock Termux Tasker/Locale protocol. | The TermuxPP plug-in keeps the protocol action names while using its fork-specific app identity and shared UID. | Protocol compatibility is retained. Existing Tasker tasks may still need to select the TermuxPP plug-in again because Android stores the plug-in package identity. See [Tasker integration](../../termux-tasker/TERMUXPP.md). |
| Updates | Upstream distributes its app through its own release channels. | Settings > Updates can check and install the TermuxPP core, API, and Tasker rolling `latest` builds through Shizuku, after an explicit user action. | TermuxPP-only updater and release feeds. It does not update absent apps or alter APT package updates. See [Self-update over Shizuku](self-update.md). |

## Source references

- Core package identity, bootstrap selection, and optional archive verification: [`app/build.gradle`](../../app/build.gradle), [`app/src/main/AndroidManifest.xml`](../../app/src/main/AndroidManifest.xml), and [`docs/en/aarch64-bootstrap.md`](aarch64-bootstrap.md).
- Shell path redirection: [`TermuxShellEnvironment.java`](../../termux-shared/src/main/java/dev/pschmitt/termux/shared/termux/shell/command/environment/TermuxShellEnvironment.java) and [`termux-pkg-redirect.c`](../../app/src/main/cpp/termux-pkg-redirect.c).
- API and Tasker identities: [`termux-api/app/build.gradle`](../../termux-api/app/build.gradle), [`termux-api-package/TERMUXPP.md`](../../termux-api-package/TERMUXPP.md), and [`termux-tasker/TERMUXPP.md`](../../termux-tasker/TERMUXPP.md).
- TermuxAm caller identity and helper packaging: [`termux-am/TERMUXPP.md`](../../termux-am/TERMUXPP.md) and [`scripts/package-helpers.py`](../../scripts/package-helpers.py).
- Upstream identity reference: [`termux/termux-app` app build](https://github.com/termux/termux-app/blob/master/app/build.gradle) and [manifest](https://github.com/termux/termux-app/blob/master/app/src/main/AndroidManifest.xml).

## Properties settings and configuration filename

TermuxPP adds **Settings > TermuxPP > Properties**, with controls for every supported
app property: boolean choices, enumerated choices (including gesture and volume-key
actions), and text dialogs for numeric values, extra-key layouts, colors, paths and
key sequences. These controls edit the properties file itself, not a competing
Android preference store. Clear a text field or select **App default** to remove
an override. Values use the existing properties syntax and runtime validation;
settings such as transcript size may require a new session or an app restart.

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
