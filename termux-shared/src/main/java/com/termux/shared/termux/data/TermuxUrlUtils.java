package com.termux.shared.termux.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finding URLs in terminal text.
 * <p>
 * Besides the URL grammar ({@link #getUrlMatchRegex()}) this joins URLs that a terminal application
 * has broken over several rows itself, which is common for long URLs printed by CLIs that wrap their
 * own output and indent the continuation (for example with spaces, or inside a box). See
 * {@link #extractUrls(String, int)} for the rules and their limits; they are documented for users in
 * {@code docs/en/urls-and-hyperlinks.md}.
 */
public class TermuxUrlUtils {

    /** The characters of the path, query and fragment of a URL, for a regex character class. */
    private static final String PATH_CHARS = "\\p{L}\\p{N}\\p{M}:@%\\-._~!$&'()*+,;=?/";

    /** The max length of a URL that is assembled from several rows, in `chars`. */
    public static final int MAX_JOINED_URL_LENGTH = 8192;

    /** The max number of continuation rows that are added to a URL. */
    public static final int MAX_CONTINUATION_ROWS = 64;

    /**
     * How many columns before the right edge of the row (or of the box) the URL may end and still
     * be considered to have been cut by the width of the terminal. Some applications wrap their
     * output a column or two before the edge.
     */
    public static final int EDGE_TOLERANCE_COLUMNS = 2;

    /** Characters that draw the vertical side of a box around the text, as done by TUIs. */
    private static final String BORDER_CHARS = "\u2502\u2503\u2551|";

    /** A row that continues a URL: one token, only characters that are valid in a URL. */
    private static final Pattern CONTINUATION_TOKEN = Pattern.compile("[" + PATH_CHARS + "#\\[\\]]+");
    private static final Pattern STARTS_WITH_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.\\-]*://.*", Pattern.DOTALL);
    private static final Pattern HAS_ALPHANUMERIC = Pattern.compile(".*[\\p{L}\\p{N}].*", Pattern.DOTALL);

    public static Pattern URL_MATCH_REGEX;

    public static Pattern getUrlMatchRegex() {
        if (URL_MATCH_REGEX != null) return URL_MATCH_REGEX;

        StringBuilder regex_sb = new StringBuilder();

        regex_sb.append("(");                       // Begin first matching group.
        regex_sb.append("(?:");                     // Begin scheme group.
        regex_sb.append("dav|");                    // The DAV proto.
        regex_sb.append("dict|");                   // The DICT proto.
        regex_sb.append("dns|");                    // The DNS proto.
        regex_sb.append("file|");                   // File path.
        regex_sb.append("finger|");                 // The Finger proto.
        regex_sb.append("ftp(?:s?)|");              // The FTP proto.
        regex_sb.append("git|");                    // The Git proto.
        regex_sb.append("gemini|");                 // The Gemini proto.
        regex_sb.append("gopher|");                 // The Gopher proto.
        regex_sb.append("http(?:s?)|");             // The HTTP proto.
        regex_sb.append("imap(?:s?)|");             // The IMAP proto.
        regex_sb.append("irc(?:[6s]?)|");           // The IRC proto.
        regex_sb.append("ip[fn]s|");                // The IPFS proto.
        regex_sb.append("ldap(?:s?)|");             // The LDAP proto.
        regex_sb.append("pop3(?:s?)|");             // The POP3 proto.
        regex_sb.append("redis(?:s?)|");            // The Redis proto.
        regex_sb.append("rsync|");                  // The Rsync proto.
        regex_sb.append("rtsp(?:[su]?)|");          // The RTSP proto.
        regex_sb.append("sftp|");                   // The SFTP proto.
        regex_sb.append("smb(?:s?)|");              // The SAMBA proto.
        regex_sb.append("smtp(?:s?)|");             // The SMTP proto.
        regex_sb.append("svn(?:(?:\\+ssh)?)|");     // The Subversion proto.
        regex_sb.append("tcp|");                    // The TCP proto.
        regex_sb.append("telnet|");                 // The Telnet proto.
        regex_sb.append("tftp|");                   // The TFTP proto.
        regex_sb.append("udp|");                    // The UDP proto.
        regex_sb.append("vnc|");                    // The VNC proto.
        regex_sb.append("ws(?:s?)");                // The Websocket proto.
        regex_sb.append(")://");                    // End scheme group.
        regex_sb.append(")");                       // End first matching group.


        // Begin second matching group.
        regex_sb.append("(");

        // User name and/or password in format 'user:pass@'.
        regex_sb.append("(?:\\S+(?::\\S*)?@)?");

        // Begin host group.
        regex_sb.append("(?:");

        // IP address (from http://www.regular-expressions.info/examples.html).
        regex_sb.append("(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)|");

        // Host name or domain.
        regex_sb.append("(?:(?:[a-z\\p{L}\\p{N}\\p{M}]-*)*[a-z\\p{L}\\p{N}\\p{M}]+)(?:(?:\\.(?:[a-z\\p{L}\\p{N}\\p{M}]-*)*[a-z\\p{L}\\p{N}\\p{M}]+)*(?:\\.(?:[a-z\\p{L}\\p{N}\\p{M}]-*){1,}[a-z\\p{L}\\p{N}\\p{M}]{1,}))?|");

        // Just path. Used in case of 'file://' scheme.
        regex_sb.append("/(?:(?:[a-z\\p{L}\\p{N}\\p{M}]-*)*[a-z\\p{L}\\p{N}\\p{M}]+)");

        // End host group.
        regex_sb.append(")");

        // Port number.
        regex_sb.append("(?::\\d{1,5})?");

        // Resource path with optional query string.
        regex_sb.append("(?:/[" + PATH_CHARS + "]*)?");

        // Fragment.
        regex_sb.append("(?:#[" + PATH_CHARS + "]*)?");

        // End second matching group.
        regex_sb.append(")");

        URL_MATCH_REGEX = Pattern.compile(
            regex_sb.toString(),
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);

        return URL_MATCH_REGEX;
    }

    /**
     * Extract the URLs of a text, without trying to join URLs that were broken over several rows by
     * the application. Trailing punctuation that is not part of the URL is removed.
     */
    public static LinkedHashSet<CharSequence> extractUrls(String text) {
        return extractUrls(text, 0);
    }

    /**
     * Extract the URLs of a text of terminal rows separated by `\n`, which is expected to have rows that
     * were wrapped by the terminal itself (auto wrap) already joined, like
     * {@code TerminalBuffer.getTranscriptText()}.
     * <p>
     * URLs that an application has broken over several rows (and that therefore do not have auto wrap
     * set) are joined, which is what makes URLs printed by CLIs that indent their wrapped output
     * selectable as a whole. Since URLs never contain whitespace this is only done when all of the
     * following is true, so that unrelated text is never glued to a URL:
     * <ul>
     * <li>the URL is the last thing on its row (only a right border of a box and whitespace may follow);</li>
     * <li>it reaches the right edge of the terminal (or of the box) within {@link #EDGE_TOLERANCE_COLUMNS},
     * since otherwise there would have been no reason to break it;</li>
     * <li>the next row is only one token of URL characters, after an indentation of any number of
     * spaces or tabs and an optional left border, and the token does not start with a scheme (a new
     * URL). A row with more words is never a continuation;</li>
     * <li>the joined URL is at most {@link #MAX_JOINED_URL_LENGTH} long and made of at most
     * {@link #MAX_CONTINUATION_ROWS} rows. This is repeated for the following rows as long as each row
     * reaches the edge again.</li>
     * </ul>
     * Newlines and the indentation are only removed for URLs, everything else is unchanged.
     *
     * @param columns The number of columns of the terminal, or `0` to not join rows.
     */
    public static LinkedHashSet<CharSequence> extractUrls(String text, int columns) {
        LinkedHashSet<CharSequence> urlSet = new LinkedHashSet<>();
        if (text == null) return urlSet;
        for (UrlMatch match : findUrls(joinWrappedUrlRows(text, columns, false))) urlSet.add(match.url);
        return urlSet;
    }

    /**
     * Find the URL that has the character at {@code offset} of a text of terminal rows, joining
     * URLs that were broken over several rows like {@link #extractUrls(String, int)}.
     *
     * @return Returns the URL, or `null` if the character is not part of one.
     */
    @Nullable
    public static String findUrlAtOffset(@NonNull String text, int columns, int offset) {
        if (offset < 0 || offset >= text.length()) return null;
        Joined joined = joinWrappedUrlRows(text, columns, true);
        for (UrlMatch match : findUrls(joined)) {
            for (int i = match.start; i < match.end; i++) {
                if (joined.rawIndex(i) == offset) return match.url;
            }
        }
        return null;
    }

    /** A URL found in a {@link Joined} text, and where. */
    private static final class UrlMatch {
        final String url;
        /** The range in the joined text. */
        final int start, end;

        UrlMatch(String url, int start, int end) {
            this.url = url;
            this.start = start;
            this.end = end;
        }
    }

    private static List<UrlMatch> findUrls(Joined joined) {
        List<UrlMatch> urls = new ArrayList<>();
        Matcher matcher = getUrlMatchRegex().matcher(joined.text);
        while (matcher.find()) {
            int start = matcher.start(1);
            int end = trimTrailingPunctuation(joined.text, start, matcher.end());
            // Nothing left after the scheme.
            if (end <= matcher.end(1)) continue;
            urls.add(new UrlMatch(joined.text.substring(start, end), start, end));
        }
        return urls;
    }

    /**
     * Get the end of the URL without the characters at its end that are most likely part of the
     * sentence around it: `.,;:!?` and quotes, and a closing `)` that has no opening one in the URL.
     */
    static int trimTrailingPunctuation(String text, int start, int end) {
        while (end > start) {
            char c = text.charAt(end - 1);
            if (c == '.' || c == ',' || c == ';' || c == ':' || c == '!' || c == '?' || c == '\'' || c == '"') {
                end--;
            } else if (c == ')') {
                int open = 0, close = 0;
                for (int i = start; i < end; i++) {
                    char x = text.charAt(i);
                    if (x == '(') open++;
                    else if (x == ')') close++;
                }
                if (close > open) end--;
                else break;
            } else {
                break;
            }
        }
        return end;
    }

    /** The result of {@link #joinWrappedUrlRows(String, int, boolean)}. */
    private static final class Joined {
        final String text;
        /** For every char of {@link #text} the index in the original text, or `null` if they are the same. */
        @Nullable final int[] rawIndex;

        Joined(String text, @Nullable int[] rawIndex) {
            this.text = text;
            this.rawIndex = rawIndex;
        }

        int rawIndex(int index) {
            return rawIndex == null ? index : rawIndex[index];
        }
    }

    /** The parts of a row that are needed to decide if it is joined, see {@link #analyzeRow}. */
    private static final class Row {
        /** The range of the content: after indentation and left border, before right border and trailing whitespace. */
        int contentStart, contentEnd;
        /** Whether the content ends within the tolerance of the right edge of the terminal (or of the box). */
        boolean reachesEdge;
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\u00a0';
    }

    private static boolean isBorder(char c) {
        return BORDER_CHARS.indexOf(c) >= 0;
    }

    /** All indexes are in {@code text}; the row is {@code [start, end)}. */
    private static Row analyzeRow(String text, int start, int end, int columns) {
        Row row = new Row();

        int s = start;
        while (s < end && isBlank(text.charAt(s))) s++;
        // A left border has to be followed by whitespace, so that it is not mistaken for text.
        if (s < end && isBorder(text.charAt(s)) && (s + 1 == end || isBlank(text.charAt(s + 1)))) {
            s++;
            while (s < end && isBlank(text.charAt(s))) s++;
        }
        row.contentStart = s;

        int e = end;
        while (e > s && isBlank(text.charAt(e - 1))) e--;
        int borderColumn = -1;
        if (e > s && isBorder(text.charAt(e - 1)) && (e - 1 == s || isBlank(text.charAt(e - 2)))) {
            borderColumn = e - 1 - start;
            e--;
            while (e > s && isBlank(text.charAt(e - 1))) e--;
        }
        row.contentEnd = e;

        int contentColumns = e - start;
        if (columns > 0 && contentColumns > 0) {
            if (borderColumn >= 0) {
                // The text of a box ends one column (the padding) before its border.
                row.reachesEdge = contentColumns >= borderColumn - 1 - EDGE_TOLERANCE_COLUMNS;
            } else {
                // Rows that the terminal wrapped itself are already joined: only the last one counts.
                int fill = ((contentColumns - 1) % columns) + 1;
                row.reachesEdge = fill >= columns - EDGE_TOLERANCE_COLUMNS;
            }
        }
        return row;
    }

    /** Whether the row ends with a URL (that has no whitespace inside up to the end of the row content). */
    private static boolean endsWithUrl(String text, int start, Row row) {
        int tokenStart = row.contentEnd;
        while (tokenStart > row.contentStart && !isBlank(text.charAt(tokenStart - 1))) tokenStart--;
        if (tokenStart == row.contentEnd) return false;

        Matcher matcher = getUrlMatchRegex().matcher(text);
        matcher.region(tokenStart, row.contentEnd);
        while (matcher.find()) {
            // The match must run to the end of the row: a character that is not valid in a URL ends it.
            if (matcher.end() == row.contentEnd) return true;
        }
        return false;
    }

    /** Whether the content of the row is a single token that can continue a URL. */
    private static boolean isContinuation(String text, Row row) {
        if (row.contentEnd <= row.contentStart) return false;
        for (int i = row.contentStart; i < row.contentEnd; i++) {
            if (isBlank(text.charAt(i))) return false;
        }
        CharSequence token = text.subSequence(row.contentStart, row.contentEnd);
        return CONTINUATION_TOKEN.matcher(token).matches()
            && HAS_ALPHANUMERIC.matcher(token).matches()
            && !STARTS_WITH_SCHEME.matcher(token).matches();
    }

    /**
     * Join the URLs that were broken over several rows, see {@link #extractUrls(String, int)}.
     *
     * @param withMap Whether the index in the original text of every char should be kept.
     */
    private static Joined joinWrappedUrlRows(String text, int columns, boolean withMap) {
        if (columns <= 0 || text.indexOf('\n') < 0) return new Joined(text, null);

        final int length = text.length();
        StringBuilder out = new StringBuilder(length);
        int[] map = withMap ? new int[length + 1] : null;
        boolean joinedAny = false;

        int pos = 0;
        while (pos <= length) {
            int end = text.indexOf('\n', pos);
            if (end < 0) end = length;

            // The ranges of the original text to add: [from, to).
            List<int[]> parts = null;
            Row row = analyzeRow(text, pos, end, columns);
            int next = end + 1;

            if (row.reachesEdge && endsWithUrl(text, pos, row)) {
                int urlLength = row.contentEnd - pos;
                Row current = row;
                List<int[]> candidate = new ArrayList<>();
                candidate.add(new int[]{pos, row.contentEnd});
                int cursor = next;
                while (current.reachesEdge && cursor <= length && candidate.size() <= MAX_CONTINUATION_ROWS) {
                    int cursorEnd = text.indexOf('\n', cursor);
                    if (cursorEnd < 0) cursorEnd = length;
                    Row continuation = analyzeRow(text, cursor, cursorEnd, columns);
                    if (!isContinuation(text, continuation)) break;
                    urlLength += continuation.contentEnd - continuation.contentStart;
                    if (urlLength > MAX_JOINED_URL_LENGTH) break;
                    candidate.add(new int[]{continuation.contentStart, continuation.contentEnd});
                    current = continuation;
                    next = cursorEnd + 1;
                    cursor = next;
                }
                if (candidate.size() > 1) parts = candidate;
            }

            if (parts == null) {
                append(out, map, text, pos, end);
                next = end + 1;
            } else {
                joinedAny = true;
                for (int[] part : parts) append(out, map, text, part[0], part[1]);
            }
            if (next <= length || pos < length) {
                // Keep the row separator, also after the last row of a joined URL.
                if (map != null) map[out.length()] = Math.min(next - 1, length);
                out.append('\n');
            }
            pos = next;
        }

        if (!joinedAny) return new Joined(text, null);
        // The text does not end with a separator that the original text did not have.
        if (!text.endsWith("\n") && out.length() > 0 && out.charAt(out.length() - 1) == '\n') out.setLength(out.length() - 1);
        return new Joined(out.toString(), map);
    }

    private static void append(StringBuilder out, @Nullable int[] map, String text, int from, int to) {
        for (int i = from; i < to; i++) {
            if (map != null) map[out.length()] = i;
            out.append(text.charAt(i));
        }
    }

}
