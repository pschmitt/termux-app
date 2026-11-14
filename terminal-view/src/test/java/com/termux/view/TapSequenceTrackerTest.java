package com.termux.view;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TapSequenceTrackerTest {

    private static final long TAP_MAX_MS = 400;
    private static final long INTER_TAP_MS = 300;
    private static final float TAP_SLOP = 8f;
    private static final float SEQUENCE_SLOP = 100f;

    private TapSequenceTracker mTracker;

    @Before
    public void setUp() {
        mTracker = new TapSequenceTracker(TAP_MAX_MS, INTER_TAP_MS, TAP_SLOP, SEQUENCE_SLOP);
    }

    /** Presses at {@code downTime}, releases {@code holdMs} later and returns the reported tap number. */
    private int tap(long downTime, long holdMs, float x, float y) {
        mTracker.onDown(downTime, x, y);
        return mTracker.onUp(downTime + holdMs, x, y);
    }

    @Test
    public void singleTapIsOne() {
        assertEquals(1, tap(1000, 60, 50, 50));
    }

    @Test
    public void fastTapsCountUp() {
        assertEquals(1, tap(1000, 60, 50, 50));
        assertEquals(2, tap(1150, 60, 50, 50));
        assertEquals(3, tap(1300, 60, 52, 49));
    }

    @Test
    public void slowSecondTapStartsNewSequence() {
        assertEquals(1, tap(1000, 60, 50, 50));
        // 350ms after the first press: the platform has already delivered a confirmed single tap.
        assertEquals(1, tap(1350, 60, 50, 50));
    }

    @Test
    public void timeoutIsMeasuredFromPreviousPress() {
        // Pressed for 150ms, so the next press 280ms after the release is 430ms after the press.
        assertEquals(1, tap(1000, 150, 50, 50));
        assertEquals(1, tap(1430, 60, 50, 50));
        // The same gap fits when it is within the timeout of the press.
        mTracker.cancel();
        assertEquals(1, tap(5000, 150, 50, 50));
        assertEquals(2, tap(5300, 60, 50, 50));
    }

    @Test
    public void bouncingSecondPressIsNotACountedTap() {
        // A press less than 40ms after the release is contact bounce, like the platform ignores.
        assertEquals(1, tap(1000, 60, 50, 50));
        assertEquals(1, tap(1070, 60, 50, 50));
    }

    @Test
    public void distantSecondTapStartsNewSequence() {
        assertEquals(1, tap(1000, 60, 50, 50));
        assertEquals(1, tap(1150, 60, 50 + SEQUENCE_SLOP + 20, 50));
        assertEquals(2, tap(1300, 60, 50 + SEQUENCE_SLOP + 20, 50));
    }

    @Test
    public void longPressIsNotATapAndEndsSequence() {
        assertEquals(1, tap(1000, 60, 50, 50));
        assertEquals(0, tap(1150, TAP_MAX_MS + 1, 50, 50));
        assertEquals(1, tap(1700, 60, 50, 50));
        // And a tap right after the long press does not continue the first sequence either.
        mTracker.cancel();
        assertEquals(1, tap(3000, 60, 50, 50));
    }

    @Test
    public void dragBeyondTouchSlopIsNotATapAndCancelsSequence() {
        assertEquals(1, tap(1000, 60, 50, 50));
        mTracker.onDown(1150, 50, 50);
        mTracker.onMove(50, 50 + TAP_SLOP + 1);
        assertEquals(0, mTracker.onUp(1200, 50, 50));
        assertEquals(0, mTracker.getCount());
        assertEquals(1, tap(1300, 60, 50, 50));
    }

    @Test
    public void releaseOutsideSlopIsNotATap() {
        mTracker.onDown(1000, 50, 50);
        assertEquals(0, mTracker.onUp(1060, 50, 50 + TAP_SLOP + 1));
    }

    @Test
    public void jitterWithinSlopIsStillATap() {
        mTracker.onDown(1000, 50, 50);
        mTracker.onMove(53, 52);
        assertEquals(1, mTracker.onUp(1060, 53, 52));
    }

    @Test
    public void cancelDuringPressDropsTapAndSequence() {
        assertEquals(1, tap(1000, 60, 50, 50));
        mTracker.onDown(1150, 50, 50);
        mTracker.cancel(); // second finger, scale, long press, text selection
        assertEquals(0, mTracker.onUp(1200, 50, 50));
        assertEquals(1, tap(1300, 60, 50, 50));
    }

    @Test
    public void resetAfterActingStartsOverAtOne() {
        assertEquals(1, tap(1000, 60, 50, 50));
        assertEquals(2, tap(1150, 60, 50, 50));
        mTracker.reset();
        assertEquals(1, tap(1300, 60, 50, 50));
        assertEquals(2, tap(1450, 60, 50, 50));
    }

    @Test
    public void upWithoutDownIsIgnored() {
        assertEquals(0, mTracker.onUp(1000, 50, 50));
        assertEquals(0, mTracker.getCount());
    }

    @Test
    public void countIsCapped() {
        long t = 1000;
        int count = 0;
        for (int i = 0; i < TapSequenceTracker.MAX_COUNT + 10; i++) {
            count = tap(t, 40, 50, 50);
            t += 120;
        }
        assertEquals(TapSequenceTracker.MAX_COUNT, count);
    }

    @Test
    public void onDownReportsWhetherThePressContinuesTheSequence() {
        assertFalse(mTracker.onDown(1000, 50, 50));
        mTracker.onUp(1060, 50, 50);
        assertTrue(mTracker.onDown(1150, 50, 50));
        mTracker.onUp(1210, 50, 50);
        // Too late, too far.
        assertFalse(mTracker.onDown(1210 + INTER_TAP_MS + 200, 50, 50));
        mTracker.onUp(1900, 50, 50);
        assertFalse(mTracker.onDown(2000, 50 + SEQUENCE_SLOP + 20, 50));
    }

    @Test
    public void isPressedFollowsThePress() {
        assertFalse(mTracker.isPressed());
        mTracker.onDown(1000, 50, 50);
        assertTrue(mTracker.isPressed());
        mTracker.onMove(50, 50 + TAP_SLOP + 1);
        assertFalse(mTracker.isPressed());
        mTracker.onDown(1100, 50, 50);
        mTracker.onUp(1160, 50, 50);
        assertFalse(mTracker.isPressed());
    }

    @Test
    public void sequenceTimeLeftIsMeasuredFromThePreviousPress() {
        assertEquals(0, mTracker.getSequenceTimeLeftMs(1000));
        tap(1000, 60, 50, 50);
        assertEquals(INTER_TAP_MS - 60, mTracker.getSequenceTimeLeftMs(1060));
        assertEquals(0, mTracker.getSequenceTimeLeftMs(1000 + INTER_TAP_MS + 50));
        mTracker.reset();
        assertEquals(0, mTracker.getSequenceTimeLeftMs(1060));
    }

}
