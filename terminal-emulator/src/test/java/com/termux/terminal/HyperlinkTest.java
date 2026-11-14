package com.termux.terminal;

import java.util.List;

/** Tests for the `OSC 8` hyperlinks, see https://gist.github.com/egmontkob/eb114294efbcd5adb1944c9f3cb5feda */
public class HyperlinkTest extends TerminalTestCase {

    private static final String EXAMPLE = "https://example.org/a";
    private static final String CLOSE = "\033]8;;\033\\";

    private static String open(String uri) {
        return "\033]8;;" + uri + "\033\\";
    }

    /** The uri of the link at a cell of the current screen, or null. */
    private String uriAt(int row, int column) {
        TerminalHyperlink link = mTerminal.getScreen().getHyperlinkAt(row, column);
        return link == null ? null : link.getUri();
    }

    private void assertLinked(String uri, int row, int fromColumn, int toColumnExclusive) {
        for (int x = fromColumn; x < toColumnExclusive; x++) assertEquals("row=" + row + " column=" + x, uri, uriAt(row, x));
    }

    private void assertNotLinked(int row, int fromColumn, int toColumnExclusive) {
        assertLinked(null, row, fromColumn, toColumnExclusive);
    }

    public void testLabelAndTargetAreDifferent() {
        withTerminalSized(20, 3).enterString(open(EXAMPLE) + "click me" + CLOSE + " after");
        assertLinesAre("click me after      ", "                    ", "                    ");
        assertLinked(EXAMPLE, 0, 0, 8);
        assertNotLinked(0, 8, 20);
        assertNotLinked(1, 0, 20);
        assertNull(mTerminal.getCurrentHyperlink());
    }

    public void testBelTerminator() {
        withTerminalSized(20, 3).enterString("\033]8;;" + EXAMPLE + "\007ab\033]8;;\007c");
        assertLinked(EXAMPLE, 0, 0, 2);
        assertNotLinked(0, 2, 3);
    }

    public void testIdParameterAndUnknownParameters() {
        withTerminalSized(20, 3).enterString("\033]8;foo=bar:id=xyz:other=1;" + EXAMPLE + "\033\\a" + CLOSE);
        TerminalHyperlink link = mTerminal.getScreen().getHyperlinkAt(0, 0);
        assertNotNull(link);
        assertEquals("xyz", link.getId());
        assertEquals(EXAMPLE, link.getUri());
    }

    public void testSameLinkTwiceSharesOneEntry() {
        withTerminalSized(20, 3).enterString(open(EXAMPLE) + "a" + CLOSE + "-" + open(EXAMPLE) + "b" + CLOSE);
        assertSame(mTerminal.getScreen().getHyperlinkAt(0, 0), mTerminal.getScreen().getHyperlinkAt(0, 0));
        assertEquals(mTerminal.getScreen().getHyperlinkAt(0, 0), mTerminal.getScreen().getHyperlinkAt(0, 2));
        assertNull(mTerminal.getScreen().getHyperlinkAt(0, 1));
    }

    public void testNewLinkReplacesOpenLink() {
        withTerminalSized(20, 3).enterString(open(EXAMPLE) + "a" + open("https://example.org/b") + "b" + CLOSE);
        assertEquals(EXAMPLE, uriAt(0, 0));
        assertEquals("https://example.org/b", uriAt(0, 1));
    }

    public void testLinkSpansLinesAndAutoWrap() {
        withTerminalSized(5, 3).enterString(open(EXAMPLE) + "0123456789" + CLOSE);
        assertLinesAre("01234", "56789", "     ");
        assertLinked(EXAMPLE, 0, 0, 5);
        assertLinked(EXAMPLE, 1, 0, 5);
        assertNotLinked(2, 0, 5);
    }

