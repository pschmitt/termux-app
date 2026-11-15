package dev.pschmitt.termux.shared.termux.data;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Tests for finding URLs in terminal text, in particular URLs that an application has broken over
 * several rows and indented. The documented rules are in {@code docs/en/urls-and-hyperlinks.md}.
 */
public class TermuxUrlUtilsTest {

    /** A long OAuth URL like the ones that are printed by CLI tools (all values are made up). */
    private static final String OAUTH_URL = "https://claude.ai/oauth/authorize?code=true"
        + "&client_id=00000000-0000-4000-8000-000000000000&response_type=code"
        + "&redirect_uri=https%3A%2F%2Fexample.org%2Foauth%2Fcode%2Fcallback"
        + "&scope=org%3Acreate_api_key+user%3Aprofile+user%3Ainference"
        + "&code_challenge=AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-AbCdE&code_challenge_method=S256"
        + "&state=Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MA";

    private static void assertUrlsAre(String text, int columns, String... urls) {
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        Collections.addAll(expected, urls);
        LinkedHashSet<String> actual = new LinkedHashSet<>();
        for (CharSequence url : TermuxUrlUtils.extractUrls(text, columns)) actual.add(url.toString());
        Assert.assertEquals(expected, actual);
    }

    /**
     * Break a URL like an application that wraps its own output does: rows of exactly {@code width}
     * columns, each prefixed with {@code indent}, the last one shorter. Rows are separated by `\n`.
     */
    private static List<String> wrapRows(String url, int width, String indent) {
        List<String> rows = new ArrayList<>();
        int chunk = width - indent.length();
        for (int i = 0; i < url.length(); i += chunk) {
            rows.add(indent + url.substring(i, Math.min(url.length(), i + chunk)));
        }
        return rows;
    }

    /** Like {@link #wrapRows} but drawn inside a box, like TUIs do: {@code │ text      │}. */
    private static List<String> boxRows(String url, int width) {
        List<String> rows = new ArrayList<>();
        int inner = width - 4;
        for (int i = 0; i < url.length(); i += inner) {
            StringBuilder row = new StringBuilder("│ ");
            String chunk = url.substring(i, Math.min(url.length(), i + inner));
            row.append(chunk);
            for (int j = chunk.length(); j < inner; j++) row.append(' ');
            row.append(" │");
            rows.add(row.toString());
        }
        return rows;
    }

    private static String rep(String s, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append(s);
        return sb.toString();
    }

