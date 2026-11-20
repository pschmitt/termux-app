package com.termux.app.terminal.io;

import androidx.annotation.NonNull;

import com.termux.app.terminal.io.ExtraKeysSwipeDetector.Direction;
import com.termux.app.terminal.io.ExtraKeysSwipeDetector.Result;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ExtraKeysSwipeDetectorTest {

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
        mDetector = new ExtraKeysSwipeDetector(MIN_DISTANCE, LONG_DISTANCE, SLOP);
        mBindings = new FakeBindings();
    }

    @Test
    public void swipeToTheRightIsALeftSwipeRecognizedOnce() {
        mDetector.onDown(100, 50, mBindings);
        assertSame(Result.NONE, mDetector.onMove(160, 52));
        assertSame(Result.SWIPE, mDetector.onMove(100 + MIN_DISTANCE, 55));
        assertSame(Direction.LEFT, mDetector.getDirection());
        // Not again for the rest of the same touch.
        assertSame(Result.NONE, mDetector.onMove(100 + MIN_DISTANCE + 80, 55));
        assertSame(Result.NONE, mDetector.onUp(100 + MIN_DISTANCE + 90, 55));
    }

    @Test
    public void swipeToTheLeftIsARightSwipe() {
        mDetector.onDown(500, 50, mBindings);
        assertSame(Result.SWIPE, mDetector.onMove(500 - MIN_DISTANCE, 50));
        assertSame(Direction.RIGHT, mDetector.getDirection());
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
    public void shortSwipeIsNotRecognized() {
        mDetector.onDown(100, 50, mBindings);
        assertSame(Result.NONE, mDetector.onMove(100 + MIN_DISTANCE - 1, 50));
        assertSame(Result.NONE, mDetector.onUp(100 + MIN_DISTANCE - 1, 50));
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
        assertSame(Result.NONE, mDetector.onMove(500 + MIN_DISTANCE + 50, 50));
        assertSame(Result.NONE, mDetector.onMove(500 - MIN_DISTANCE - 50, 50));
    }

    @Test
    public void smallWobbleWithinSlopStillCounts() {
        mDetector.onDown(300, 50, mBindings);
        assertSame(Result.NONE, mDetector.onMove(300 - SLOP + 1, 50));
        assertSame(Result.SWIPE, mDetector.onMove(300 + MIN_DISTANCE, 50));
    }

    @Test
    public void mostlyVerticalDragIsNotRecognized() {
        // The extra keys scroll vertically.
        mDetector.onDown(100, 50, mBindings);
        assertSame(Result.NONE, mDetector.onMove(100 + MIN_DISTANCE, 50 + MIN_DISTANCE));
        assertSame(Result.NONE, mDetector.onMove(100 + MIN_DISTANCE + 200, 50 + 2 * (MIN_DISTANCE + 200)));
    }

    @Test
    public void verticalTravelEarlierInTheTouchStillCounts() {
        mDetector.onDown(100, 50, mBindings);
        assertSame(Result.NONE, mDetector.onMove(110, 50 + 200));
        assertSame(Result.NONE, mDetector.onMove(100 + MIN_DISTANCE, 50));
    }

    @Test
    public void notEligibleIsNeverRecognized() {
        // The text input is selected or the pager is moving.
        mDetector.onDown(100, 50, null);
        assertSame(Result.NONE, mDetector.onMove(100 + MIN_DISTANCE * 3, 50));
    }

    @Test
    public void cancelStopsRecognition() {
        mDetector.onDown(100, 50, mBindings);
        mDetector.cancel(); // second finger
        assertSame(Result.NONE, mDetector.onMove(100 + MIN_DISTANCE * 3, 50));
    }

    @Test
    public void nextTouchStartsOver() {
        mDetector.onDown(100, 50, mBindings);
        assertSame(Result.SWIPE, mDetector.onMove(100 + MIN_DISTANCE, 50));
        mDetector.onDown(100, 50, mBindings);
        assertSame(Result.SWIPE, mDetector.onMove(100 + MIN_DISTANCE, 50));
    }

    @Test
    public void withALongSwipeTheSwipeIsFollowedAndDecidedOnRelease() {
        mBindings.longLeft = true;
        mDetector.onDown(100, 50, mBindings);
        assertSame(Result.RECOGNIZED, mDetector.onMove(100 + MIN_DISTANCE, 50));
        assertTrue(mDetector.isFollowing());
        assertFalse(mDetector.isLongReached());
        assertEquals(0f, mDetector.getLongProgress(), 0.001f);

        mDetector.onMove(100 + (MIN_DISTANCE + LONG_DISTANCE) / 2, 50);
        assertEquals(0.5f, mDetector.getLongProgress(), 0.001f);
        assertSame(Result.SWIPE, mDetector.onUp(100 + (MIN_DISTANCE + LONG_DISTANCE) / 2, 50));
        assertFalse(mDetector.isFollowing());
    }

    @Test
    public void releasedFarEnoughIsALongSwipe() {
        mBindings.longRight = true;
        mDetector.onDown(600, 50, mBindings);
        assertSame(Result.RECOGNIZED, mDetector.onMove(600 - MIN_DISTANCE, 50));
        assertSame(Direction.RIGHT, mDetector.getDirection());
        mDetector.onMove(600 - LONG_DISTANCE, 50);
        assertTrue(mDetector.isLongReached());
        // Vertical travel no longer matters once the swipe is followed.
        assertSame(Result.LONG_SWIPE, mDetector.onUp(600 - LONG_DISTANCE - 10, 140));
    }

    @Test
    public void comingBackFromTheLongSwipeIsTheNormalSwipeAgain() {
        mBindings.longLeft = true;
        mDetector.onDown(100, 50, mBindings);
        mDetector.onMove(100 + MIN_DISTANCE, 50);
        mDetector.onMove(100 + LONG_DISTANCE + 20, 50);
        assertTrue(mDetector.isLongReached());
        mDetector.onMove(100 + MIN_DISTANCE + 20, 50);
        assertFalse(mDetector.isLongReached());
        assertSame(Result.SWIPE, mDetector.onUp(100 + MIN_DISTANCE + 20, 50));
    }

    @Test
    public void goingBackToTheStartCallsAFollowedSwipeOff() {
        mBindings.longLeft = true;
        mDetector.onDown(100, 50, mBindings);
        mDetector.onMove(100 + MIN_DISTANCE, 50);
        mDetector.onMove(100 + 20, 50);
        assertTrue(mDetector.isCalledOff());
        assertSame(Result.NONE, mDetector.onUp(100 + 20, 50));
    }

    @Test
    public void longSwipeAloneMakesTheDirectionSwipeable() {
        mBindings.left = false;
        mBindings.longLeft = true;
        mDetector.onDown(100, 50, mBindings);
        assertSame(Result.RECOGNIZED, mDetector.onMove(100 + MIN_DISTANCE, 50));
    }

    @Test
    public void cancelEndsAFollowedSwipe() {
        mBindings.longLeft = true;
        mDetector.onDown(100, 50, mBindings);
        mDetector.onMove(100 + MIN_DISTANCE, 50);
        mDetector.cancel();
        assertFalse(mDetector.isFollowing());
        assertSame(Result.NONE, mDetector.onUp(100 + LONG_DISTANCE, 50));
    }

}
