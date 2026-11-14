package com.termux.app.update;

import androidx.annotation.NonNull;

/**
 * Which published build stream an install follows. Only {@link #LATEST} exists for now: the rolling
 * {@code latest} GitHub prerelease that every push to {@code main} republishes (see
 * docs/en/self-update.md).
 */
public enum UpdateChannel {

    LATEST("latest");

    /** Value stored in the preferences. */
    public final String value;

    UpdateChannel(String value) {
        this.value = value;
    }

    /** Unknown or missing values fall back to {@link #LATEST}, the only channel. */
    @NonNull
    public static UpdateChannel fromValue(String value) {
        for (UpdateChannel channel : values()) {
            if (channel.value.equals(value)) return channel;
        }
        return LATEST;
    }

}
