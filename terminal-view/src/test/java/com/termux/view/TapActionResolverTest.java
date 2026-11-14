package com.termux.view;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TapActionResolverTest {

    private static final long TAP_MAX_MS = 400;
    private static final long INTER_TAP_MS = 300;
    private static final float TAP_SLOP = 8f;
    private static final float SEQUENCE_SLOP = 100f;

    /** Records the one scheduled call, to be run by the test. */
    private static final class FakeScheduler implements TapActionResolver.Scheduler {
        boolean scheduled;
        long delayMs;

        @Override
        public void schedule(long delayMs) {
            scheduled = true;
            this.delayMs = delayMs;
        }

        @Override
        public void cancel() {
            scheduled = false;
        }
    }

    private FakeScheduler mScheduler;
    private TapActionResolver mResolver;
    private final List<Integer> mActed = new ArrayList<>();

    @Before
    public void setUp() {
        mScheduler = new FakeScheduler();
        mActed.clear();
        mResolver = new TapActionResolver(
            new TapSequenceTracker(TAP_MAX_MS, INTER_TAP_MS, TAP_SLOP, SEQUENCE_SLOP),
            mScheduler, mActed::add);
    }

    private void tap(long downTime, long holdMs, float x, float y, int max) {
        mResolver.onDown(downTime, x, y);
        mResolver.onUp(downTime + holdMs, x, y, max);
    }

    /** Runs the scheduled wait, if there is one, like the clock would when it has run out. */
    private void timeout() {
        if (mScheduler.scheduled) {
            mScheduler.scheduled = false;
            mResolver.onTimeout();
        }
    }

    @Test
    public void singleTapOnlyIsNotResolvedHere() {
        // With no higher tap action the caller acts on the confirmed single tap, as it always did.
        tap(1000, 60, 50, 50, 1);
        assertTrue(mActed.isEmpty());
        assertFalse(mScheduler.scheduled);
        tap(1150, 60, 50, 50, 1);
        assertTrue(mActed.isEmpty());
        assertFalse(mScheduler.scheduled);
        tap(1300, 60, 50, 50, 0);
        assertTrue(mActed.isEmpty());
    }

    @Test
    public void doubleTapActsImmediatelyWhenItIsTheHighestAction() {
        tap(1000, 60, 50, 50, 2);
        assertTrue(mActed.isEmpty());
        assertTrue(mScheduler.scheduled);

        tap(1150, 60, 50, 50, 2);
        assertEquals(List.of(2), mActed);
        // And the single tap does not fire later.
        assertFalse(mScheduler.scheduled);
        timeout();
        assertEquals(List.of(2), mActed);
    }

    @Test
    public void singleTapWaitsOutTheSequenceWhenDoubleIsConfigured() {
        tap(1000, 60, 50, 50, 2);
        assertTrue(mActed.isEmpty());
        // The old confirmed single tap latency: up to 300ms after the press, plus the margin.
        assertEquals(INTER_TAP_MS - 60 + TapActionResolver.TIMEOUT_MARGIN_MS, mScheduler.delayMs);

        timeout();
        assertEquals(List.of(1), mActed);
    }

    @Test
    public void tripleConfiguredDefersTheDoubleTap() {
        tap(1000, 60, 50, 50, 3);
        tap(1150, 60, 50, 50, 3);
        // Not yet: a third tap may follow.
        assertTrue(mActed.isEmpty());
        assertTrue(mScheduler.scheduled);
        assertEquals(2, mResolver.getWaitingCount());

        timeout();
        assertEquals(List.of(2), mActed);
    }

    @Test
    public void tripleTapActsImmediatelyAndNeverAlsoFiresLowerCounts() {
        tap(1000, 60, 50, 50, 3);
        tap(1150, 60, 50, 50, 3);
        tap(1300, 60, 50, 50, 3);
        assertEquals(List.of(3), mActed);
        timeout();
        assertEquals(List.of(3), mActed);
    }

    @Test
    public void tapsBeyondTheHighestActionActAsTheHighest() {
        // A fourth tap in a row cannot start a new sequence that fires the single action.
        tap(1000, 60, 50, 50, 2);
        tap(1150, 60, 50, 50, 2);
        assertEquals(List.of(2), mActed);
        // The sequence was reset, a following fast tap starts over at one.
        tap(1300, 60, 50, 50, 2);
        timeout();
        assertEquals(List.of(2, 1), mActed);
    }

    @Test
    public void allThreeActionsAreIndependent() {
        // single
        tap(1000, 60, 50, 50, 3);
        timeout();
        // double
        tap(2000, 60, 50, 50, 3);
        tap(2150, 60, 50, 50, 3);
        timeout();
        // triple
        tap(3000, 60, 50, 50, 3);
        tap(3150, 60, 50, 50, 3);
        tap(3300, 60, 50, 50, 3);
        assertEquals(List.of(1, 2, 3), mActed);
    }

    @Test
    public void onlyDoubleAndTripleConfiguredStillResolvesSingleToCallback() {
        // The client decides that count 1 has no action. The resolver reports it all the same.
        tap(1000, 60, 50, 50, 3);
        timeout();
        assertEquals(List.of(1), mActed);
    }

    @Test
    public void distantNextPressFlushesTheWaitingTap() {
        tap(1000, 60, 50, 50, 2);
        assertTrue(mActed.isEmpty());

        // Too far to continue the sequence: the waiting tap acts now and this press starts over.
        mResolver.onDown(1150, 50 + SEQUENCE_SLOP + 20, 50);
        assertEquals(List.of(1), mActed);
        assertFalse(mScheduler.scheduled);
        mResolver.onUp(1210, 50 + SEQUENCE_SLOP + 20, 50, 2);
        assertEquals(1, mResolver.getWaitingCount());
    }

    @Test
    public void latePressFlushesTheWaitingTapOnce() {
        tap(1000, 60, 50, 50, 2);
        // The timer has not run yet, but the next press is already too late to continue.
        mResolver.onDown(1000 + INTER_TAP_MS + 5, 50, 50);
        assertEquals(List.of(1), mActed);
        timeout();
        assertEquals(List.of(1), mActed);
    }

    @Test
    public void dragAfterTheFirstTapDropsIt() {
        tap(1000, 60, 50, 50, 2);
        mResolver.onDown(1150, 50, 50);
        mResolver.onMove(50, 50 + TAP_SLOP + 1);
        mResolver.onUp(1250, 50, 80, 2);
        assertTrue(mActed.isEmpty());
        assertFalse(mScheduler.scheduled);
        assertEquals(0, mResolver.getWaitingCount());
    }

    @Test
    public void longPressAfterTheFirstTapDropsIt() {
        tap(1000, 60, 50, 50, 2);
        tap(1150, TAP_MAX_MS + 100, 50, 50, 2);
        assertTrue(mActed.isEmpty());
        assertEquals(0, mResolver.getWaitingCount());
        timeout();
        assertTrue(mActed.isEmpty());
    }

    @Test
    public void longPressAloneNeverActs() {
        tap(1000, TAP_MAX_MS + 100, 50, 50, 2);
        assertTrue(mActed.isEmpty());
        assertFalse(mScheduler.scheduled);
    }

    @Test
    public void cancelDropsTheWaitingTapAndTheSequence() {
        tap(1000, 60, 50, 50, 2);
        mResolver.cancel(); // scroll, selection, multi touch, long press, detach
        assertFalse(mScheduler.scheduled);
        timeout();
        assertTrue(mActed.isEmpty());

        // The next tap does not continue the cancelled sequence.
        tap(1150, 60, 50, 50, 2);
        assertTrue(mActed.isEmpty());
        assertEquals(1, mResolver.getWaitingCount());
    }

    @Test
    public void configurationChangeToNoHigherActionDropsTheWaitingTap() {
        tap(1000, 60, 50, 50, 2);
        // The properties were reloaded and now only the single tap has an action.
        tap(1150, 60, 50, 50, 1);
        assertTrue(mActed.isEmpty());
        assertFalse(mScheduler.scheduled);
    }

    @Test
    public void timeoutWithoutWaitingTapDoesNothing() {
        mResolver.onTimeout();
        assertTrue(mActed.isEmpty());
    }

    @Test
    public void slowSecondTapIsASeparateSingleTap() {
        tap(1000, 60, 50, 50, 2);
        timeout();
        tap(1500, 60, 50, 50, 2);
        timeout();
        assertEquals(List.of(1, 1), mActed);
    }

}
