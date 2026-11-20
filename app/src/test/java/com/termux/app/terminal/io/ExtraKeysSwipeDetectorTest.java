package com.termux.app.terminal.io;

import androidx.annotation.NonNull;

import com.termux.app.terminal.io.ExtraKeysSwipeDetector.Direction;
import com.termux.app.terminal.io.ExtraKeysSwipeDetector.Result;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ExtraKeysSwipeDetectorTest {

    private static final float DRAG_START = 30f;
    private static final float MIN_DISTANCE = 150f;
    private static final float LONG_DISTANCE = 400f;
    private static final float SLOP = 24f;

    private static final class FakeBindings implements ExtraKeysSwipeDetector.Bindings {
        boolean left = true, right = true, longLeft, longRight;

        @Override
        public boolean hasSwipe(@NonNull Direction direction) {
            return direction == Direction.LEFT ? left : right;
        }

        @Override
        public boolean hasLongSwipe(@NonNull Direction direction) {
            return direction == Direction.LEFT ? longLeft : longRight;
        }
    }

    private ExtraKeysSwipeDetector mDetector;
    private FakeBindings mBindings;

    @Before
    public void setUp() {
        mDetector = new ExtraKeysSwipeDetector(DRAG_START, MIN_DISTANCE, LONG_DISTANCE, SLOP);
        mBindings = new FakeBindings();
    }

    @Test
    public void theRowFollowsFromTheDragStartAndTheSwipeRunsOnRelease() {
        mDetector.onDown(100, 50, mBindings);
        assertSame(Result.NONE, mDetector.onMove(110, 51));
        assertSame(Result.RECOGNIZED, mDetector.onMove(100 + DRAG_START, 52));
        assertSame(Direction.LEFT, mDetector.getDirection());
        assertTrue(mDetector.isFollowing());
        assertSame("not far enough yet", Result.NONE, mDetector.getPending());
        mDetector.onMove(100 + MIN_DISTANCE, 52);
        assertSame(Result.SWIPE, mDetector.getPending());
        assertSame(Result.SWIPE, mDetector.onUp(100 + MIN_DISTANCE, 52));
        assertFalse(mDetector.isFollowing());
    }

    @Test
    public void swipeToTheLeftIsARightSwipe() {
        mDetector.onDown(500, 50, mBindings);
        assertSame(Result.RECOGNIZED, mDetector.onMove(500 - DRAG_START, 50));
        assertSame(Direction.RIGHT, mDetector.getDirection());
        assertSame(Result.SWIPE, mDetector.onUp(500 - MIN_DISTANCE, 50));
    }

    @Test
    public void tooShortASwipeIsCalledOff() {
        mDetector.onDown(100, 50, mBindings);
        mDetector.onMove(100 + DRAG_START, 50);
        assertSame(Result.NONE, mDetector.onUp(100 + MIN_DISTANCE - 1, 50));
    }

    @Test
    public void goingBackCallsTheSwipeOff() {
        mDetector.onDown(100, 50, mBindings);
        mDetector.onMove(100 + DRAG_START, 50);
        mDetector.onMove(100 + MIN_DISTANCE + 50, 50);
        assertSame(Result.SWIPE, mDetector.getPending());
        assertSame(Result.NONE, mDetector.onUp(110, 50));
    }

    @Test
    public void directionWithoutAnActionIsNeverRecognized() {
        mBindings.right = false;
        mDetector.onDown(500, 50, mBindings);
        assertSame(Result.NONE, mDetector.onMove(500 - MIN_DISTANCE * 2, 50));
        // Not even by coming back past the start.
        assertSame(Result.NONE, mDetector.onMove(500 + MIN_DISTANCE * 2, 50));
    }

    @Test
    public void tapAndJitterAreNotRecognized() {
        mDetector.onDown(100, 50, mBindings);
        assertSame(Result.NONE, mDetector.onMove(104, 53));
        assertSame(Result.NONE, mDetector.onMove(98, 49));
        assertSame(Result.NONE, mDetector.onUp(100, 50));
    }

    @Test
    public void backAndForthIsNotRecognized() {
        mDetector.onDown(500, 50, mBindings);
        assertSame(Result.NONE, mDetector.onMove(500 - SLOP - 1, 50));
        assertSame(Result.NONE, mDetector.onMove(500 + MIN_DISTANCE, 50));
    }

    @Test
    public void mostlyVerticalDragIsNotRecognized() {
        // The extra keys scroll vertically.
        mDetector.onDown(100, 50, mBindings);
        assertSame(Result.NONE, mDetector.onMove(100 + DRAG_START, 50 + DRAG_START));
        assertSame(Result.NONE, mDetector.onMove(100 + MIN_DISTANCE, 50 + 2 * MIN_DISTANCE));
    }

    @Test
    public void notEligibleIsNeverRecognized() {
        // The text input is selected or the pager is moving.
        mDetector.onDown(100, 50, null);
        assertSame(Result.NONE, mDetector.onMove(100 + MIN_DISTANCE * 3, 50));
        assertSame(Result.NONE, mDetector.onUp(100 + MIN_DISTANCE * 3, 50));
    }

    @Test
    public void cancelEndsTheSwipe() {
        mDetector.onDown(100, 50, mBindings);
        mDetector.onMove(100 + DRAG_START, 50);
        mDetector.cancel(); // second finger
        assertFalse(mDetector.isFollowing());
        assertSame(Result.NONE, mDetector.onUp(100 + LONG_DISTANCE, 50));
    }

    @Test
    public void releasedFarEnoughIsALongSwipe() {
        mBindings.longRight = true;
        mDetector.onDown(600, 50, mBindings);
        mDetector.onMove(600 - DRAG_START, 50);
        mDetector.onMove(600 - LONG_DISTANCE, 50);
        assertSame(Result.LONG_SWIPE, mDetector.getPending());
        // Vertical travel no longer matters once the swipe is followed.
        assertSame(Result.LONG_SWIPE, mDetector.onUp(600 - LONG_DISTANCE - 10, 140));
    }

    @Test
    public void withoutALongSwipeFurtherIsStillTheSwipe() {
        mDetector.onDown(100, 50, mBindings);
        mDetector.onMove(100 + DRAG_START, 50);
        assertSame(Result.SWIPE, mDetector.onUp(100 + LONG_DISTANCE * 2, 50));
    }

    @Test
    public void betweenTheDistancesIsTheNormalSwipe() {
        mBindings.longLeft = true;
        mDetector.onDown(100, 50, mBindings);
        mDetector.onMove(100 + DRAG_START, 50);
        mDetector.onMove(100 + LONG_DISTANCE + 20, 50);
        assertSame(Result.LONG_SWIPE, mDetector.getPending());
        mDetector.onMove(100 + MIN_DISTANCE + 20, 50);
        assertSame(Result.SWIPE, mDetector.getPending());
        assertSame(Result.SWIPE, mDetector.onUp(100 + MIN_DISTANCE + 20, 50));
    }

    @Test
    public void aLongSwipeAloneNeedsTheLongDistance() {
        mBindings.left = false;
        mBindings.longLeft = true;
        mDetector.onDown(100, 50, mBindings);
        assertSame(Result.RECOGNIZED, mDetector.onMove(100 + DRAG_START, 50));
        assertSame(Result.NONE, mDetector.onUp(100 + MIN_DISTANCE + 20, 50));
        mDetector.onDown(100, 50, mBindings);
        mDetector.onMove(100 + DRAG_START, 50);
        assertSame(Result.LONG_SWIPE, mDetector.onUp(100 + LONG_DISTANCE, 50));
    }

    @Test
    public void distancesNeverShrinkBelowTheDragStart() {
        mDetector.setDistances(1, 2);
        assertTrue(mDetector.getMinDistance() >= DRAG_START);
        assertTrue(mDetector.getLongDistance() >= mDetector.getMinDistance());
    }

}
