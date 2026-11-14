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

New files should be created in `~/.config/termuxpp`. TermuxPP never moves or deletes files in the
legacy directories. To migrate, copy the files (keeping symlinks) into `~/.config/termuxpp` and
leave the old directories in place, since stock Termux and tools such as `login` (`~/.termux/shell`,
`~/.termux/motd.sh`) still read `~/.termux`.

Not covered: the Termux:Styling, Termux:Boot and Termux:Widget apps are not part of TermuxPP and
read only `~/.termux`. Shell commands that mention `~/.termux` in their help text (for example
`termux-reload-settings`) are unchanged.