    public void testMalformedAndRejectedCommandsKeepTheTextAndDoNotLink() {
        String[] bad = {
            "\033]8;https://example.org\007",                      // No second `;`.
            "\033]8;;javascript:alert(1)\007",                       // Scheme not allowed.
            "\033]8;;intent://x#Intent;scheme=http;end\007",
            "\033]8;;file:///etc/passwd\007",
            "\033]8;;content://com.example/x\007",
            "\033]8;;https://exa mple.org\007",                      // Space.
            "\033]8;;https://example.org/‮g\007",           // Bidi override.
            "\033]8;;http:\007",                                      // Nothing after the scheme.
            "\033]8;;://example.org\007",
            "\033]8;;" + "https://example.org/" + new String(new char[TerminalHyperlink.MAX_URI_LENGTH]).replace('\0', 'a') + "\007", // Too long.
        };
        for (String command : bad) {
            withTerminalSized(10, 2).enterString(open(EXAMPLE) + "a" + command + "bc" + CLOSE + "d");
            assertLineIs(0, "abcd      ");
            assertEquals(EXAMPLE, uriAt(0, 0));
            // The rejected link ends the previous one, so the text after it does not get it.
            assertNotLinked(0, 1, 10);
        }
    }

    public void testControlCharactersInTheUriAreRejected() {
        // C1 controls are dropped by the decoder of the terminal before they reach a sequence, so test the validation itself.
        assertNull(TerminalHyperlink.parse("", "https://example.org/\u0085x"));
        assertNull(TerminalHyperlink.parse("", "https://example.org/\u009bx"));
        assertNull(TerminalHyperlink.parse("", "https://example.org/\u0007x"));
        assertNull(TerminalHyperlink.parse("", "https://example.org/\u007fx"));
        assertNull(TerminalHyperlink.parse("", "https://example.org/\u2028x"));
        assertNull(TerminalHyperlink.parse("", "https://example.org/\u00a0x"));
    }

    public void testSchemesThatAreAccepted() {
        for (String uri : new String[]{"http://example.org", "HTTPS://example.org", "mailto:me@example.org", "ftp://example.org/x",
            "sftp://example.org/x", "ftps://example.org/x"}) {
            assertNotNull(uri, TerminalHyperlink.parse("", uri));
        }
        for (String uri : new String[]{"javascript:alert(1)", "tel:123", "sms:123", "data:text/plain,a", "market://details?id=a", "about:blank", "example.org"}) {
            assertNull(uri, TerminalHyperlink.parse("", uri));
        }
    }

    public void testUnicodePercentQueryAndFragmentArePreserved() {
        String uri = "https://example.org/pa%20th/(a)/ü/日本?q=a%26b&x=(1)#frag%C3%BC";
        withTerminalSized(20, 3).enterString(open(uri) + "x" + CLOSE);
        assertEquals(uri, uriAt(0, 0));
    }

    public void testNothingIsOpenedOrSentWhenReceived() {
        withTerminalSized(20, 3).enterString(open(EXAMPLE) + "x" + CLOSE);
        assertEquals("", mOutput.getOutputAndClear());
        assertEquals(0, mOutput.bellsRung);
        assertTrue(mOutput.titleChanges.isEmpty());
        assertTrue(mOutput.clipboardPuts.isEmpty());
    }

    public void testIncompleteCommandDoesNothing() {
        withTerminalSized(20, 3).enterString("\033]8;;" + EXAMPLE);
        assertNull(mTerminal.getCurrentHyperlink());
        // The next normal sequence terminates the string, it is not executed as a link with the following text.
        enterString("\033\\ab");
        assertEquals(EXAMPLE, mTerminal.getCurrentHyperlink().getUri());
        assertEquals(EXAMPLE, uriAt(0, 0));
    }

    public void testOversizedCommandIsNotExecuted() {
        StringBuilder huge = new StringBuilder("\033]8;;https://example.org/");
        for (int i = 0; i < 40000; i++) huge.append('a');
        withTerminalSized(20, 3).enterString(huge.toString()).enterString("\033\\");
        assertNull(mTerminal.getCurrentHyperlink());
        assertNull(uriAt(0, 0));
    }

    public void testOverwritingCellsRemovesTheLink() {
        withTerminalSized(10, 3).enterString(open(EXAMPLE) + "abcdef" + CLOSE);
        assertLinked(EXAMPLE, 0, 0, 6);
        enterString("\r12");
        assertLineIs(0, "12cdef    ");
        assertNotLinked(0, 0, 2);
        assertLinked(EXAMPLE, 0, 2, 6);
    }

    public void testOverwritingWithAnotherLink() {
        withTerminalSized(10, 3).enterString(open(EXAMPLE) + "abcd" + CLOSE + "\r" + open("https://example.org/b") + "XY" + CLOSE);
        assertEquals("https://example.org/b", uriAt(0, 0));
        assertEquals("https://example.org/b", uriAt(0, 1));
        assertEquals(EXAMPLE, uriAt(0, 2));
    }