    /** The index in {@code text} of the {@code n}th (zero based) character that is not whitespace, from {@code from}. */
    private static int offsetOfNonWhitespace(String text, int from, int n) {
        for (int i = from; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) continue;
            if (n-- == 0) return i;
        }
        throw new IllegalArgumentException("n too large");
    }

    private static String join(List<String> rows) {
        StringBuilder sb = new StringBuilder();
        for (String row : rows) sb.append(row).append('\n');
        return sb.toString();
    }

    // The behavior for text that has no application wrapped URL, which has to stay the same.

    @Test
    public void testPlainUrlsAreUnchanged() {
        assertUrlsAre("hello http://example.com world", 80, "http://example.com");
        assertUrlsAre("http://example.com\nhttp://another.com", 80, "http://example.com", "http://another.com");
        assertUrlsAre("hello https://example.com/#bar https://example.com/foo#bar", 80,
            "https://example.com/#bar", "https://example.com/foo#bar");
        assertUrlsAre("", 80);
        assertUrlsAre("no urls here\nat all", 80);
    }

    @Test
    public void testSingleArgumentDoesNotJoinRows() {
        List<String> rows = wrapRows(OAUTH_URL, 60, "  ");
        LinkedHashSet<CharSequence> urls = TermuxUrlUtils.extractUrls(join(rows));
        Assert.assertEquals(1, urls.size());
        Assert.assertEquals(rows.get(0).trim(), urls.iterator().next().toString());
    }

    @Test
    public void testColumnsZeroDoesNotJoinRows() {
        assertUrlsAre(join(wrapRows(OAUTH_URL, 60, "  ")), 0, wrapRows(OAUTH_URL, 60, "  ").get(0).trim());
    }

    @Test
    public void testNullText() {
        Assert.assertTrue(TermuxUrlUtils.extractUrls(null, 80).isEmpty());
        Assert.assertTrue(TermuxUrlUtils.extractUrls(null).isEmpty());
    }

    // Wrapped by the application.

    @Test
    public void testIndentedWithTwoSpaces() {
        String text = "Browser didn't open? Use the url below to sign in:\n\n"
            + join(wrapRows(OAUTH_URL, 60, "  "))
            + "\nPaste code here if prompted > ";
        assertUrlsAre(text, 60, OAUTH_URL);
    }

    @Test
    public void testIndentedWithFourSpacesAndWideTerminal() {
        assertUrlsAre("Open:\n" + join(wrapRows(OAUTH_URL, 80, "    ")), 80, OAUTH_URL);
    }

    @Test
    public void testIndentedWithTab() {
        assertUrlsAre("Open:\n" + join(wrapRows(OAUTH_URL, 60, "\t")), 60, OAUTH_URL);
    }

    @Test
    public void testIndentedWithMixedTabsAndSpaces() {
        assertUrlsAre("Open:\n" + join(wrapRows(OAUTH_URL, 60, " \t ")), 60, OAUTH_URL);
    }

    @Test
    public void testWrappedWithoutIndentation() {
        assertUrlsAre("Open:\n" + join(wrapRows(OAUTH_URL, 60, "")), 60, OAUTH_URL);
    }

    @Test
    public void testWrappedAfterALabelOnTheFirstRow() {
        String url = OAUTH_URL;
        String first = "Visit: " + url.substring(0, 53);
        StringBuilder text = new StringBuilder(first).append('\n');
        for (String row : wrapRows(url.substring(53), 60, "       ")) text.append(row).append('\n');
        assertUrlsAre(text.toString(), 60, url);
    }

    @Test
    public void testWrapperLeavesAColumnAtTheEdge() {
        // Some applications break one or two columns before the edge.
        String url = "https://example.org/" + rep("a", 100);
        List<String> rows = wrapRows(url, 58, "  ");
        assertUrlsAre(join(rows), 60, url);
    }

    @Test
    public void testInsideABox() {
        String text = "╭" + rep("\u2500", 58) + "╮\n" + join(boxRows(OAUTH_URL, 60))
            + "╰" + rep("\u2500", 58) + "╯\n";
        assertUrlsAre(text, 60, OAUTH_URL);
    }

    @Test
    public void testInsideABoxDoesNotIncludeTheBorder() {
        for (CharSequence url : TermuxUrlUtils.extractUrls(join(boxRows(OAUTH_URL, 60)), 60)) {
            Assert.assertFalse(url.toString().contains("│"));
            Assert.assertFalse(url.toString().contains(" "));
        }
    }

    @Test
    public void testCarriageReturnsAndTrailingWhitespace() {
        StringBuilder text = new StringBuilder();
        for (String row : wrapRows(OAUTH_URL, 60, "  ")) text.append(row).append(" \r\n");
        assertUrlsAre(text.toString(), 60, OAUTH_URL);
    }

    @Test
    public void testTwoWrappedUrlsInARow() {
        String second = "https://example.net/second/" + rep("b", 90) + "?x=1";
        String text = join(wrapRows(OAUTH_URL, 60, "  ")) + "\n" + join(wrapRows(second, 60, "  "));
        assertUrlsAre(text, 60, OAUTH_URL, second);
    }

    @Test
    public void testWrappedUrlFollowedByText() {
        String text = join(wrapRows(OAUTH_URL, 60, "  ")) + "Paste the code here:\n";
        assertUrlsAre(text, 60, OAUTH_URL);
    }

    @Test
    public void testWrappedUrlWithoutTrailingNewline() {
        String text = join(wrapRows(OAUTH_URL, 60, "  "));
        assertUrlsAre(text.substring(0, text.length() - 1), 60, OAUTH_URL);
    }

    // Text that must not become part of a URL.

    @Test
    public void testShortUrlIsNotJoinedWithTheNextRow() {
        assertUrlsAre("see https://example.com/a\n  continued", 60, "https://example.com/a");
    }

    @Test
    public void testUrlAtTheEdgeIsNotJoinedWithProse() {
        String head = "https://example.org/" + rep("a", 40);
        // The URL fills the row, and the next row is a sentence.
        assertUrlsAre(head + "\n  and then some words here\n", head.length(), head);
        assertUrlsAre(head + "\nthis is not a url\n", head.length(), head);
    }

    @Test
    public void testNextRowWithMoreThanOneTokenIsNotAContinuation() {
        String head = "https://example.org/" + rep("a", 40);
        assertUrlsAre(head + "\n  tail more\n", head.length(), head);
        assertUrlsAre(head + "\n  tail\tmore\n", head.length(), head);
    }

    @Test
    public void testNextRowStartingWithASchemeIsAnotherUrl() {
        String head = "https://example.org/" + rep("a", 40);
        String other = "https://example.net/b";
        assertUrlsAre(head + "\n  " + other + "\n", head.length(), head, other);
    }

    @Test
    public void testNextRowAfterAnEmptyRowIsNotAContinuation() {
        String head = "https://example.org/" + rep("a", 40);
        assertUrlsAre(head + "\n\n  tail\n", head.length(), head);
    }

    @Test
    public void testNextRowOfOnlyPunctuationIsNotAContinuation() {
        String head = "https://example.org/" + rep("a", 40);
        assertUrlsAre(head + "\n  ---\n", head.length(), head);
        assertUrlsAre(head + "\n  ...\n", head.length(), head);
    }

    @Test
    public void testPromptCharactersAreNotAContinuation() {
        String head = "https://example.org/" + rep("a", 40);
        // A shell prompt or a list marker is no URL character.
        assertUrlsAre(head + "\n  > tail\n", head.length(), head);
        assertUrlsAre(head + "\n  * tail\n", head.length(), head);
    }

    @Test
    public void testHeadThatIsNotTheEndOfTheRowIsNotJoined() {
        String head = "https://example.org/" + rep("a", 20);
        // The row ends with text after the URL.
        assertUrlsAre(head + " tail words at the edge\n  next\n", head.length() + 11, head);
    }

    @Test
    public void testNextRowWithAnotherIndentBlockIsStillOneToken() {
        // A deeper indent is fine, since only whitespace is skipped.
        String head = "https://example.org/" + rep("a", 40);
        assertUrlsAre(head + "\n            tail\n", head.length(), head + "tail");
    }

    @Test
    public void testNonUrlCharactersEndAContinuation() {
        String head = "https://example.org/" + rep("a", 40);
        // Quotes and angle brackets are not URL characters.
        assertUrlsAre(head + "\n  <tail>\n", head.length(), head);
    }

    // Limits.

    @Test
    public void testNumberOfJoinedRowsIsLimited() {
        String url = "https://example.org/" + rep("a", 20000);
        int columns = 60;
        LinkedHashSet<CharSequence> urls = TermuxUrlUtils.extractUrls(join(wrapRows(url, columns, "  ")), columns);
        Assert.assertEquals(1, urls.size());
        int length = urls.iterator().next().length();
        Assert.assertTrue("length " + length, length <= TermuxUrlUtils.MAX_JOINED_URL_LENGTH);
        Assert.assertTrue("length " + length, length < (TermuxUrlUtils.MAX_CONTINUATION_ROWS + 2) * columns);
        Assert.assertTrue("length " + length, length > 2000);
    }

    @Test
    public void testJoinedLengthIsLimited() {
        String url = "https://example.org/" + rep("a", 20000);
        int columns = 4000;
        LinkedHashSet<CharSequence> urls = TermuxUrlUtils.extractUrls(join(wrapRows(url, columns, "  ")), columns);
        Assert.assertEquals(1, urls.size());
        Assert.assertTrue(urls.iterator().next().length() <= TermuxUrlUtils.MAX_JOINED_URL_LENGTH);
    }

    @Test
    public void testManyRowsOfText() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 20000; i++) text.append("  some line of output number ").append(i).append('\n');
        assertUrlsAre(text.toString(), 80);
    }

    @Test
    public void testManyUrlLikeRowsAreLinear() {
        // Many full width rows that are all a URL, none is joined with unrelated rows.
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 2000; i++) text.append("https://example.org/").append(String.format("%04d", i)).append("\n");
        LinkedHashSet<CharSequence> urls = TermuxUrlUtils.extractUrls(text.toString(), 24);
        Assert.assertEquals(2000, urls.size());
    }

    // The URL itself.

    @Test
    public void testPercentEncodingQueryFragmentAndParenthesesArePreserved() {
        String url = "https://example.org/wiki/Foo_(bar)/caf%C3%A9?q=a%20b&r=%E2%9C%93&s=x+y#sec-1.2";
        assertUrlsAre("see " + url + " now", 120, url);
        assertUrlsAre(join(wrapRows(url, 30, "  ")), 30, url);
    }

    @Test
    public void testUnicodeIsPreserved() {
        String url = "https://example.org/日本語/café?q=über#å";
        assertUrlsAre("see " + url, 120, url);
        assertUrlsAre("https://bücher.example/path", 120, "https://bücher.example/path");
    }

    @Test
    public void testUserInfoPortAndIpAddress() {
        assertUrlsAre("ssh via https://user:pw@example.org:8443/x and http://192.168.0.1:8080/a", 120,
            "https://user:pw@example.org:8443/x", "http://192.168.0.1:8080/a");
    }

    @Test
    public void testTrailingPunctuationIsRemoved() {
        assertUrlsAre("Go to https://example.com/a, then https://example.org/b.", 120,
            "https://example.com/a", "https://example.org/b");
        assertUrlsAre("(see https://example.com/a)", 120, "https://example.com/a");
        assertUrlsAre("is it https://example.com/a?", 120, "https://example.com/a");
        assertUrlsAre("\"https://example.com/a\"", 120, "https://example.com/a");
        assertUrlsAre("'https://example.com/a'.", 120, "https://example.com/a");
        assertUrlsAre("wow https://example.com/a!!", 120, "https://example.com/a");
    }

    @Test
    public void testBalancedParenthesesAtTheEndAreKept() {
        assertUrlsAre("https://example.org/Foo_(bar)", 120, "https://example.org/Foo_(bar)");
        assertUrlsAre("(https://example.org/Foo_(bar))", 120, "https://example.org/Foo_(bar)");
        assertUrlsAre("(https://example.org/Foo_(bar)).", 120, "https://example.org/Foo_(bar)");
    }

    @Test
    public void testTrimTrailingPunctuation() {
        String text = "https://example.org/a.,;:!?";
        Assert.assertEquals("https://example.org/a".length(), TermuxUrlUtils.trimTrailingPunctuation(text, 0, text.length()));
        Assert.assertEquals(0, TermuxUrlUtils.trimTrailingPunctuation("...", 0, 3));
    }

    // Finding the URL at a position.

    @Test
    public void testFindUrlAtOffsetOfAWrappedUrl() {
        String text = "Open this:\n" + join(wrapRows(OAUTH_URL, 60, "  ")) + "\nDone.";
        int start = text.indexOf("https://");
        // The first, a middle and the last character of the URL, on different rows.
        Assert.assertEquals(OAUTH_URL, TermuxUrlUtils.findUrlAtOffset(text, 60, start));
        int middle = OAUTH_URL.indexOf("code_challenge_method") + 3;
        Assert.assertEquals(OAUTH_URL, TermuxUrlUtils.findUrlAtOffset(text, 60, offsetOfNonWhitespace(text, start, middle)));
        Assert.assertEquals(OAUTH_URL, TermuxUrlUtils.findUrlAtOffset(text, 60, offsetOfNonWhitespace(text, start, OAUTH_URL.length() - 1)));
    }

    @Test
    public void testFindUrlAtOffsetIsNullOutsideOfAUrl() {
        String text = "Open this:\n" + join(wrapRows(OAUTH_URL, 60, "  ")) + "\nDone.";
        Assert.assertNull(TermuxUrlUtils.findUrlAtOffset(text, 60, 0));
        Assert.assertNull(TermuxUrlUtils.findUrlAtOffset(text, 60, text.indexOf("Done.")));
        // The indentation of a continuation row and the newline are not part of the URL.
        int secondRow = text.indexOf('\n', text.indexOf("https://")) + 1;
        Assert.assertNull(TermuxUrlUtils.findUrlAtOffset(text, 60, secondRow));
        Assert.assertNull(TermuxUrlUtils.findUrlAtOffset(text, 60, secondRow - 1));
        Assert.assertNull(TermuxUrlUtils.findUrlAtOffset(text, 60, -1));
        Assert.assertNull(TermuxUrlUtils.findUrlAtOffset(text, 60, text.length()));
    }

    @Test
    public void testFindUrlAtOffsetOfAPlainUrl() {
        String text = "x https://example.org/a y";
        Assert.assertEquals("https://example.org/a", TermuxUrlUtils.findUrlAtOffset(text, 80, 5));
        Assert.assertNull(TermuxUrlUtils.findUrlAtOffset(text, 80, 0));
        Assert.assertNull(TermuxUrlUtils.findUrlAtOffset(text, 80, text.length() - 1));
    }

    @Test
    public void testFindUrlAtOffsetDoesNotIncludeTrailingPunctuation() {
        String text = "see https://example.org/a.";
        Assert.assertEquals("https://example.org/a", TermuxUrlUtils.findUrlAtOffset(text, 80, 10));
        Assert.assertNull(TermuxUrlUtils.findUrlAtOffset(text, 80, text.length() - 1));
    }

    @Test
    public void testFindUrlAtOffsetPicksTheRightOfTwoUrls() {
        String second = "https://example.net/second/" + rep("b", 90);
        String text = join(wrapRows(OAUTH_URL, 60, "  ")) + join(wrapRows(second, 60, "  "));
        Assert.assertEquals(second, TermuxUrlUtils.findUrlAtOffset(text, 60, text.indexOf("example.net") + 2));
        Assert.assertEquals(OAUTH_URL, TermuxUrlUtils.findUrlAtOffset(text, 60, text.indexOf("claude.ai") + 2));
    }

    @Test
    public void testRowsThatAreNotJoinedKeepTheirOffsets() {
        String text = "first row\nsecond row https://example.org/x\nthird row";
        int offset = text.indexOf("example.org");
        Assert.assertEquals("https://example.org/x", TermuxUrlUtils.findUrlAtOffset(text, 80, offset));
        Assert.assertNull(TermuxUrlUtils.findUrlAtOffset(text, 80, text.indexOf("third")));
    }

}
