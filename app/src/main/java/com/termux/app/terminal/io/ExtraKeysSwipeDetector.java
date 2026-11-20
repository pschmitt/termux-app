package com.termux.app.terminal.io;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Recognizes deliberate horizontal swipes across the normal extra keys row, in both directions.
 *
 * The directions are named after the page that the swipe would go to, like the pager does: a
 * {@link Direction#LEFT} swipe has the finger moving from left to right, a {@link Direction#RIGHT}
 * swipe from right to left (toward the text input page on the right).
 *
 * A swipe is recognized once the finger is at least {@code minDistance} away from where it went
 * down, horizontally, while it moved clearly more horizontally than vertically and never went more
 * than {@code touchSlop} the other way. A press that starts elsewhere than on the settled normal
 * row, that gets a second finger or that is cancelled never is a swipe.
 *
 * If the direction has no long swipe, the swipe fires at once ({@link Result#SWIPE}). Otherwise
 * the swipe is only {@link Result#RECOGNIZED} and followed until the finger goes up: released at
 * least {@code longDistance} away it is a {@link Result#LONG_SWIPE}, released less far but still
 * past {@code minDistance} a {@link Result#SWIPE}, and brought back closer than that to where it
 * started it is called off ({@link Result#NONE}).
 *
 * Without Android dependencies, so that the rules can be unit tested.
 */
public final class ExtraKeysSwipeDetector {

    /** The vertical travel allowed relative to the horizontal one, like the ViewPager requires for its own drags. */
    static final float MAX_VERTICAL_TO_HORIZONTAL_RATIO = 0.5f;

    public enum Direction {
        /** The finger moves from left to right. */
        LEFT,
        /** The finger moves from right to left. */
        RIGHT;

        /** +1 if the finger moves to the right, -1 if it moves to the left. */
        public int sign() {
            return this == LEFT ? 1 : -1;
        }
    }

    public enum Result {
        /** Nothing to do, or a followed swipe was called off. */
        NONE,
        /** A swipe with a long swipe is followed from now on: the touch belongs to the swipe. */
        RECOGNIZED,
        /** The (normal) swipe is done. */
        SWIPE,
        /** The long swipe is done. */
        LONG_SWIPE
    }

    /** Which swipes have an action. Asked once per touch, when the finger goes down. */
    public interface Bindings {
        boolean hasSwipe(@NonNull Direction direction);

        boolean hasLongSwipe(@NonNull Direction direction);
    }

    private final float mMinDistance;
    private final float mLongDistance;
    private final float mTouchSlop;

    private boolean mTracking;
    private boolean mFollowing;
    private boolean mLeftEnabled, mRightEnabled, mLeftLong, mRightLong;
    private int mSign;
    private Direction mDirection;
    private float mDownX;
    private float mDownY;
    private float mMaxAbsDy;
    private float mDistance;

    public ExtraKeysSwipeDetector(float minDistance, float longDistance, float touchSlop) {
        mMinDistance = minDistance;
        mLongDistance = Math.max(longDistance, minDistance);
        mTouchSlop = touchSlop;
    }

    /**
     * A first finger went down.
     *
     * @param bindings The swipes that have an action, or {@code null} if the normal extra keys row
     *                 is not the settled page: if the text input is selected or the pager is still
     *                 moving, nothing is recognized.
     */
    public void onDown(float x, float y, @Nullable Bindings bindings) {
        mLeftLong = bindings != null && bindings.hasLongSwipe(Direction.LEFT);
        mRightLong = bindings != null && bindings.hasLongSwipe(Direction.RIGHT);
        mLeftEnabled = mLeftLong || (bindings != null && bindings.hasSwipe(Direction.LEFT));
        mRightEnabled = mRightLong || (bindings != null && bindings.hasSwipe(Direction.RIGHT));
        mTracking = mLeftEnabled || mRightEnabled;
        mFollowing = false;
        mSign = 0;
        mDirection = null;
        mDownX = x;
        mDownY = y;
        mMaxAbsDy = 0;
        mDistance = 0;
    }

    /** The finger moved. */
    @NonNull
    public Result onMove(float x, float y) {
        if (!mTracking) return Result.NONE;

        float dx = x - mDownX;

        if (mFollowing) {
            mDistance = dx * mDirection.sign();
            return Result.NONE;
        }

        mMaxAbsDy = Math.max(mMaxAbsDy, Math.abs(y - mDownY));

        if (Math.abs(dx) > mTouchSlop) {
            int sign = dx > 0 ? 1 : -1;
            if (mSign == 0) {
                mSign = sign;
            } else if (sign != mSign) {
                // Back and forth is no swipe.
                mTracking = false;
                return Result.NONE;
            }
            Direction direction = sign > 0 ? Direction.LEFT : Direction.RIGHT;
            if (!(direction == Direction.LEFT ? mLeftEnabled : mRightEnabled)) {
                mTracking = false;
                return Result.NONE;
            }
        }

        float distance = Math.abs(dx);
        if (mSign != 0 && distance >= mMinDistance && mMaxAbsDy < distance * MAX_VERTICAL_TO_HORIZONTAL_RATIO) {
            mDirection = mSign > 0 ? Direction.LEFT : Direction.RIGHT;
            mDistance = distance;
            if (mDirection == Direction.LEFT ? mLeftLong : mRightLong) {
                mFollowing = true;
                return Result.RECOGNIZED;
            }
            mTracking = false;
            return Result.SWIPE;
        }
        return Result.NONE;
    }

    /** The finger went up. */
    @NonNull
    public Result onUp(float x, float y) {
        Result result = onMove(x, y);
        if (!mFollowing) {
            mTracking = false;
            return result;
        }
        mFollowing = false;
        mTracking = false;
        if (mDistance >= mLongDistance) return Result.LONG_SWIPE;
        if (mDistance >= mMinDistance) return Result.SWIPE;
        return Result.NONE;
    }

    /** A second finger went down or the press was cancelled. */
    public void cancel() {
        mTracking = false;
        mFollowing = false;
    }

    /** Whether a swipe with a long swipe is being followed. */
    public boolean isFollowing() {
        return mFollowing;
    }

    /** The direction of the recognized swipe, or {@code null}. */
    @Nullable
    public Direction getDirection() {
        return mDirection;
    }

    /** How far the finger is from where it went down, along the direction of the swipe (negative if behind). */
    public float getDistance() {
        return mDistance;
    }

    /** Whether releasing now would be a long swipe. */
    public boolean isLongReached() {
        return mFollowing && mDistance >= mLongDistance;
    }

    /** Whether releasing now would call the followed swipe off. */
    public boolean isCalledOff() {
        return mFollowing && mDistance < mMinDistance;
    }

    /**
     * How far the followed swipe is on its way from the normal swipe to the long swipe, from 0 (at
     * {@code minDistance} or less) to 1 (at {@code longDistance} or more).
     */
    public float getLongProgress() {
        if (mLongDistance <= mMinDistance) return mDistance >= mLongDistance ? 1f : 0f;
        float progress = (mDistance - mMinDistance) / (mLongDistance - mMinDistance);
        return Math.max(0f, Math.min(1f, progress));
    }

}
