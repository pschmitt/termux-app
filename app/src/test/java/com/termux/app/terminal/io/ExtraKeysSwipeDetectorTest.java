package com.termux.app.terminal.io;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExtraKeysSwipeDetectorTest {

    private static final float MIN_DISTANCE = 150f;
    private static final float SLOP = 24f;

    private ExtraKeysSwipeDetector mDetector;

    @Before
    public void setUp() {
        mDetector = new ExtraKeysSwipeDetector(MIN_DISTANCE, SLOP);
    }

    @Test
    public void deliberateSwipeIsRecognizedOnce() {
        mDetector.onDown(100, 50, true);
        assertFalse(mDetector.onMove(160, 52));
        assertTrue(mDetector.onMove(100 + MIN_DISTANCE, 55));
        assertTrue(mDetector.isRecognized());
        // Not again for the rest of the same touch.
        assertFalse(mDetector.onMove(100 + MIN_DISTANCE + 80, 55));
        assertFalse(mDetector.onMove(100 + MIN_DISTANCE + 90, 55));
    }

    @Test
    public void shortSwipeIsNotRecognized() {
        mDetector.onDown(100, 50, true);
        assertFalse(mDetector.onMove(100 + MIN_DISTANCE - 1, 50));
        assertFalse(mDetector.isRecognized());
    }

    @Test
    public void tapAndJitterAreNotRecognized() {
        mDetector.onDown(100, 50, true);
        assertFalse(mDetector.onMove(104, 53));
        assertFalse(mDetector.onMove(98, 49));
        assertFalse(mDetector.isRecognized());
    }

    @Test
    public void swipeTowardTheTextInputIsNeverRecognized() {
        mDetector.onDown(500, 50, true);
        assertFalse(mDetector.onMove(500 - SLOP - 1, 50));
        assertFalse(mDetector.onMove(100, 50));
        // Not even by coming back past the start.
        assertFalse(mDetector.onMove(500 + MIN_DISTANCE + 50, 50));
        assertFalse(mDetector.isRecognized());
    }

    @Test
    public void smallWobbleTowardTheTextInputWithinSlopStillCounts() {
        mDetector.onDown(300, 50, true);
        assertFalse(mDetector.onMove(300 - SLOP + 1, 50));
        assertTrue(mDetector.onMove(300 + MIN_DISTANCE, 50));
    }

    @Test
    public void mostlyVerticalDragIsNotRecognized() {
        // The extra keys scroll vertically.
        mDetector.onDown(100, 50, true);
        assertFalse(mDetector.onMove(100 + MIN_DISTANCE, 50 + MIN_DISTANCE));
        assertFalse(mDetector.onMove(100 + MIN_DISTANCE + 200, 50 + 2 * (MIN_DISTANCE + 200)));
        assertFalse(mDetector.isRecognized());
    }

    @Test
    public void verticalTravelEarlierInTheTouchStillCounts() {
        mDetector.onDown(100, 50, true);
        assertFalse(mDetector.onMove(110, 50 + 200));
        assertFalse(mDetector.onMove(100 + MIN_DISTANCE, 50));
        assertFalse(mDetector.isRecognized());
    }

    @Test
    public void notEligibleIsNeverRecognized() {
        // The text input is selected or the pager is moving.
        mDetector.onDown(100, 50, false);
        assertFalse(mDetector.onMove(100 + MIN_DISTANCE * 3, 50));
        assertFalse(mDetector.isRecognized());
    }

    @Test
    public void cancelStopsRecognition() {
        mDetector.onDown(100, 50, true);
        mDetector.cancel(); // second finger
        assertFalse(mDetector.onMove(100 + MIN_DISTANCE * 3, 50));
    }

    @Test
    public void nextTouchStartsOver() {
        mDetector.onDown(100, 50, true);
        assertTrue(mDetector.onMove(100 + MIN_DISTANCE, 50));
        mDetector.onDown(100, 50, true);
        assertFalse(mDetector.isRecognized());
        assertTrue(mDetector.onMove(100 + MIN_DISTANCE, 50));
    }

}
