---
page_ref: /docs/apps/termux/tap-gestures.html
---

# Terminal Tap and Extra Keys Swipe Actions

TermuxPP can run an action for a single, a double and a triple tap on the terminal, and for a
swipe on the extra keys row. They are configured only in the text file
`~/.termux/termux.properties` (or `~/.config/termux/termux.properties`), the same file as every
other TermuxPP and Termux setting. There is no entry in the Android settings screens.

| Key | Gesture | Default |
| --- | --- | --- |
| `terminal-single-tap-action` | One finger tap on the terminal | `show-keyboard` |
| `terminal-double-tap-action` | Two quick taps in a row | `none` |
| `terminal-triple-tap-action` | Three quick taps in a row | `none` |
| `extra-keys-swipe-left-action` | Swipe on the normal extra keys row (see below) | `none` |

The four keys are independent and active at the same time. Valid values, case-insensitive:

| Value | Action |
| --- | --- |
| `none` | Do nothing. |
| `show-keyboard` | Show the soft keyboard. If it is already visible nothing changes. |
| `toggle-keyboard` | Hide the soft keyboard if it is visible, otherwise show it. Before Android 6 the visibility cannot be read and this only shows it. |
| `next-session` | Switch to the next session, wrapping around. Does nothing with a single session. |
| `previous-session` | Switch to the previous session, wrapping around. Does nothing with a single session. |
| `open-sidebar` | Open the sidebar with the session list. It is not opened while it is locked closed in copy mode. |
| `open-menu` | Open the terminal menu, the bottom sheet that a long press on the terminal and then "More..." opens (Extract URL, Share transcript, Autofill, Reset, Kill process, Style, Settings and so on). It never touches the soft keyboard. |
| `send-keys` | Send keys to the terminal, like Escape or Ctrl+L, given by the matching `*-keys` key (see below). |

A missing or empty key uses its default. A value that is not listed is logged and the default is
used. The soft keyboard flags that disable the keyboard always win: `show-keyboard` and
`toggle-keyboard` do nothing while it is disabled, the other actions still work.

## Sending keys

The action `send-keys` sends keys to the current terminal session, as if they were typed. Every
gesture has its own key that says which keys to send:

| Gesture | Action key | Keys key |
| --- | --- | --- |
| Single tap | `terminal-single-tap-action` | `terminal-single-tap-keys` |
| Double tap | `terminal-double-tap-action` | `terminal-double-tap-keys` |
| Triple tap | `terminal-triple-tap-action` | `terminal-triple-tap-keys` |
| Extra keys swipe | `extra-keys-swipe-left-action` | `extra-keys-swipe-left-keys` |

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

Run `termux-reload-settings` after editing the file. Stock Termux does not read these keys and
ignores them, so the same file can be shared with a stock installation.