    public void testErasingRemovesTheLink() {
        withTerminalSized(10, 3).enterString(open(EXAMPLE) + "abcdef" + CLOSE);
        // EL to the end of line from column 3.
        enterString("\033[1;4H\033[K");
        assertLinked(EXAMPLE, 0, 0, 3);
        assertNotLinked(0, 3, 10);
        // ECH two characters.
        enterString("\033[1;1H\033[2X");
        assertNotLinked(0, 0, 2);
        assertLinked(EXAMPLE, 0, 2, 3);
        // ED of the whole screen.
        enterString("\033[2J");
        assertNotLinked(0, 0, 10);
        assertTrue(mTerminal.getScreen().getHyperlinkSpans().isEmpty());
    }

    public void testErasingWithOpenLinkDoesNotCreateLinks() {
        withTerminalSized(10, 3).enterString(open(EXAMPLE) + "\033[2J\033[1;1H\033[K\033[1;1H\033[3X");
        assertNotLinked(0, 0, 10);
        assertNotNull(mTerminal.getCurrentHyperlink());
    }

    public void testInsertAndDeleteCharactersMoveTheLink() {
        withTerminalSized(10, 3).enterString("12" + open(EXAMPLE) + "ab" + CLOSE + "cd");
        assertLinked(EXAMPLE, 0, 2, 4);
        // ICH 2 at column 1: the link moves to the right with its text.
        enterString("\033[1;1H\033[2@");
        assertLineIs(0, "  12abcd  ");
        assertNotLinked(0, 0, 4);
        assertLinked(EXAMPLE, 0, 4, 6);
        assertNotLinked(0, 6, 10);
        // DCH 3 at column 1: moves back left.
        enterString("\033[1;1H\033[3P");
        assertLineIs(0, "2abcd     ");
        assertLinked(EXAMPLE, 0, 1, 3);
        assertNotLinked(0, 0, 1);
        assertNotLinked(0, 3, 10);
    }

    public void testInsertModeMovesTheLink() {
        withTerminalSized(10, 3).enterString(open(EXAMPLE) + "ab" + CLOSE + "\033[1;1H\033[4hXY");
        assertLineIs(0, "XYab      ");
        assertNotLinked(0, 0, 2);
        assertLinked(EXAMPLE, 0, 2, 4);
    }

    public void testCombiningCharacterKeepsTheLink() {
        withTerminalSized(10, 3).enterString(open(EXAMPLE) + "éx" + CLOSE);
        assertEquals(EXAMPLE, uriAt(0, 0));
        assertEquals(EXAMPLE, uriAt(0, 1));
        assertNull(uriAt(0, 2));
    }

    public void testWideCharacterCoversBothCellsAndOverwriteClearsBoth() {
        withTerminalSized(10, 3).enterString(open(EXAMPLE) + "日本" + CLOSE + "z");
        assertLinked(EXAMPLE, 0, 0, 4);
        assertNotLinked(0, 4, 10);
        // Overwrite the first half of the first wide character.
        enterString("\033[1;1Hx");
        assertNotLinked(0, 0, 2);
        assertLinked(EXAMPLE, 0, 2, 4);
        // Overwrite the second half of the second wide character.
        enterString("\033[1;4Hy");
        assertLinked(null, 0, 0, 2);
        assertNotLinked(0, 2, 4);
    }

    public void testWideCharacterOverwritingLinkedCellsRemovesTheirLinks() {
        withTerminalSized(10, 3).enterString(open(EXAMPLE) + "abcd" + CLOSE + "\033[1;2H日");
        assertEquals(EXAMPLE, uriAt(0, 0));
        assertNotLinked(0, 1, 3);
        assertEquals(EXAMPLE, uriAt(0, 3));
    }

