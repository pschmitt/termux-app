package com.termux.app.terminal.io;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Recognizes deliberate horizontal swipes across the normal extra keys row, in both directions, the
 * way swipe actions on list items work (Gmail, Todoist): the row follows the finger from the start
 * of the drag, and the action is decided when the finger goes up.
 *
 * The directions are named after the page that the swipe would go to, like the pager does: a
 * {@link Direction#LEFT} swipe has the finger moving from left to right, a {@link Direction#RIGHT}
 * swipe from right to left (toward the text input page on the right).
 *
 * A drag becomes a swipe ({@link Result#RECOGNIZED}) once the finger is {@code dragStart} away from
 * where it went down, horizontally, while it moved clearly more horizontally than vertically and
 * never went more than {@code touchSlop} the other way, in a direction that has an action. From then
 * on the touch belongs to the swipe. Released at least {@code longDistance} away it is a
 * {@link Result#LONG_SWIPE} if the direction has one, at least {@code minDistance} away a
 * {@link Result#SWIPE} if the direction has one, and otherwise nothing happens: going back toward
 * the start calls a swipe off.
 *
 * A press that starts elsewhere than on the settled normal row, that gets a second finger or that
 * is cancelled never is a swipe. Without Android dependencies, so that the rules can be unit tested.
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
        /** Nothing to do, or a swipe was called off. */
        NONE,
        /** The drag is a swipe from now on: the touch belongs to it. */
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

    private final float mDragStart;
    private final float mTouchSlop;
    private float mMinDistance;
    private float mLongDistance;

    private boolean mTracking;
    private boolean mFollowing;
    private boolean mLeftSwipe, mRightSwipe, mLeftLong, mRightLong;
    private int mSign;
    private Direction mDirection;
    private float mDownX;
    private float mDownY;
    private float mMaxAbsDy;
    private float mDistance;

    public ExtraKeysSwipeDetector(float dragStart, float minDistance, float longDistance, float touchSlop) {
        mDragStart = Math.max(dragStart, touchSlop);
        mTouchSlop = touchSlop;
        setDistances(minDistance, longDistance);
    }

    /** The distances of the swipe and the long swipe, which depend on the width of the row. */
    public void setDistances(float minDistance, float longDistance) {
        mMinDistance = Math.max(minDistance, mDragStart);
        mLongDistance = Math.max(longDistance, mMinDistance);
    }

    public float getMinDistance() {
        return mMinDistance;
    }

    public float getLongDistance() {
        return mLongDistance;
    }

    /**
     * A first finger went down.
     *
     * @param bindings The swipes that have an action, or {@code null} if the normal extra keys row
     *                 is not the settled page: if the text input is selected or the pager is still
     *                 moving, nothing is recognized.
     */
    public void onDown(float x, float y, @Nullable Bindings bindings) {
        mLeftSwipe = bindings != null && bindings.hasSwipe(Direction.LEFT);
        mRightSwipe = bindings != null && bindings.hasSwipe(Direction.RIGHT);
        mLeftLong = bindings != null && bindings.hasLongSwipe(Direction.LEFT);
        mRightLong = bindings != null && bindings.hasLongSwipe(Direction.RIGHT);
        mTracking = mLeftSwipe || mRightSwipe || mLeftLong || mRightLong;
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
            if (!hasAction(sign > 0 ? Direction.LEFT : Direction.RIGHT)) {
                mTracking = false;
                return Result.NONE;
            }
        }

        float distance = Math.abs(dx);
        if (mSign != 0 && distance >= mDragStart && mMaxAbsDy < distance * MAX_VERTICAL_TO_HORIZONTAL_RATIO) {
            mDirection = mSign > 0 ? Direction.LEFT : Direction.RIGHT;
            mDistance = distance;
            mFollowing = true;
            return Result.RECOGNIZED;
        }
        return Result.NONE;
    }

    /** The finger went up. */
    @NonNull
    public Result onUp(float x, float y) {
        onMove(x, y);
        boolean following = mFollowing;
        mFollowing = false;
        mTracking = false;
        if (!following) return Result.NONE;
        if (hasLong(mDirection) && mDistance >= mLongDistance) return Result.LONG_SWIPE;
        if (hasSwipe(mDirection) && mDistance >= mMinDistance) return Result.SWIPE;
        return Result.NONE;
    }

    /** A second finger went down or the press was cancelled. */
    public void cancel() {
        mTracking = false;
        mFollowing = false;
    }

    private boolean hasSwipe(@Nullable Direction direction) {
        return direction == Direction.LEFT ? mLeftSwipe : direction == Direction.RIGHT && mRightSwipe;
    }

    private boolean hasLong(@Nullable Direction direction) {
        return direction == Direction.LEFT ? mLeftLong : direction == Direction.RIGHT && mRightLong;
    }

    private boolean hasAction(@NonNull Direction direction) {
        return hasSwipe(direction) || hasLong(direction);
    }

    /** Whether a swipe is being followed. */
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

    /** Whether the followed swipe has a long swipe. */
    public boolean hasLongSwipe() {
        return mFollowing && hasLong(mDirection);
    }

    /** Whether the followed swipe has a normal swipe. */
    public boolean hasSwipe() {
        return mFollowing && hasSwipe(mDirection);
    }

    /** What releasing now would do. */
    @NonNull
    public Result getPending() {
        if (!mFollowing) return Result.NONE;
        if (hasLong(mDirection) && mDistance >= mLongDistance) return Result.LONG_SWIPE;
        if (hasSwipe(mDirection) && mDistance >= mMinDistance) return Result.SWIPE;
        return Result.NONE;
    }

}
