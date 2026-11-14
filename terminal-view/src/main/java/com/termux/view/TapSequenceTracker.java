package com.termux.view;

/**
 * Counts consecutive finger taps (single, double, triple, ...) without any Android dependency so
 * that the timing and distance rules can be unit tested.
 *
 * A tap is a press that is released within {@code maxTapDurationMs} without leaving
 * {@code tapSlop} around the press position. A tap continues the running sequence when its press
 * comes at least {@link #MIN_INTER_TAP_MS} and at most {@code interTapTimeoutMs} after the previous
 * tap was released, within {@code sequenceSlop} of where the previous tap was released. Anything
 * else starts a new sequence at one.
 *
 * Callers cancel the sequence with {@link #cancel()} for scrolling, long press, multi touch,
 * scaling and text selection, and reset it with {@link #reset()} once the sequence has been acted
 * upon.
 */
public final class TapSequenceTracker {

    /** Same lower bound the platform GestureDetector uses to ignore bouncing double taps. */
    public static final long MIN_INTER_TAP_MS = 40;

    /** Upper bound of the reported count, so that it can never overflow. */
    public static final int MAX_COUNT = 16;

    private final long mMaxTapDurationMs;
    private final long mInterTapTimeoutMs;
    private final float mTapSlop;
    private final float mSequenceSlop;

    private boolean mPressed;
    private long mDownTime;
    private float mDownX;
    private float mDownY;
    private boolean mPressContinuesSequence;

    private int mCount;
    private boolean mHasPreviousTap;
    private long mLastTapDownTime;
    private long mLastTapUpTime;
    private float mLastTapUpX;
    private float mLastTapUpY;

    public TapSequenceTracker(long maxTapDurationMs, long interTapTimeoutMs, float tapSlop, float sequenceSlop) {
        mMaxTapDurationMs = maxTapDurationMs;
        mInterTapTimeoutMs = interTapTimeoutMs;
        mTapSlop = tapSlop;
        mSequenceSlop = sequenceSlop;
    }

    /**
     * A first finger went down. Starts a potential tap.
     *
     * @return Whether this press continues the running sequence, if it turns out to be a tap.
     */
    public boolean onDown(long timeMs, float x, float y) {
        mPressed = true;
        mDownTime = timeMs;
        mDownX = x;
        mDownY = y;
        mPressContinuesSequence = mHasPreviousTap
            && timeMs - mLastTapUpTime >= MIN_INTER_TAP_MS
            && timeMs - mLastTapDownTime <= mInterTapTimeoutMs
            && distance(x, y, mLastTapUpX, mLastTapUpY) <= mSequenceSlop;
        return mPressContinuesSequence;
    }

    /** The finger moved. Leaving the tap slop turns the press into a drag, which is not a tap. */
    public void onMove(float x, float y) {
        if (!mPressed) return;
        if (distance(x, y, mDownX, mDownY) > mTapSlop) cancel();
    }

    /**
     * The finger went up.
     *
     * @return The number of the tap within the running sequence, starting at {@code 1}, or
     * {@code 0} if the press was not a tap.
     */
    public int onUp(long timeMs, float x, float y) {
        if (!mPressed) return 0;
        mPressed = false;

        if (timeMs - mDownTime > mMaxTapDurationMs || distance(x, y, mDownX, mDownY) > mTapSlop) {
            // A long press or a drag.
            clearSequence();
            return 0;
        }

        mCount = mPressContinuesSequence ? Math.min(mCount + 1, MAX_COUNT) : 1;
        mHasPreviousTap = true;
        mLastTapDownTime = mDownTime;
        mLastTapUpTime = timeMs;
        mLastTapUpX = x;
        mLastTapUpY = y;
        return mCount;
    }

    /** Abort the press and forget the running sequence. */
    public void cancel() {
        mPressed = false;
        clearSequence();
    }

    /** Forget the running sequence, e.g. because it has been acted upon. */
    public void reset() {
        clearSequence();
    }

    /** Whether a finger is down and could still turn out to be a tap. */
    public boolean isPressed() {
        return mPressed;
    }

    /**
     * How long after {@code nowMs} a further press stops continuing the running sequence, in
     * milliseconds, {@code 0} if there is no running sequence.
     */
    public long getSequenceTimeLeftMs(long nowMs) {
        if (!mHasPreviousTap) return 0;
        return Math.max(0, mLastTapDownTime + mInterTapTimeoutMs - nowMs);
    }

    /** The number of taps in the running sequence, {@code 0} if there is none. */
    public int getCount() {
        return mCount;
    }

    private void clearSequence() {
        mCount = 0;
        mHasPreviousTap = false;
        mPressContinuesSequence = false;
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

}