    public void testScrollingKeepsLinksInTheTranscriptUntilTheyAreDropped() {
        withTerminalSized(10, 3);
        enterString(open(EXAMPLE) + "abc" + CLOSE + "\r\n\r\n\r\n");
        // Scrolled one line into the transcript.
        assertEquals(1, mTerminal.getScreen().getActiveTranscriptRows());
        assertEquals(EXAMPLE, uriAt(-1, 0));
        assertEquals(1, mTerminal.getScreen().getHyperlinkSpans().size());
        // The transcript of a terminal has at least the default number of rows (the one asked for is
        // ignored below 100): after enough lines the row is recycled without its link.
        for (int i = 0; i < TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS + 10; i++) enterString("\r\n");
        assertTrue(mTerminal.getScreen().getHyperlinkSpans().isEmpty());
        for (int row = -mTerminal.getScreen().getActiveTranscriptRows(); row < 3; row++) assertNotLinked(row, 0, 10);
    }

    public void testScrollRegionKeepsTheLinkWithItsLine() {
        withTerminalSized(10, 4).enterString("0\r\n" + open(EXAMPLE) + "link" + CLOSE + "\r\n2\r\n3");
        enterString("\033[2;4r\033[4;1H\n");
        // The scroll region 2..4 scrolled up by one: "link" left it, and (as for any line that scrolls
        // out above a region) went to the transcript, still with its link.
        assertLineIs(0, "0         ");
        assertLineIs(1, "2         ");
        assertEquals("link", mTerminal.getScreen().getSelectedText(0, -1, 9, -1));
        assertEquals(EXAMPLE, uriAt(-1, 0));
        assertLinked(EXAMPLE, -1, 0, 4);
        assertNotLinked(0, 0, 10);
        assertNotLinked(1, 0, 10);
        assertNotLinked(2, 0, 10);
        assertNotLinked(3, 0, 10);
        assertEquals(1, mTerminal.getScreen().getHyperlinkSpans().size());
    }

    public void testLinkOnScreenAfterScrollRegionKeepsPosition() {
        withTerminalSized(10, 4).enterString("0\r\n1\r\n" + open(EXAMPLE) + "link" + CLOSE + "\r\n3");
        enterString("\033[1;4r\033[4;1H\n");
        assertLineIs(1, "link      ");
        assertLinked(EXAMPLE, 1, 0, 4);
        assertNotLinked(2, 0, 10);
    }

    public void testAlternateScreenDoesNotShowOrKeepMainScreenLinks() {
        withTerminalSized(10, 3).enterString(open(EXAMPLE) + "main");
        assertNotNull(mTerminal.getCurrentHyperlink());
        // The open link is not applied to the text on the alternate screen.
        enterString("\033[?1049h" + "alt");
        assertNull(mTerminal.getCurrentHyperlink());
        assertTrue(mTerminal.isAlternateBufferActive());
        assertNotLinked(0, 0, 10);
        assertNotLinked(1, 0, 10);
        assertNotLinked(2, 0, 10);
        // Links on the alternate screen.
        enterString("\033[1;1H" + open("https://example.org/alt") + "alt" + CLOSE);
        assertLinked("https://example.org/alt", 0, 0, 3);
        // Back on the main screen: its link is still there and the alt link is not.
        enterString("\033[?1049l");
        assertLinked(EXAMPLE, 0, 0, 4);
        assertNotLinked(0, 4, 10);
        // A new alternate screen starts empty.
        enterString("\033[?1049h");
        assertNotLinked(0, 0, 10);
        assertTrue(mTerminal.getScreen().getHyperlinkSpans().isEmpty());
    }

    public void testResetEndsTheOpenLinkAndClearsTranscriptLinks() {
        withTerminalSized(10, 2).enterString(open(EXAMPLE) + "a\r\nb\r\nc\r\nd");
        assertNotNull(mTerminal.getCurrentHyperlink());
        assertFalse(mTerminal.getScreen().getHyperlinkSpans().isEmpty());
        enterString("\033c");
        assertNull(mTerminal.getCurrentHyperlink());
        assertTrue(mTerminal.getScreen().getHyperlinkSpans().isEmpty());
        enterString("x");
        assertNull(uriAt(0, 0));
    }

    public void testClearingTheTranscriptRemovesItsLinks() {
        withTerminalSized(10, 2).enterString(open(EXAMPLE) + "a" + CLOSE + "\r\n\r\n\r\n");
        assertEquals(EXAMPLE, uriAt(-2, 0));
        enterString("\033[3J");
        assertEquals(0, mTerminal.getScreen().getActiveTranscriptRows());
        assertTrue(mTerminal.getScreen().getHyperlinkSpans().isEmpty());
    }

