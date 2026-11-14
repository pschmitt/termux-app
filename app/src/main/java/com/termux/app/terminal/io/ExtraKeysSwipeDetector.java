package com.termux.app.terminal.io;

/**
 * Recognizes a deliberate horizontal swipe from left to right across the normal extra keys row,
 * the direction that has no page to go to, since the text input page is on the other side.
 *
 * A swipe is recognized once, as soon as the finger is at least {@code minDistance} to the right of
 * where it went down, while it never moved more than {@code touchSlop} to the left of it, which is
 * the way to the text input, and while it moved clearly more horizontally than vertically. A press
 * that starts elsewhere than on the settled normal row, that gets a second finger or that is
 * cancelled never is a swipe.
 *
 * Without Android dependencies, so that the rules can be unit tested.
 */
public final class ExtraKeysSwipeDetector {

    /** The vertical travel allowed relative to the horizontal one, like the ViewPager requires for its own drags. */
    static final float MAX_VERTICAL_TO_HORIZONTAL_RATIO = 0.5f;

    private final float mMinDistance;
    private final float mTouchSlop;

    private boolean mTracking;
    private boolean mRecognized;
    private float mDownX;
    private float mDownY;
    private float mMaxAbsDy;

    public ExtraKeysSwipeDetector(float minDistance, float touchSlop) {
        mMinDistance = minDistance;
        mTouchSlop = touchSlop;
    }

    /**
     * A first finger went down.
     *
     * @param eligible Whether the normal extra keys row is the settled page. If the text input is
     *                 selected or the pager is still moving, nothing is recognized.
     */
    public void onDown(float x, float y, boolean eligible) {
        mTracking = eligible;
        mRecognized = false;
        mDownX = x;
        mDownY = y;
        mMaxAbsDy = 0;
    }

    /**
     * The finger moved or went up.
     *
     * @return {@code true} exactly once per press, when the swipe is recognized.
     */
    public boolean onMove(float x, float y) {
        if (!mTracking) return false;

        float dx = x - mDownX;
        mMaxAbsDy = Math.max(mMaxAbsDy, Math.abs(y - mDownY));

        if (dx < -mTouchSlop) {
            // Toward the text input, which is the pager's to handle.
            mTracking = false;
            return false;
        }
        if (dx >= mMinDistance && mMaxAbsDy < dx * MAX_VERTICAL_TO_HORIZONTAL_RATIO) {
            mTracking = false;
            mRecognized = true;
            return true;
        }
        return false;
    }

    /** A second finger went down or the press was cancelled. */
    public void cancel() {
        mTracking = false;
    }

    /** Whether the running press has been recognized as a swipe. */
    public boolean isRecognized() {
        return mRecognized;
    }

}
