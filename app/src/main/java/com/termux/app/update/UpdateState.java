package com.termux.app.update;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Immutable snapshot of what the updater is doing for one {@link UpdateTarget}, for the settings screen. */
public final class UpdateState {

    public enum Phase {
        IDLE,
        /** The target is not installed; tapping its Updates row may check for a first-install offer. */
        NOT_INSTALLED,
        CHECKING,
        UP_TO_DATE,
        UNAVAILABLE,
        AVAILABLE,
        NOT_ELIGIBLE,
        CHECK_FAILED,
        DOWNLOADING,
        VERIFYING,
        INSTALLING,
        FAILED;

        /** A check or an update is running; no new one may start. */
        public boolean isBusy() {
            return this == CHECKING || this == DOWNLOADING || this == VERIFYING || this == INSTALLING;
        }
    }

    public static final UpdateState IDLE = new UpdateState(Phase.IDLE, null, 0, -1, null, null);

    public final Phase phase;
    /** The pending offer in AVAILABLE and while it is being installed. */
    @Nullable public final UpdateChecker.Offer offer;
    public final long done;
    /** -1 when unknown. */
    public final long total;
    @Nullable public final UpdateFailure failure;
    /** Short technical detail (never markup) for CHECK_FAILED and FAILED. */
    @Nullable public final String detail;

    UpdateState(@NonNull Phase phase, @Nullable UpdateChecker.Offer offer, long done, long total,
                @Nullable UpdateFailure failure, @Nullable String detail) {
        this.phase = phase;
        this.offer = offer;
        this.done = done;
        this.total = total;
        this.failure = failure;
        this.detail = detail;
    }

    static UpdateState of(Phase phase, @Nullable UpdateChecker.Offer offer) {
        return new UpdateState(phase, offer, 0, -1, null, null);
    }

    static UpdateState progress(UpdateChecker.Offer offer, long done, long total) {
        return new UpdateState(Phase.DOWNLOADING, offer, done, total, null, null);
    }

    static UpdateState failed(@Nullable UpdateChecker.Offer offer, UpdateFailure failure, @Nullable String detail) {
        return new UpdateState(Phase.FAILED, offer, 0, -1, failure, detail);
    }

    static UpdateState checkFailed(@Nullable String detail) {
        return new UpdateState(Phase.CHECK_FAILED, null, 0, -1, null, detail);
    }

}
