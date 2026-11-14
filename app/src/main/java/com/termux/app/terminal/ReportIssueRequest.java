package com.termux.app.terminal;

import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A one-shot request from Settings to run the existing "Report issue" flow of the terminal.
 *
 * That flow reports the transcript of the current terminal session, so it has to run in
 * {@link com.termux.app.TermuxActivity}. Settings records the request here and brings the terminal
 * back; the terminal then calls {@link #consume()} and starts the flow, which first asks whether to
 * include debug info and only builds a report that the user reviews. Nothing is collected or sent by
 * making the request, and it is process-local, so other apps cannot trigger it.
 *
 * A request that is not picked up within {@link #TIMEOUT_MS} is dropped, so that it can never fire
 * unexpectedly later.
 */
public final class ReportIssueRequest {

    static final long TIMEOUT_MS = 30_000L;

    interface Clock {
        long uptimeMillis();
    }

    private static final Clock SYSTEM_CLOCK = SystemClock::uptimeMillis;

    private static final long NONE = -1L;
    private static final AtomicLong sRequestedAt = new AtomicLong(NONE);

    private ReportIssueRequest() {}

    /** Ask the terminal to start the Report issue flow the next time it is shown. */
    public static void request() {
        request(SYSTEM_CLOCK);
    }

    /** Whether a request is waiting. It is consumed (and cleared) only when it has not timed out. */
    public static boolean consume() {
        return consume(SYSTEM_CLOCK);
    }

    static void request(Clock clock) {
        sRequestedAt.set(clock.uptimeMillis());
    }

    static boolean consume(Clock clock) {
        long requestedAt = sRequestedAt.getAndSet(NONE);
        if (requestedAt == NONE) return false;
        return clock.uptimeMillis() - requestedAt <= TIMEOUT_MS;
    }

    /** Drop a pending request, for tests. */
    static void clear() {
        sRequestedAt.set(NONE);
    }

}