    public void testReflowWhenResizingKeepsTheLinkOnItsText() {
        withTerminalSized(10, 4).enterString("go " + open(EXAMPLE) + "link-text" + CLOSE + " end");
        assertLinesAre("go link-te", "xt end    ", "          ", "          ");
        assertLinked(EXAMPLE, 0, 3, 10);
        assertLinked(EXAMPLE, 1, 0, 2);
        assertNotLinked(1, 2, 10);

        resize(20, 4);
        assertLineIs(0, "go link-text end    ");
        assertNotLinked(0, 0, 3);
        assertLinked(EXAMPLE, 0, 3, 12);
        assertNotLinked(0, 12, 20);

        resize(6, 4);
        // "go lin" "k-text" " end  "
        assertLineIs(0, "go lin");
        assertLineIs(1, "k-text");
        assertNotLinked(0, 0, 3);
        assertLinked(EXAMPLE, 0, 3, 6);
        assertLinked(EXAMPLE, 1, 0, 6);
        assertNotLinked(2, 0, 6);
    }

    public void testReflowOfWideCharacters() {
        withTerminalSized(6, 3).enterString("a" + open(EXAMPLE) + "日本" + CLOSE + "b");
        assertLinked(EXAMPLE, 0, 1, 5);
        resize(3, 4);
        // "a日" "本b"
        assertEquals(EXAMPLE, uriAt(0, 1));
        assertEquals(EXAMPLE, uriAt(0, 2));
        assertNull(uriAt(0, 0));
        assertEquals(EXAMPLE, uriAt(1, 0));
        assertEquals(EXAMPLE, uriAt(1, 1));
        assertNull(uriAt(1, 2));
    }

    public void testResizeOnlyRowsKeepsLinks() {
        withTerminalSized(10, 4).enterString("x\r\n" + open(EXAMPLE) + "abc" + CLOSE);
        resize(10, 6);
        assertLinked(EXAMPLE, 1, 0, 3);
        resize(10, 3);
        assertEquals(EXAMPLE, uriAt(mTerminal.getCursorRow(), 0));
    }

    public void testSpansAndLabels() {
        withTerminalSized(30, 6).enterString("a " + open(EXAMPLE) + "first" + CLOSE + " b\r\n" + open("https://example.org/b") + "second" + CLOSE);
        List<TerminalHyperlink.Span> spans = mTerminal.getScreen().getHyperlinkSpans();
        assertEquals(2, spans.size());
        assertEquals(EXAMPLE, spans.get(0).link.getUri());
        assertEquals("first", spans.get(0).label);
        assertEquals(0, spans.get(0).startRow);
        assertEquals(2, spans.get(0).startColumn);
        assertEquals("https://example.org/b", spans.get(1).link.getUri());
        assertEquals("second", spans.get(1).label);
        assertEquals("first", mTerminal.getScreen().getHyperlinkLabelAt(0, 3));
        assertNull(mTerminal.getScreen().getHyperlinkLabelAt(0, 0));
        assertNull(mTerminal.getScreen().getHyperlinkLabelAt(5, 0));
        assertNull(mTerminal.getScreen().getHyperlinkLabelAt(0, 100));
        assertNull(mTerminal.getScreen().getHyperlinkAt(-5, 0));
        assertNull(mTerminal.getScreen().getHyperlinkAt(99, 0));
    }

    /** An application that wraps long links itself, one link per row with indentation (like claude code). */
    public void testLinkWrappedByTheApplicationIsOneSpan() {
        String uri = "https://example.org/a/very/long/path?query=1";
        withTerminalSized(40, 6).enterString("  " + open(uri) + "https://example.org/a/very/lo" + CLOSE + "\r\n  " + open(uri) + "ng/path?query=1" + CLOSE);
        List<TerminalHyperlink.Span> spans = mTerminal.getScreen().getHyperlinkSpans();
        assertEquals(1, spans.size());
        assertEquals("https://example.org/a/very/long/path?query=1", spans.get(0).label);
        assertFalse(spans.get(0).link.isLabelConcealingTarget(spans.get(0).label));
        assertEquals(spans.get(0).label, mTerminal.getScreen().getHyperlinkLabelAt(1, 5));
        assertEquals(spans.get(0).label, mTerminal.getScreen().getHyperlinkLabelAt(0, 5));
    }

