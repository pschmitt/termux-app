---
page_ref: /docs/apps/termux/extra-keys-colors.html
---

# Extra Keys Active Colors

TermuxPP can recolor the pressed and latched states of the extra keys row above the soft keyboard
from `~/.termux/termux.properties`. Both keys are optional. When a key is absent, empty or not a
valid color, the color of the current theme is used, so the behavior of an existing configuration
does not change.

| Key | Applies to | Theme default |
| --- | --- | --- |
| `extra-keys-button-active-text-color` | Text of an active (tapped, or locked by a long press) special button: `CTRL`, `ALT`, `SHIFT`, `FN` | `#FF0000` (red) |
| `extra-keys-button-active-background-color` | Background of any extra key while it is pressed, and of the swipe-up popup key | `#9E9E9E` (grey) |

The value is `#RGB`, `#RRGGBB` or `#AARRGGBB` (alpha first, like Android), case-insensitive. A
value that is present but invalid is logged and ignored. The text and background colors of
inactive buttons are not configurable here and keep following the theme.

```properties
# TermuxPP purple: light lavender text on an active modifier, deep purple while a key is pressed
extra-keys-button-active-text-color = #D8B4FE
extra-keys-button-active-background-color = #3B1D6A
```

Run `termux-reload-settings` after editing the file. Stock Termux does not read these keys and
ignores them, so the same file can be shared with a stock installation.

The colors in the example are taken from the TermuxPP launcher icon (`#D8B4FE` foreground,
`#3B1D6A` background).
