package dev.pschmitt.termux.shared.termux.extrakeys;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A sequence of keys to send to the terminal, written in the same grammar as an extra keys
 * {@code macro}: keys separated by whitespace, for example {@code ESC}, {@code CTRL l} or
 * {@code ESC :wq ENTER}.
 *
 * <ul>
 *   <li>The named keys of {@link ExtraKeysConstants#PRIMARY_KEY_CODES_FOR_STRINGS} (for example
 *   {@code ESC}, {@code TAB}, {@code ENTER}, {@code UP}, {@code PGUP}, {@code F1}) and the aliases of
 *   {@link ExtraKeysConstants#CONTROL_CHARS_ALIASES} (for example {@code ESCAPE}, {@code RETURN},
 *   {@code PAGEUP}) are the same as on the extra keys row, and are case sensitive.</li>
 *   <li>{@code CTRL}, {@code ALT}, {@code SHIFT} and {@code FN} apply to the next key only, like in
 *   a macro. They have to be followed by a key.</li>
 *   <li>Any other word is literal text that is typed, a character at a time. {@code CTRL l} sends
 *   Ctrl+L and {@code CTRL ALT l} sends Ctrl+Alt+L.</li>
 *   <li>The app specific words {@code KEYBOARD}, {@code DRAWER}, {@code PASTE} and {@code SCROLL}
 *   are not terminal input and are rejected.</li>
 * </ul>
 *
 * This is pure policy without any Android dependency, so that it can be unit tested. A sequence is
 * immutable, and it is sent through a {@link Sink} so that no modifier state is ever left behind:
 * each step carries its own modifiers.
 */
public final class TerminalKeySequence {

    /** The most keys and modifiers in one sequence. */
    public static final int MAX_TOKENS = 64;

    /** Words of the extra keys row that are not keys of the terminal. */
    private static final List<String> RESERVED_WORDS = Collections.unmodifiableList(
        Arrays.asList("KEYBOARD", "DRAWER", "PASTE", "SCROLL"));

    /** Receives the keys of a sequence, like {@code TerminalExtraKeys#sendTerminalKey}. */
    public interface Sink {
        /**
         * @param key A named key like {@code ESC}, or literal text.
         * @param ctrlDown Whether Ctrl is held for this key only.
         */
        void sendKey(@NonNull String key, boolean ctrlDown, boolean altDown, boolean shiftDown, boolean fnDown);
    }

    /** A key to send, with the modifiers that are held for it. */
    public static final class Step {
        @NonNull public final String key;
        public final boolean ctrl;
        public final boolean alt;
        public final boolean shift;
        public final boolean fn;

        public Step(@NonNull String key, boolean ctrl, boolean alt, boolean shift, boolean fn) {
            this.key = key;
            this.ctrl = ctrl;
            this.alt = alt;
            this.shift = shift;
            this.fn = fn;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Step)) return false;
            Step other = (Step) o;
            return key.equals(other.key) && ctrl == other.ctrl && alt == other.alt && shift == other.shift && fn == other.fn;
        }

        @Override
        public int hashCode() {
            return key.hashCode() * 31 + (ctrl ? 8 : 0) + (alt ? 4 : 0) + (shift ? 2 : 0) + (fn ? 1 : 0);
        }

        @NonNull
        @Override
        public String toString() {
            return (ctrl ? "CTRL+" : "") + (alt ? "ALT+" : "") + (shift ? "SHIFT+" : "") + (fn ? "FN+" : "") + key;
        }
    }

    private final List<Step> mSteps;
    private final String mNormalized;

    private TerminalKeySequence(List<Step> steps, String normalized) {
        mSteps = Collections.unmodifiableList(steps);
        mNormalized = normalized;
    }

    /** The keys to send, in order. Never empty. */
    @NonNull
    public List<Step> getSteps() {
        return mSteps;
    }

    /** The sequence with the aliases resolved, as it would be written in a macro. */
    @NonNull
    public String getNormalizedValue() {
        return mNormalized;
    }

    /** Sends every step to the sink, in order. Each step carries its own modifiers. */
    public void send(@NonNull Sink sink) {
        for (Step step : mSteps)
            sink.sendKey(step.key, step.ctrl, step.alt, step.shift, step.fn);
    }

    /** Parses a sequence, returning {@code null} if the value is {@code null}, blank or invalid, see {@link #getError(String)}. */
    @Nullable
    public static TerminalKeySequence parse(@Nullable String value) {
        return parseInternal(value, null);
    }

    /**
     * Why a value is not a valid sequence, in words for a log message.
     *
     * @return The reason, or {@code null} if the value is valid.
     */
    @Nullable
    public static String getError(@Nullable String value) {
        String[] error = new String[1];
        return parseInternal(value, error) == null ? error[0] : null;
    }

    @Nullable
    private static TerminalKeySequence parseInternal(@Nullable String value, @Nullable String[] error) {
        if (value == null || value.trim().isEmpty()) {
            return fail(error, "no keys given");
        }

        String[] tokens = value.trim().split("\\s+");
        if (tokens.length > MAX_TOKENS)
            return fail(error, "more than " + MAX_TOKENS + " keys");

        List<Step> steps = new ArrayList<>();
        StringBuilder normalized = new StringBuilder();
        boolean ctrl = false, alt = false, shift = false, fn = false;
        boolean pendingModifier = false;

        for (String token : tokens) {
            String key = ExtraKeyButton.replaceAlias(ExtraKeysConstants.CONTROL_CHARS_ALIASES, token);
            if (normalized.length() > 0) normalized.append(' ');
            normalized.append(key);

            if (RESERVED_WORDS.contains(key)) {
                return fail(error, "\"" + token + "\" is an extra keys row action, not a terminal key");
            } else if (SpecialButton.CTRL.getKey().equals(key)) {
                ctrl = true;
                pendingModifier = true;
            } else if (SpecialButton.ALT.getKey().equals(key)) {
                alt = true;
                pendingModifier = true;
            } else if (SpecialButton.SHIFT.getKey().equals(key)) {
                shift = true;
                pendingModifier = true;
            } else if (SpecialButton.FN.getKey().equals(key)) {
                fn = true;
                pendingModifier = true;
            } else {
                steps.add(new Step(key, ctrl, alt, shift, fn));
                ctrl = false; alt = false; shift = false; fn = false;
                pendingModifier = false;
            }
        }

        if (pendingModifier)
            return fail(error, "the modifier at the end is not followed by a key");

        return new TerminalKeySequence(steps, normalized.toString());
    }

    @Nullable
    private static TerminalKeySequence fail(@Nullable String[] error, String message) {
        if (error != null) error[0] = message;
        return null;
    }

}
