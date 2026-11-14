package com.termux.app.update;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/** GitHub's {@code compare/<base>...<head>} verdict: what {@code head} is relative to {@code base}. */
public enum CompareStatus {
    AHEAD,
    IDENTICAL,
    BEHIND,
    DIVERGED;

    /** Anything unrecognised counts as {@link #DIVERGED}: nothing can be claimed about it. */
    @NonNull
    public static CompareStatus parse(@Nullable String status) {
        if (status != null) {
            String upper = status.toUpperCase(Locale.ROOT);
            for (CompareStatus value : values()) {
                if (value.name().equals(upper)) return value;
            }
        }
        return DIVERGED;
    }
}
