# Config directory

TermuxPP keeps all user configuration in `~/.config/termuxpp`:

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
touched.

New files should be created in `~/.config/termuxpp`. TermuxPP never moves or deletes files in the
legacy directories. To migrate, copy the files (keeping symlinks) into `~/.config/termuxpp` and
leave the old directories in place as a fallback and for tools that only read `~/.termux`.

## Limits

- Termux:Boot reads only `~/.termux/boot`, and Termux:Styling and Termux:Widget are not part of
  TermuxPP and read only `~/.termux`. Keep `~/.config/termux/boot` (or `~/.termux/boot`) pointing
  at `~/.config/termuxpp/boot`, for example with a symlink, so boot scripts stay in one place.
- `XDG_CONFIG_HOME` is not consulted, the directories are fixed.
- Stock Termux tooling that writes `~/.termux/font.ttf` (for example a Nix setup for the stock
  `com.termux` app) keeps working in the legacy directory, which is still read as a fallback. A
  font in `~/.config/termuxpp` takes precedence, so a managed font should be written there, or
  the copy in `~/.config/termuxpp` removed, if both are in use.
