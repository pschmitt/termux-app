package com.termux.terminal;

/**
 * <pre>
 * "CSI ? Pm h", DEC Private Mode Set (DECSET)
 * </pre>
 * <p/>
 * and
 * <p/>
 * <pre>
 * "CSI ? Pm l", DEC Private Mode Reset (DECRST)
 * </pre>
 * <p/>
 * controls various aspects of the terminal
 */
public class DecSetTest extends TerminalTestCase {

	/** DECSET 25, DECTCEM, controls visibility of the cursor. */
	public void testEnableDisableCursor() {
		withTerminalSized(3, 3);
		assertTrue("Initially the cursor should be enabled", mTerminal.isCursorEnabled());
		enterString("\033[?25l"); // Disable Cursor (DECTCEM).
		assertFalse(mTerminal.isCursorEnabled());
		enterString("\033[?25h"); // Enable Cursor (DECTCEM).
		assertTrue(mTerminal.isCursorEnabled());

		enterString("\033[?25l"); // Disable Cursor (DECTCEM), again.
		assertFalse(mTerminal.isCursorEnabled());
		mTerminal.reset();
		assertTrue("Resetting the terminal should enable the cursor", mTerminal.isCursorEnabled());

		enterString("\033[?25l");
		assertFalse(mTerminal.isCursorEnabled());
		enterString("\033c"); // RIS resetting should enabled cursor.
		assertTrue(mTerminal.isCursorEnabled());
	}

	/** DECSET 2004, controls bracketed paste mode. */
	public void testBracketedPasteMode() {
		withTerminalSized(3, 3);

		mTerminal.paste("a");
		assertEquals("Pasting 'a' should output 'a' when bracketed paste mode is disabled", "a", mOutput.getOutputAndClear());

		enterString("\033[?2004h"); // Enable bracketed paste mode.
		mTerminal.paste("a");
		assertEquals("Pasting when in bracketed paste mode should be bracketed", "\033[200~a\033[201~", mOutput.getOutputAndClear());

		enterString("\033[?2004l"); // Disable bracketed paste mode.
		mTerminal.paste("a");
		assertEquals("Pasting 'a' should output 'a' when bracketed paste mode is disabled", "a", mOutput.getOutputAndClear());

		enterString("\033[?2004h"); // Enable bracketed paste mode, again.
		mTerminal.paste("a");
		assertEquals("Pasting when in bracketed paste mode again should be bracketed", "\033[200~a\033[201~", mOutput.getOutputAndClear());

		mTerminal.paste("\033ab\033cd\033");
		assertEquals("Pasting an escape character should not input it", "\033[200~abcd\033[201~", mOutput.getOutputAndClear());
		mTerminal.paste("\u0081ab\u0081cd\u009F");
		assertEquals("Pasting C1 control codes should not input it", "\033[200~abcd\033[201~", mOutput.getOutputAndClear());

		mTerminal.reset();
		mTerminal.paste("a");
		assertEquals("Terminal reset() should disable bracketed paste mode", "a", mOutput.getOutputAndClear());
	}

	/** DECSET 7, DECAWM, controls wraparound mode. */
	public void testWrapAroundMode() {
		// Default with wraparound:
		withTerminalSized(3, 3).enterString("abcd").assertLinesAre("abc", "d  ", "   ");
		// With wraparound disabled:
		withTerminalSized(3, 3).enterString("\033[?7labcd").assertLinesAre("abd", "   ", "   ");
		enterString("efg").assertLinesAre("abg", "   ", "   ");
		// Re-enabling wraparound:
		enterString("\033[?7hhij").assertLinesAre("abh", "ij ", "   ");
	}

	public void testSynchronizedOutputMode2026() {
		withTerminalSized(10, 4);
		assertEnteringStringGivesResponse("\033[?2026$p", "\033[?2026;2$y");
		assertEquals(0L, mTerminal.getDeferredScreenUpdateDelayMs());

		enterString("\033[?2026hHello");
		assertTrue(mTerminal.isSynchronizedOutput());
		long delayMs = mTerminal.getDeferredScreenUpdateDelayMs();
		assertTrue(delayMs > 0L && delayMs <= 100L);
		assertLinesAre("Hello     ", "          ", "          ", "          ");
		assertEnteringStringGivesResponse("\033[?2026$p", "\033[?2026;1$y");
		enterString("\033[?1049hAlt\033[?1049l");
		assertTrue(mTerminal.isSynchronizedOutput());

		enterString("\033[?2026l");
		assertFalse(mTerminal.isSynchronizedOutput());
		assertEquals(0L, mTerminal.getDeferredScreenUpdateDelayMs());
		assertEnteringStringGivesResponse("\033[?2026$p", "\033[?2026;2$y");

		enterString("\033[?2026h\033[?2026s\033[?2026l\033[?2026r");
		assertTrue(mTerminal.isSynchronizedOutput());
		enterString("\033[!p");
		assertFalse(mTerminal.isSynchronizedOutput());
		assertEquals(0L, mTerminal.mSyncOutputStartNs);
		enterString("\033[?2026h\033c");
		assertFalse(mTerminal.isSynchronizedOutput());
		assertEquals(0L, mTerminal.mSyncOutputStartNs);
	}

	public void testSynchronizedOutputTimeout() {
		withTerminalSized(10, 4);
		enterString("\033[?2026h");
		long startNs = mTerminal.mSyncOutputStartNs;
		enterString("\033[?2026h");
		assertEquals("Repeated enable must not extend the hold", startNs, mTerminal.mSyncOutputStartNs);
		mTerminal.mSyncOutputStartNs = System.nanoTime() - TerminalEmulator.SYNC_OUTPUT_MAX_HOLD_NS - 1_000_000L;
		assertEquals(0L, mTerminal.getDeferredScreenUpdateDelayMs());
		assertFalse(mTerminal.isSynchronizedOutput());
		assertEnteringStringGivesResponse("\033[?2026$p", "\033[?2026;2$y");
		enterString("\033[?2026h");
		assertTrue(mTerminal.isSynchronizedOutput());
		mTerminal.reset();
		assertEquals(0L, mTerminal.getDeferredScreenUpdateDelayMs());
		assertEquals(0L, mTerminal.mSyncOutputStartNs);
	}

}
