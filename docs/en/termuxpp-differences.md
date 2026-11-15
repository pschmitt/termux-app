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
| Tasker and Locale | The plug-in protocol is the stock Termux Tasker/Locale protocol. | The TermuxPP plug-in keeps the protocol action names while using its fork-specific app identity and shared UID. | Protocol compatibility is retained. Existing Tasker tasks may still need to select the TermuxPP plug-in again because Android stores the plug-in package identity. See [Tasker integration](../../termux-tasker/TERMUXPP.md). |
| Updates | Upstream distributes its app through its own release channels. | Settings > Updates can check and install the TermuxPP core, API, and Tasker rolling `latest` builds through Shizuku, after an explicit user action. | TermuxPP-only updater and release feeds. It does not update absent apps or alter APT package updates. See [Self-update over Shizuku](self-update.md). |

## Source references

- Core package identity, bootstrap selection, and optional archive verification: [`app/build.gradle`](../../app/build.gradle), [`app/src/main/AndroidManifest.xml`](../../app/src/main/AndroidManifest.xml), and [`docs/en/aarch64-bootstrap.md`](aarch64-bootstrap.md).
- Shell path redirection: [`TermuxShellEnvironment.java`](../../termux-shared/src/main/java/dev/pschmitt/termux/shared/termux/shell/command/environment/TermuxShellEnvironment.java) and [`termux-pkg-redirect.c`](../../app/src/main/cpp/termux-pkg-redirect.c).
- API and Tasker identities: [`termux-api/app/build.gradle`](../../termux-api/app/build.gradle), [`termux-api-package/TERMUXPP.md`](../../termux-api-package/TERMUXPP.md), and [`termux-tasker/TERMUXPP.md`](../../termux-tasker/TERMUXPP.md).
- TermuxAm caller identity and helper packaging: [`termux-am/TERMUXPP.md`](../../termux-am/TERMUXPP.md) and [`scripts/package-helpers.py`](../../scripts/package-helpers.py).
- Upstream identity reference: [`termux/termux-app` app build](https://github.com/termux/termux-app/blob/master/app/build.gradle) and [manifest](https://github.com/termux/termux-app/blob/master/app/src/main/AndroidManifest.xml).

## Scope

A matching signing certificate and shared UID are required for TermuxPP's core
and companions. Stock Termux packages, APKs, and Android helpers remain a
separate family. The path redirect and unchanged Tasker protocol preserve
specific compatibility points; neither makes the two app identities
interchangeable.
