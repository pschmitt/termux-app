---
page_ref: /docs/apps/termux/tap-gestures.html
---

# Terminal Tap and Extra Keys Swipe Actions

TermuxPP can run an action for a single, a double and a triple tap on the terminal, and for a
swipe on the extra keys row. Configure them in **Settings > TermuxPP > Properties** or in the text
file `~/.config/termuxpp/termux.properties`, the same file as every other TermuxPP and Termux
setting (see [config-dir.md](config-dir.md) for the legacy locations that are still read).

| Key | Gesture | Default |
| --- | --- | --- |
| `terminal-single-tap-action` | One finger tap on the terminal | `show-keyboard` |
| `terminal-double-tap-action` | Two quick taps in a row | `none` |
| `terminal-triple-tap-action` | Three quick taps in a row | `none` |
| `terminal-triple-tap-focus-title` | Regular expression for a running session to focus before trying the triple-tap action | unset |
| `extra-keys-swipe-left-action` | Swipe on the normal extra keys row (see below) | `none` |
| `gesture-command-context` | Shell environment for configured command actions | `session` |

The four keys are independent and active at the same time. Valid values, case-insensitive:

| Value | Action |
| --- | --- |
| `none` | Do nothing. |
| `show-keyboard` | Show the soft keyboard. If it is already visible nothing changes. |
| `toggle-keyboard` | Hide the soft keyboard if it is visible, otherwise show it. Before Android 6 the visibility cannot be read and this only shows it. |
| `next-session` | Switch to the next session, wrapping around. With one session, creates a new one if `create-session-on-single-session-switch=true`. |
| `previous-session` | Switch to the previous session, wrapping around. With one session, creates a new one if `create-session-on-single-session-switch=true`. |
| `open-sidebar` | Open the sidebar with the session list. It is not opened while it is locked closed in copy mode. |
| `open-menu` | Open the terminal menu, the bottom sheet that a long press on the terminal and then "More..." opens (Extract URL, Share transcript, Autofill, Reset, Kill process, Style, Settings and so on). It never touches the soft keyboard. |
| `font-size-up` | Increase the terminal font size by one step, like pinching out. It never touches the soft keyboard. |
| `font-size-down` | Decrease the terminal font size by one step, like pinching in. It never touches the soft keyboard. |
| `send-keys` | Send keys to the terminal, like Escape or Ctrl+L, given by the matching `*-keys` key (see below). |
| `run-command` | Run the matching `*-command` text in a new visible terminal session. See [Running commands](#running-commands). |

A missing or empty key uses its default. A value that is not listed is logged and the default is
used. The soft keyboard flags that disable the keyboard always win: `show-keyboard` and
`toggle-keyboard` do nothing while it is disabled, the other actions still work.

When a triple-tap action is configured, `terminal-triple-tap-focus-title` can
match the title reported by a program such as tmux. A match focuses that
session. If the current session matches, it stays selected. If no active
session matches, the configured action runs. For example, combine a title
pattern with `send-keys` and a shell shortcut that starts the desired program.

## Sending keys

The action `send-keys` sends keys to the current terminal session, as if they were typed. Every
gesture has its own key that says which keys to send:

| Gesture | Action key | Keys key |
| --- | --- | --- |
| Single tap | `terminal-single-tap-action` | `terminal-single-tap-keys` |
| Double tap | `terminal-double-tap-action` | `terminal-double-tap-keys` |
| Triple tap | `terminal-triple-tap-action` | `terminal-triple-tap-keys` |
| Extra keys swipe | `extra-keys-swipe-left-action` | `extra-keys-swipe-left-keys` |

## Running commands

Select `run-command` for a tap or extra-keys swipe, then set its matching command property:

| Gesture | Command property |
| --- | --- |
| Single tap | `terminal-single-tap-command` |
| Double tap | `terminal-double-tap-command` |
| Triple tap | `terminal-triple-tap-command` |
| Extra keys swipe | `extra-keys-swipe-left-command` |

Commands open a new visible terminal session and leave the current program alone. The command is
interpreted by the selected shell, then the session stays open at a shell prompt so you can inspect
output and continue working. Arguments are passed as separate process arguments up to the shell;
the command text itself is one shell `-c` argument, so quoted spaces and shell syntax are kept
intact.

`gesture-command-context` selects the shell environment:

* `session` follows `nix-session`, the setting used by ordinary new terminal sessions.
* `nix` always enters the managed Nix guest. If it is not provisioned, the app reports that and
  does not silently run the command in the outer shell.
* `outer` runs in the native Termux prefix.

An unset or blank command makes its `run-command` action do nothing. Commands are trusted shell
code stored in `termux.properties`; do not put credentials or other secrets in command text. The
app redacts command values from its property and execution logs. Use a script that reads runtime
secrets when a command needs them.

Extra-key buttons can run commands too. Use a command object instead of `key` or `macro` in the
`extra-keys` JSON, with an optional label:

```properties
extra-keys = [["ESC", {"command": "nix develop", "display": "DEV"}]]
```

The same `gesture-command-context` applies. A command button cannot also send a key or macro; it
can be used as a popup button in the same way as a regular key.

The keys are written exactly like the `macro` of an extra keys button in `extra-keys`, so what works
there works here:

* Keys are separated by whitespace and sent in order.
* The names of the extra keys row: `ESC`, `TAB`, `ENTER`, `SPACE`, `UP`, `DOWN`, `LEFT`, `RIGHT`,
  `HOME`, `END`, `PGUP`, `PGDN`, `INS`, `DEL`, `BKSP` and `F1` to `F12`, and the aliases of the row
  such as `ESCAPE`, `RETURN`, `PAGEUP`, `DELETE`, `BACKSPACE`. Names are upper case, as in the extra
  keys.
* `CTRL`, `ALT`, `SHIFT` and `FN` apply to the next key only and have to be followed by one. `CTRL l`
  is Ctrl+L, `CTRL ALT x` is Ctrl+Alt+X. `CONTROL` and `SHFT` are accepted too.
* Any other word is typed as text, a character at a time. `ESC :wq ENTER` sends Escape, the
  characters `:wq` and Enter. A space has to be written `SPACE`, since spaces separate the keys.
* The words of the row that are not terminal input (`KEYBOARD`, `DRAWER`, `PASTE`, `SCROLL`) are not
  accepted. Nothing is ever run as a command, only terminal key input is sent.

If the keys key is missing, blank or invalid (for example a `CTRL` with no key after it), the problem
is logged and the gesture does nothing, as if its action were `none`. That also means that nothing is
animated or consumed for the swipe, and that taps do not wait for it. The keys keys are ignored for
every other action.

Each key is sent with its own modifiers, so no modifier stays pressed after the gesture. The `CTRL`
or `ALT` button of the extra keys row is different: when it is latched at the time of the gesture it
applies to the first key that is sent and is released, as it would for any key typed.

A tap that only sends keys does not show the soft keyboard. Combine it with another gesture if you
want both.

## How taps are told apart

A double tap must not also run the single tap action, and a triple tap must not also run the double
tap action. TermuxPP therefore acts on a tap count only when no further tap can follow:

* A tap that reaches the highest count that has an action acts at once. With a triple tap action
  configured, the third tap acts immediately.
* A lower count waits until the time for the next tap has run out (the system double tap timeout,
  about 300 ms after the press), and then acts. With a double tap action configured, the single
  tap acts after that wait.
* While no double or triple tap action is configured, the single tap acts exactly as before, with
  the latency of the system's confirmed single tap, and nothing else changes.

Taps have to follow each other within the system double tap timeout and close to one another. A
press that moves (scrolling, text selection), a long press, a second finger and taps from an
external mouse are never counted, and drop a tap that was waiting. With a terminal application that
uses the mouse (for example tmux with `mouse on`), every tap is still forwarded to it as a click
at once, as before, in addition to the configured action.

## Extra keys swipe

`extra-keys-swipe-left-action` runs on a deliberate swipe over the normal extra keys row, with the
finger moving from left to right. This is the direction the row could not go to before, the text
input is on the other side. The text input is the page to the right and this swipe goes to the
(non-existing) page to the left, hence the name. The swipe has to be at least 48 dp long and
clearly more horizontal than vertical.

Swiping the other way, with the finger from right to left, still goes to the text input, and going
back from the text input to the normal row works as before. Only the deliberate swipe over the
normal row is consumed, and the keys under the finger are released without being pressed.
Tapping, holding and repeating keys, the swipe-up popup and vertical scrolling of the row keep
working.

When the swipe is recognized, the row slides a little (up to 40 dp) in the direction of the finger
and snaps back within about 0.2 seconds, as feedback that the swipe was taken. The animation only
runs while `extra-keys-swipe-left-action` is not `none`, and not when the system animations are
turned off (animator duration scale 0), the action itself still runs then. With `none` the swipe is
not recognized at all and the row behaves exactly as without this feature.

## Volume buttons

The two volume buttons can run the same actions. They are configured with their own keys in the
same file:

| Key | Button | Default |
| --- | --- | --- |
| `volume-up-action` | Volume up | `modifier` |
| `volume-down-action` | Volume down | `modifier` |
| `volume-up-keys` | The keys that volume up sends with `send-keys` | none |
| `volume-down-keys` | The keys that volume down sends with `send-keys` | none |

The two buttons are independent. Valid values of the action keys, case-insensitive, are `modifier`
and every action of the table above except `run-command` (`none`, `show-keyboard`, `toggle-keyboard`,
`next-session`, `previous-session`, `open-sidebar`, `open-menu`, `font-size-up`, `font-size-down`,
`send-keys`).
The `*-keys` keys are written as described in "Sending keys".

* `modifier` is the default and the behaviour without any configuration: while the button is held,
  volume down is a virtual Ctrl key and volume up is a virtual Fn key (Fn plus a letter or digit
  gives arrows, Page Up and so on), and the system volume does not change. Note that a plain press
  of a volume button does not change the font size by default, the font size is changed by
  pinching or by the `font-size-up` and `font-size-down` actions.
* `none` gives the button back to Android: it changes the system volume as in any other app.
* Any other action replaces the modifier role of that button and runs when the button is pressed.
  The button is consumed, so the system volume does not change either, also when the button is
  released.
* The action runs once for each press and never on release. A held button auto repeats, and only
  `font-size-up` and `font-size-down` repeat with it, so that holding the button keeps growing or
  shrinking the text. Every other action (a session switch, the sidebar, the menu, the keyboard,
  keys) runs once however long the button is held.
* The soft keyboard flags that disable the keyboard win as for every other gesture: `show-keyboard`
  and `toggle-keyboard` do nothing while it is disabled, the other actions still work.
* `send-keys` without a usable `volume-up-keys` or `volume-down-keys` is logged and does nothing,
  as if the action were `none`, which gives the button back to the system volume.
* A value that is not listed is logged and `modifier` is used.
* `volume-keys = volume` (the existing key) still wins over everything: both buttons then always
  change the system volume and these keys are ignored.
* Volume buttons of a full external (alphabetic) keyboard are never taken over, they stay with
  the system.
* A change takes effect after `termux-reload-settings`. A button that is held at that moment is not
  left stuck as a modifier.

## Locking a modifier

The modifier keys of the extra keys row (`CTRL`, `ALT`, `SHIFT`, `FN`) have three ways to be used:

| Gesture on the modifier | Result |
|-------------------------|--------|
| Tap | Applies to the next key only, then it is released (unchanged) |
| Double tap | Locked: it applies to every key until you tap it again |
| Long press | Locked, the same as a double tap (unchanged). Long press again to release |

The second tap of a double tap has to follow within the system double tap timeout, otherwise
it releases the modifier like before. For example, double tap `CTRL` and type `d` twice to send
`Ctrl+D` twice. A locked modifier keeps the active colors of a latched one. This is not
configurable and has no effect on other keys like `ESC`.

## Example

```properties
# Double tap hides the keyboard when it is visible and shows it otherwise
terminal-double-tap-action = toggle-keyboard

# Triple tap opens the sidebar
terminal-triple-tap-action = open-sidebar

# Alternatively the triple tap opens the terminal menu, the one that a long press opens
# terminal-triple-tap-action = open-menu

# Optional: keep the default single tap (show-keyboard) or turn it off with none
terminal-single-tap-action = show-keyboard

# Optional: swipe the extra keys row to the right to go to the previous session
extra-keys-swipe-left-action = previous-session
```

Sending keys:

```properties
# Double tap sends Escape
terminal-double-tap-action = send-keys
terminal-double-tap-keys = ESC

# Triple tap sends Ctrl+L, which clears the screen in most shells
terminal-triple-tap-action = send-keys
terminal-triple-tap-keys = CTRL l

# The swipe sends Escape, then ":q", then Enter, for example to leave vim
extra-keys-swipe-left-action = send-keys
extra-keys-swipe-left-keys = ESC :q ENTER
```

Volume buttons:

```properties
# Volume up grows the text, volume down shrinks it (hold to keep going)
volume-up-action = font-size-up
volume-down-action = font-size-down

# Alternatively: volume up switches to the next session, volume down stays the virtual Ctrl key
# volume-up-action = next-session

# Alternatively: volume down sends Escape, volume up keeps the system volume
# volume-down-action = send-keys
# volume-down-keys = ESC
# volume-up-action = none
```

Run `termux-reload-settings` after editing the file. Stock Termux does not read these keys and
ignores them, so the same file can be shared with a stock installation.
