package dev.pschmitt.termux.shared.termux.settings.properties;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/** Selects the shell environment for commands started by terminal gestures and extra keys. */
public enum GestureCommandContext {
    /** Follow the {@code nix-session} setting used by ordinary new terminal sessions. */
    SESSION("session"),
    /** Always enter the managed Nix guest. */
    NIX("nix"),
    /** Always run in the native Termux prefix. */
    OUTER("outer");

    private final String value;

    GestureCommandContext(String value) {
        this.value = value;
    }

    @NonNull
    public String getValue() {
        return value;
    }

    @Nullable
    public static GestureCommandContext parse(@Nullable String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (GestureCommandContext context : values())
            if (context.value.equals(normalized)) return context;
        return null;
    }

    @NonNull
    public static GestureCommandContext parseOrDefault(@Nullable String value,
                                                        @NonNull GestureCommandContext defaultContext) {
        GestureCommandContext context = parse(value);
        return context == null ? defaultContext : context;
    }

    /** Whether the command should be started through nixpp in the guest. */
    public boolean shouldEnterNix(boolean sessionStartsInNix) {
        return this == NIX || (this == SESSION && sessionStartsInNix);
    }
}
