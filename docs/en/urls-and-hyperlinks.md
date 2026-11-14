---
page_ref: /docs/apps/termux/urls-and-hyperlinks.html
---

# URLs and OSC 8 Hyperlinks

TermuxPP finds URLs in the terminal text (tap on a URL, and the long press menu entry *Select URL*)
and supports terminal hyperlinks (`OSC 8`). Both are shown and opened by the same actions.

Opening a link is always something you do: nothing is ever opened when a program prints a link.

## Tapping a link

The tap opens links only while `terminal-onclick-url-open` is not set to `false` (the setting that
already existed for URLs in the text). A tap on

- a hyperlink opens its address. If the text that is shown for the link is not its address, a
  dialog shows both and asks first (*Open*, *Copy address* or *Cancel*);
- a URL in the text opens that URL, also when an application has broken it over several rows (see
  below);
- anything else is a normal tap.

## Select URL

*Select URL* lists the hyperlinks and URLs of the transcript and screen, latest first, the
hyperlinks before the URLs found in the text. A hyperlink whose text is not its address is listed as
`text → address`. A click copies the address, a long press opens it.

## OSC 8 hyperlinks

```
ESC ] 8 ; params ; URI ST     starts a link, the text after it is the link
ESC ] 8 ; ; ST                ends it
```

`ST` is `ESC \` or `BEL`. Supported:

- the label (displayed text) is independent from the URI, and is not changed in any way;
- the `id=` parameter; other parameters are ignored. Links are identified by URI and `id`;
- a link that continues over rows, whether by auto wrap or because the application wrote it on
  several rows (a link is the cells that carry it, so it ends at each row end and starts again on the
  next row);
- links in the scrollback, which scroll and reflow on a resize together with their text.

A link is dropped together with the text of the cells: when they are erased (`EL`, `ED`, `ECH`,
`DCH`, ...), overwritten (also by half of a wide character, or by another link), scrolled out of the
transcript or cleared with `clear` (`CSI 3 J`). The main and the alternate screen do not share an
open link, and a reset (`RIS`, `reset`) ends it. A link never changes how text is displayed.

### What is accepted and the limits

A link that is not accepted is ignored: its text is shown as usual, without a link.

| | |
|---|---|
| Schemes | `http`, `https`, `mailto`, `ftp`, `ftps`, `sftp`. Others (`javascript:`, `intent:`, `file:`, `content:`, `data:`, `tel:`, ...) are ignored |
| Characters | Control characters (also C1), whitespace, bidi and other format characters, private use and unassigned code points are rejected. Other Unicode is kept as it is, as are percent encoding, query, fragment and parentheses |
| Length | URI at most 2083 characters, `id` at most 250 (a longer `id` is dropped, the link stays) |
| Sequence size | An escape sequence longer than 16384 characters is not executed |
| Distinct links | At most 8192 at the same time, and 1 MiB of characters in all. When this is exceeded further links are shown without a link (never with another link); space is freed when the text that carries them is erased or scrolled out. After a bulk erase it can take some more links until the space is available again |
| Select URL | At most 2000 hyperlinks are listed |

A hyperlink whose label is the address (with or without scheme, with or without a trailing `/`,
ignoring whitespace the application inserted) is opened without asking. Anything else, including a
label that only shows the beginning of the address, is shown in a dialog.

## URLs in the text and multi-line URLs

URLs are found with a pattern for the schemes TermuxPP knows (`http`, `https`, `ftp`, `sftp`,
`git`, `ws`, ...). Percent encoding, query, fragment, parentheses and Unicode letters are kept.
`. , ; : ! ? ' "` at the end are not part of the URL, and neither is a `)` that has no `(` in the URL.

A terminal application that wraps its own output (for example a CLI printing a long login URL, indented
by two spaces, a tab, or inside a box) ends each row with a newline instead of letting the terminal
wrap. As URLs have no whitespace the rows are joined **only if all of this is true**:

1. the URL is the last thing on its row (a right border `│ ┃ ║ |` of a box and spaces may follow);
2. it reaches the right edge of the terminal, or of the box, to within 2 columns: a URL that ends
   earlier had no reason to be broken;
3. the next row is exactly one word of URL characters, after any amount of spaces and tabs and an
   optional left border, and does not start with a scheme (that is another URL). A row with a
   second word, an empty row, or a row of only punctuation is never a continuation;
4. the result is at most 8192 characters long and 65 rows. For URLs of more rows the first 65 rows are
   the URL.

The indentation, the border and the newline are removed from the URL only; the text is not changed.
Each row of a long URL must again reach the edge to continue it.

Not handled, by design: text that is cut by the application without a recognizable edge (for
example a URL that was shortened with `…`, or wrapped at a width that is not the terminal width), URLs
whose rows are separated by an empty row, and a continuation that is a word and also valid text (the
rule above joins a row such as `report` after a URL that ends at the edge).

## Where it is implemented

| | |
|---|---|
| `terminal-emulator` `TerminalHyperlink`, `TerminalHyperlinkRegistry` | validation, interning and the limits |
| `TerminalRow`, `TerminalBuffer`, `TerminalEmulator` | the hyperlink of each cell, its lifetime, `OSC 8` parsing |
| `termux-shared` `TermuxUrlUtils` | URLs in the text, joining rows |
| `TermuxTerminalViewClient` | tap, the confirmation and *Select URL* |

Tests: `HyperlinkTest` (emulator) and `TermuxUrlUtilsTest` (`termux-shared`).
