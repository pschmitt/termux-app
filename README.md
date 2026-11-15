<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="128" alt="TermuxPP app icon" />
</p>
<h1 align="center">TermuxPP</h1>
<p align="center">
  An Android terminal and Linux environment forked from Termux, with compatible
  companion apps and fork-aware helpers.
</p>

## Features

- **Terminal and Linux environment** — Run command-line tools in an Android
  app sandbox using the Termux package ecosystem.
- **Companion apps** — Build Termux:API, Termux:Tasker, Termux:Boot,
  Termux:Styling, Termux:Widget, and Termux:Float in this repository.
- **Fork-aware helpers** — TermuxAm and the native API tools address TermuxPP's
  Android identities while retaining the APT names `termux-am` and `termux-api`.
- **Upstream package compatibility** — Fork shells translate selected stock
  Termux paths for unmodified package binaries; the app identity and API socket
  remain fork-specific.
- **AArch64 bootstrap** — Optionally build a checksum-verified archive with
  extra packages; upstream archives remain the default. See [Custom AArch64
  Bootstrap](docs/en/aarch64-bootstrap.md).
- **On-device updates** — Settings > Updates checks the core, API, and Tasker
  rolling feeds on request through Shizuku. See [Self-update over
  Shizuku](docs/en/self-update.md).

## Install

Install the core APK and any companion APKs from matching TermuxPP builds. They
must use the same signing certificate. Do not mix TermuxPP APKs or helper
packages with stock Termux; the app identities differ, and the Tasker permission
compatibility alias prevents co-installing the TermuxPP core with stock Termux.
See [Upstream and TermuxPP differences](docs/en/termuxpp-differences.md).

The current core release page is the [GitHub Releases][core-releases] page.
Companion project instructions and build sources are listed below.

## Quick start

After installing and opening TermuxPP, use its terminal to install a package:

```sh
pkg update                 # Refresh package metadata
pkg install git            # Install Git
git --version              # Check that Git is available
```

Continue with the [Termux package management guide][package-management].

## Documentation

| Topic | Description |
| --- | --- |
| [AArch64 bootstrap](docs/en/aarch64-bootstrap.md) | Build an optional custom bootstrap and package fork-aware Android helpers. |
| [Config directory](docs/en/config-dir.md) | Termux configuration directory behavior. |
| [Extra keys and colors](docs/en/extra-keys-colors.md) | Configure the extra-keys row and terminal colors. |
| [Self-update over Shizuku](docs/en/self-update.md) | Check and install core, API, and Tasker rolling builds. |
| [Tap gestures](docs/en/tap-gestures.md) | Terminal touch gesture behavior. |
| [Upstream and TermuxPP differences](docs/en/termuxpp-differences.md) | Compatibility points and intentional fork behavior. |
| [URLs and hyperlinks](docs/en/urls-and-hyperlinks.md) | URL detection and terminal hyperlinks. |
| [Termux:API README](termux-api/README.md) | API companion project and upstream usage documentation. |
| [Termux:API TermuxPP notes](termux-api/TERMUXPP.md) | Fork identity, build, signing, helper, and update details. |
| [Termux:Boot README](termux-boot/README.md) | Boot companion project documentation. |
| [Termux:Float README](termux-float/README.md) | Float companion project documentation. |
| [Termux:Styling README](termux-styling/README.md) | Styling companion project documentation. |
| [Termux:Tasker README](termux-tasker/README.md) | Tasker companion project and protocol documentation. |
| [Termux:Tasker TermuxPP notes](termux-tasker/TERMUXPP.md) | Fork identity, host permissions, tests, and update details. |
| [Termux:Widget README](termux-widget/README.md) | Widget companion project documentation. |
| [Termux API tools README](termux-api-package/README.md) | Native API command-line tools. |
| [Termux API tools TermuxPP notes](termux-api-package/TERMUXPP.md) | Fork receiver/socket, native build, and packaging notes. |
| [TermuxAm README](termux-am/README.md) | Android command helper project. |
| [TermuxAm TermuxPP notes](termux-am/TERMUXPP.md) | Caller identity and helper APK packaging notes. |

## License

The core is [GPL-3.0-only](LICENSE.md). See the linked library licenses for
third-party code and license exceptions.

[core-releases]: https://github.com/pschmitt/termuxpp-app/releases
[package-management]: https://github.com/termux/termux-packages/wiki/Package-Management
