package com.termux.view;

/**
 * Decides which tap count to act on, so that a single, a double and a triple tap can each have
 * their own action without a double tap also firing the single tap action or a triple tap also
 * firing the double tap action.
 *
 * Taps are counted by a {@link TapSequenceTracker}. A tap that reaches the highest tap count that
 * has an action is acted on right away, since no further tap can follow it. Any lower tap
 * waits for the next tap, and is acted on when the time to continue the sequence has run out, or
 * when the next press turns out not to continue it, for example because it is too far away. A
 * following press that turns out to be a drag or a long press, as well as {@link #cancel()},
 * drop the waiting tap without acting on it.
 *
 * Without Android dependencies, the wait is delegated to a {@link Scheduler}.
 */
public final class TapActionResolver {

    /** Schedules a single call of {@link TapActionResolver#onTimeout()}. */
    public interface Scheduler {
        /** Calls {@link TapActionResolver#onTimeout()} after the delay, replacing a scheduled call. */
        void schedule(long delayMs);

        /** Drops a scheduled call, if there is one. */
        void cancel();
    }

    /** Receives the resolved tap count. */
    public interface Listener {
        /** The user tapped {@code tapCount} times in a row, and no further tap followed. */
        void onTap(int tapCount);
    }

    /** Wait this much longer than the sequence timeout, so that a press that just makes it is not missed. */
    static final long TIMEOUT_MARGIN_MS = 20;

    private final TapSequenceTracker mTracker;
    private final Scheduler mScheduler;
    private final Listener mListener;

    /** The count of the last tap that waits for a following tap, {@code 0} if none waits. */
    private int mWaitingCount;

    public TapActionResolver(TapSequenceTracker tracker, Scheduler scheduler, Listener listener) {
        mTracker = tracker;
        mScheduler = scheduler;
        mListener = listener;
    }

    /** A first finger went down. */
    public void onDown(long timeMs, float x, float y) {
        boolean continues = mTracker.onDown(timeMs, x, y);
        if (mWaitingCount == 0) return;

        if (continues) {
            // Decided by the tap that this press may turn out to be.
            mScheduler.cancel();
        } else {
            actOnWaitingTap();
        }
    }

    /** The finger moved. Leaving the tap slop drops a waiting tap, the press is a drag. */
    public void onMove(float x, float y) {
        mTracker.onMove(x, y);
        if (!mTracker.isPressed()) dropWaitingTap();
    }

    /**
     * The finger went up.
     *
     * @param maxTapCount The highest tap count that has an action. Taps are not resolved here for
     *                    one or less, the caller then acts on single taps as soon as they are
     *                    known to be one.
     */
    public void onUp(long timeMs, float x, float y, int maxTapCount) {
        int tapCount = mTracker.onUp(timeMs, x, y);
        if (tapCount == 0) {
            // A long press or a drag.
            dropWaitingTap();
            return;
        }

        if (maxTapCount <= 1) {
            dropWaitingTap();
            mTracker.reset();
        } else if (tapCount >= maxTapCount) {
            dropWaitingTap();
            mTracker.reset();
            mListener.onTap(maxTapCount);
        } else {
            mWaitingCount = tapCount;
            mScheduler.schedule(mTracker.getSequenceTimeLeftMs(timeMs) + TIMEOUT_MARGIN_MS);
        }
    }

    /** The scheduled wait has run out. */
    public void onTimeout() {
        if (mWaitingCount == 0) return;
        actOnWaitingTap();
    }

    /** Drops the waiting tap and the running sequence, for scrolling, long press, selection, multi touch... */
    public void cancel() {
        dropWaitingTap();
        mTracker.cancel();
    }

    /** The number of the tap that waits for a following tap, {@code 0} if none. For tests. */
    int getWaitingCount() {
        return mWaitingCount;
    }

    private void actOnWaitingTap() {
        int tapCount = mWaitingCount;
        mWaitingCount = 0;
        mScheduler.cancel();
        // The sequence is over, so that the press that may be down now is not counted on top of it.
        mTracker.reset();
        mListener.onTap(tapCount);
    }

    private void dropWaitingTap() {
        mWaitingCount = 0;
        mScheduler.cancel();
    }

}