    public void testLinksDoNotChangeTheTextForCopying() {
        withTerminalSized(20, 3).enterString("a " + open(EXAMPLE) + "label" + CLOSE + " b");
        assertEquals("a label b", mTerminal.getScreen().getSelectedText(0, 0, 19, 0));
    }

    public void testManyDistinctLinksStayBounded() {
        // A screen with more cells than the max number of links, no history.
        int columns = 200, rows = 60;
        mTerminal = new TerminalEmulator(mOutput, columns, rows, INITIAL_CELL_WIDTH_PIXELS, INITIAL_CELL_HEIGHT_PIXELS, rows, null);
        int total = TerminalHyperlinkRegistry.MAX_LINKS + 500;
        assertTrue(total < columns * rows);
        StringBuilder input = new StringBuilder();
        for (int i = 0; i < total; i++) input.append(open("https://example.org/" + i)).append('x');
        enterString(input.toString());

        // The links that fit are correct, the rest has no link, and none is wrong.
        for (int i = 0; i < total; i++) {
            String uri = uriAt(i / columns, i % columns);
            if (i < TerminalHyperlinkRegistry.MAX_LINKS) {
                assertEquals("cell " + i, "https://example.org/" + i, uri);
            } else {
                assertNull("cell " + i, uri);
            }
        }

        // When the old cells go away there is room for new links again. The stale entries are released by a
        // scan that is retried at most every 256 failed attempts, so a few links might still be without a link.
        enterString("\033[2J\033[1;1H");
        StringBuilder more = new StringBuilder();
        for (int i = 0; i < 300; i++) more.append(open("https://example.org/new" + i)).append('n');
        enterString(more.toString());
        assertEquals("https://example.org/new299", uriAt(1, 99));
    }

    public void testManyLinksScrollingThroughTheTranscript() {
        withTerminalSized(80, 24);
        StringBuilder input = new StringBuilder();
        int total = 3 * TerminalHyperlinkRegistry.MAX_LINKS;
        for (int i = 0; i < total; i++) {
            input.append(open("https://example.org/" + i)).append("x").append(CLOSE);
            // One link per row, so that the rows leave the transcript (2000 rows) and their links are released.
            input.append("\r\n");
        }
        enterString(input.toString());
        // The latest links are still correct and the old ones were released while scrolling.
        List<TerminalHyperlink.Span> spans = mTerminal.getScreen().getHyperlinkSpans();
        assertFalse(spans.isEmpty());
        // All the 2000 rows of the buffer, except the last one that is empty, still have their link.
        assertEquals(TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS - 1, spans.size());
        assertTrue(spans.size() <= TerminalBuffer.MAX_HYPERLINK_SPANS);
        for (TerminalHyperlink.Span span : spans) {
            int n = Integer.parseInt(span.link.getUri().substring("https://example.org/".length()));
            assertEquals("x", span.label);
            assertTrue(n < total);
        }
        TerminalHyperlink.Span last = spans.get(spans.size() - 1);
        assertEquals("https://example.org/" + (total - 1), last.link.getUri());
    }

    public void testLabelConcealingTarget() {
        TerminalHyperlink link = TerminalHyperlink.parse("", "https://example.org/path");
        assertFalse(link.isLabelConcealingTarget("https://example.org/path"));
        assertFalse(link.isLabelConcealingTarget("example.org/path"));
        assertFalse(link.isLabelConcealingTarget("  https://example.org/ path \n"));
        assertFalse(TerminalHyperlink.parse("", "https://example.org/").isLabelConcealingTarget("https://example.org"));
        assertFalse(TerminalHyperlink.parse("", "mailto:me@example.org").isLabelConcealingTarget("me@example.org"));
        assertTrue(link.isLabelConcealingTarget("click here"));
        assertTrue(link.isLabelConcealingTarget("https://example.com/path"));
        assertTrue(link.isLabelConcealingTarget("example.org"));
        assertTrue(link.isLabelConcealingTarget("https://example.org/path/more"));
        assertTrue(link.isLabelConcealingTarget(""));
        assertTrue(link.isLabelConcealingTarget("   "));
        assertTrue(link.isLabelConcealingTarget(null));
    }

}
