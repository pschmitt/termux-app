# Config directory

TermuxPP's canonical user configuration directory is `~/.config/termuxpp`. New and migrated files
should use this directory; legacy locations are read only as compatibility fallbacks:

| File or directory | Purpose |
| --- | --- |
| `termux.properties`, `termux.float.properties` | settings |
| `colors.properties` | terminal colors |
| `font.ttf`, `font-italic.ttf`, `font-bold.ttf`, `font-bold-italic.ttf` | terminal fonts |
| `tasker/` | scripts run by the TermuxPP Tasker plug-in |
| `boot/` | boot scripts |

For compatibility with an existing Termux setup, each file is looked up in these directories, in
this order, and the first one that has it wins:

1. `~/.config/termuxpp`
2. `~/.config/termux`
3. `~/.termux`

The lookup is per file, so a partly migrated setup works: a font that exists only in `~/.termux`
is still used while `termux.properties` is read from `~/.config/termuxpp`. Symlinked fonts and
scripts are followed, except for the properties files, which must be regular files. Relative
Tasker executables use the same order, and a script in any of the three `tasker/` directories
counts as a scripts directory script for the `allow-external-apps` check.

Tasker profiles that still hold an absolute path such as `~/.termux/tasker/job.sh` keep working:
if that file no longer exists but the script is in another `tasker/` directory, the script found
there is run.

The `login` shell and the message of the day follow the same order: `login` takes `shell` and
`motd.sh` from the first of the three directories that has them (the TermuxPP version of the
`termux-tools` scripts, re-adapted by the package hook after every apt run). `termux-reload-settings`
names `~/.config/termuxpp` in its help text. Stock Termux is not affected, its scripts are never
touched. The hook is also refreshed and run by the app on every start, so a new TermuxPP version
brings its rules to an existing install without reinstalling the bootstrap.

The stock `$PREFIX/etc/motd` (welcome text, `termux.dev` links) is replaced by a TermuxPP one: it
welcomes to TermuxPP, links to this project's documentation and source, drops the donation and
community links and keeps the `pkg` commands. The line for reporting problems names the upstream
Termux package issue tracker explicitly, since package problems belong there. Only a motd that still
contains the stock lines is changed, and only those lines: a motd you wrote yourself is never
touched, and it is not rewritten again after apt reinstalls `termux-tools` unless the stock text is
back. To show your own message, edit `$PREFIX/etc/motd`, or put a `motd.sh` into
`~/.config/termuxpp`.

The stock login profile script `$PREFIX/etc/profile.d/init-termux-properties.sh` copies an example
`termux.properties` into `~/.termux` when none exists. TermuxPP makes it copy the example into
`~/.config/termuxpp` instead, and only when `termux.properties` exists in none of the three
directories, so no stale second copy shows up in a legacy directory. A script you edited yourself is
not touched.

New files should be created in `~/.config/termuxpp`. TermuxPP never moves or deletes files in the
legacy directories. To migrate, copy the files (keeping symlinks) into `~/.config/termuxpp` and
leave the old directories in place as a fallback and for tools that only read `~/.termux`.

## Limits

- TermuxPP's core looks for boot scripts in `~/.config/termuxpp/boot` only. Separate upstream
  companion apps such as Termux:Boot, Termux:Styling and Termux:Widget are not covered by this
  config resolver; unless their fork documentation confirms TermuxPP support, assume they still
  use their documented upstream paths (commonly under `~/.termux`). A symlink may be needed to
  share files between those apps and TermuxPP.
- `XDG_CONFIG_HOME` is not consulted, the directories are fixed.
- Stock Termux tooling that writes `~/.termux/font.ttf` (for example a Nix setup for the stock
  `com.termux` app) keeps working in the legacy directory, which is still read as a fallback. A
  font in `~/.config/termuxpp` takes precedence, so a managed font should be written there, or
  the copy in `~/.config/termuxpp` removed, if both are in use.
