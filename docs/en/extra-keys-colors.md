---
page_ref: /docs/apps/termux/extra-keys-colors.html
---

# Extra Keys Active Colors and Corner Radius

TermuxPP can recolor the pressed and latched states of the extra keys row above the soft keyboard
from `~/.config/termuxpp/termux.properties` (see [config-dir.md](config-dir.md)). Both keys are optional. When a key is absent, empty or not a
valid color, the color of the current theme is used, so the behavior of an existing configuration
does not change.

| Key | Applies to | Theme default |
| --- | --- | --- |
| `extra-keys-button-active-text-color` | Text of an active (tapped, or locked by a long press) special button: `CTRL`, `ALT`, `SHIFT`, `FN` | `#FF0000` (red) |
| `extra-keys-button-active-background-color` | Background of any extra key while it is pressed, of the swipe-up popup key and, when this key is set, of an active (latched or locked) special button | `#9E9E9E` (grey) while pressed; no fill for latched keys |
| `extra-keys-button-corner-radius` | Corner radius in dp of the backgrounds of all extra keys (pressed, latched and popup) | `0` / theme (square) |

The value is `#RGB`, `#RRGGBB` or `#AARRGGBB` (alpha first, like Android), case-insensitive. A
value that is present but invalid is logged and ignored. The text and background colors of
inactive buttons are not configurable here and keep following the theme.

Without `extra-keys-button-active-background-color` a latched `CTRL`/`ALT` only changes its text
color, as before. With it, the latched key also gets that background, which stays filled until the
key is unlatched (it is not reset when the finger is lifted).

`extra-keys-button-corner-radius` is a plain decimal number of dp from `0` to `32`, for example
`8` or `6.5`. Signs, units, exponents, `NaN` and values above `32` are invalid, logged and ignored
(the theme shape is used). Only the drawn background is rounded: the touch area of every key stays
the full grid cell. A radius above half the key height makes a pill.

```properties
# TermuxPP purple: light lavender text on an active modifier, deep purple while a key is pressed
extra-keys-button-active-text-color = #D8B4FE
extra-keys-button-active-background-color = #3B1D6A
extra-keys-button-corner-radius = 8
```

Run `termux-reload-settings` after editing the file. Stock Termux does not read these keys and
ignores them, so the same file can be shared with a stock installation.

The colors in the example are taken from the TermuxPP launcher icon (`#D8B4FE` foreground,
`#3B1D6A` background).
