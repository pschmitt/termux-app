package com.termux.terminal;

import java.util.Locale;

/**
 * An `OSC 8` hyperlink received from a terminal application: {@code ESC ] 8 ; params ; URI ST}.
 * <p>
 * Instances are immutable. They are only metadata attached to terminal cells, the text that is
 * displayed for a link (its label) is stored in the cells as usual and is never changed by the link.
 * <p>
 * Receiving a hyperlink never opens it. The URI is validated by {@link #parse(String, String)}
 * when it is received (printable characters only, bounded length, scheme allow list) and callers
 * that open a link must still ask the user, see {@link #isLabelConcealingTarget(String)}.
 *
 * @see <a href="https://gist.github.com/egmontkob/eb114294efbcd5adb1944c9f3cb5feda">Hyperlinks in terminal emulators</a>
 */
public final class TerminalHyperlink {

    /** The max length of the URI in `chars`. The de-facto limit used by other terminal emulators is 2083. */
    public static final int MAX_URI_LENGTH = 2083;

    /** The max length of the optional `id` parameter in `chars`. */
    public static final int MAX_ID_LENGTH = 250;

    /**
     * The schemes that are accepted. Everything else (like `javascript:`, `intent:`, `content:`,
     * `file:`, `data:`, `tel:`) is ignored when it is received, so that no such link ever exists in
     * the terminal buffer to be opened later.
     */
    private static final String[] ALLOWED_SCHEMES = {"http", "https", "mailto", "ftp", "ftps", "sftp"};

    private final String mUri;
    private final String mId;

    private TerminalHyperlink(String uri, String id) {
        mUri = uri;
        mId = id;
    }

    /** The validated URI. */
    public String getUri() {
        return mUri;
    }

    /** The value of the optional `id=` parameter, or `null`. */
    public String getId() {
        return mId;
    }

    /**
     * Parse and validate the arguments of an `OSC 8` command.
     *
     * @param params The `params` field, which is a `:` separated list of `key=value` pairs. Only
     *               `id` is used, unknown keys are ignored. A malformed or too long `id` is dropped
     *               but does not invalidate the link.
     * @param uri    The URI field.
     * @return Returns the link, or `null` if the URI is empty or not acceptable.
     */
    public static TerminalHyperlink parse(String params, String uri) {
        if (uri == null || uri.isEmpty() || uri.length() > MAX_URI_LENGTH) return null;
        if (!isAcceptableUri(uri)) return null;

        String id = null;
        if (params != null && !params.isEmpty()) {
            for (String param : params.split(":", -1)) {
                if (param.startsWith("id=")) {
                    String value = param.substring(3);
                    if (!value.isEmpty() && value.length() <= MAX_ID_LENGTH && hasOnlyPrintableCharacters(value, false)) {
                        id = value;
                    }
                    break;
                }
            }
        }

        return new TerminalHyperlink(uri, id);
    }

    /** Check if the scheme is allowed and the string has no control, format or whitespace characters. */
    static boolean isAcceptableUri(String uri) {
        int colon = uri.indexOf(':');
        if (colon < 1) return false;

        String scheme = uri.substring(0, colon).toLowerCase(Locale.ROOT);
        char first = scheme.charAt(0);
        if (first < 'a' || first > 'z') return false;
        for (int i = 1; i < scheme.length(); i++) {
            char c = scheme.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.'))
                return false;
        }

        boolean allowed = false;
        for (String allowedScheme : ALLOWED_SCHEMES) {
            if (allowedScheme.equals(scheme)) {
                allowed = true;
                break;
            }
        }
        if (!allowed) return false;

        // `mailto:` has no authority, everything else needs something after the scheme.
        if (colon == uri.length() - 1) return false;

        return hasOnlyPrintableCharacters(uri, true);
    }

    private static boolean hasOnlyPrintableCharacters(String s, boolean allowNonAscii) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp <= 0x20 || cp == 0x7F) return false; // C0 controls, space, DEL.
            if (cp > 0x7E) {
                if (!allowNonAscii) return false;
                // C1 controls, bidi overrides, line/paragraph separators, zero width and other format
                // characters could be used to disguise the target in a dialog, and unpaired
                // surrogates / unassigned code points are invalid.
                int type = Character.getType(cp);
                if (type == Character.CONTROL || type == Character.FORMAT || type == Character.LINE_SEPARATOR ||
                    type == Character.PARAGRAPH_SEPARATOR || type == Character.SPACE_SEPARATOR ||
                    type == Character.SURROGATE || type == Character.UNASSIGNED || type == Character.PRIVATE_USE)
                    return false;
            }
        }
        return true;
    }

    /**
     * Check if the visible text of a link does not show where it leads to, so that opening it
     * should only be done after showing the actual target to the user.
     * <p>
     * Whitespace in the label is ignored since a label that was broken over several rows by the
     * application has indentation. A label is not concealing if it is the URI itself, with or
     * without the scheme (and `//`) and with or without a trailing `/`. Everything else (including a
     * label that is only the beginning of the URI) is concealing.
     */
    public boolean isLabelConcealingTarget(String label) {
        if (label == null) return true;
        String compactLabel = removeWhitespace(label);
        if (compactLabel.isEmpty()) return true;

        if (isSameAddress(compactLabel, mUri)) return false;

        // The label may leave out the scheme: `example.org/a` for `https://example.org/a`, or `me@example.org` for `mailto:me@example.org`.
        String withoutScheme = mUri.substring(mUri.indexOf(':') + 1);
        if (withoutScheme.startsWith("//")) withoutScheme = withoutScheme.substring(2);
        return !isSameAddress(compactLabel, withoutScheme);
    }

    private static boolean isSameAddress(String a, String b) {
        if (a.endsWith("/")) a = a.substring(0, a.length() - 1);
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return a.equals(b);
    }

    private static String removeWhitespace(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c) && c != '\u00a0') sb.append(c);
        }
        return sb.toString();
    }

    /** A hyperlink in the terminal and the text that is shown for it, see {@link TerminalBuffer#getHyperlinkSpans()}. */
    public static final class Span {
        /** The link. */
        public final TerminalHyperlink link;
        /** The text of the cells of the link, whitespace between the rows of a wrapped link is not included. */
        public final String label;
        /** The external row and the column where the link starts. */
        public final int startRow, startColumn;

        Span(TerminalHyperlink link, String label, int startRow, int startColumn) {
            this.link = link;
            this.label = label;
            this.startRow = startRow;
            this.startColumn = startColumn;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TerminalHyperlink)) return false;
        TerminalHyperlink other = (TerminalHyperlink) o;
        return mUri.equals(other.mUri) && (mId == null ? other.mId == null : mId.equals(other.mId));
    }

    @Override
    public int hashCode() {
        return mUri.hashCode() * 31 + (mId == null ? 0 : mId.hashCode());
    }

    @Override
    public String toString() {
        return mUri;
    }

}
