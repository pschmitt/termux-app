package com.termux.shared.termux.settings.properties;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * An action that a terminal tap or an extra keys swipe can be mapped to with the
 * {@link TermuxPropertyConstants#KEY_TERMINAL_SINGLE_TAP_ACTION},
 * {@link TermuxPropertyConstants#KEY_TERMINAL_DOUBLE_TAP_ACTION},
 * {@link TermuxPropertyConstants#KEY_TERMINAL_TRIPLE_TAP_ACTION} and
 * {@link TermuxPropertyConstants#KEY_EXTRA_KEYS_SWIPE_LEFT_ACTION} properties of
 * {@code termux.properties}.
 *
 * This is pure policy without any Android dependency, so that it can be unit tested.
 */
public enum TerminalGestureAction {

    /** Do nothing. For a tap, the terminal handles it alone, for example as a mouse click. */
    NONE("none"),

    /** Show the soft keyboard. Showing it while it is already visible does nothing. */
    SHOW_KEYBOARD("show-keyboard"),

    /** Hide the soft keyboard if it is visible, otherwise show it. */
    TOGGLE_KEYBOARD("toggle-keyboard"),

    /** Switch to the next terminal session, wrapping around after the last one. */
    NEXT_SESSION("next-session"),

    /** Switch to the previous terminal session, wrapping around before the first one. */
    PREVIOUS_SESSION("previous-session"),

    /** Open the sidebar (the drawer) with the list of sessions. */
    OPEN_SIDEBAR("open-sidebar"),

    /**
     * Send keys to the terminal, like Escape or Ctrl+L, given by the {@code *-keys} property of the
     * gesture, for example {@link TermuxPropertyConstants#KEY_TERMINAL_DOUBLE_TAP_KEYS}. Without
     * usable keys the action is {@link #NONE}, see {@link #effective(TerminalGestureAction, boolean)}.
     */
    SEND_KEYS("send-keys");

    /** What to do with the soft keyboard, see {@link #keyboardOperation(boolean, boolean)}. */
    public enum KeyboardOperation { NONE, SHOW, HIDE }

    /** Which way to switch sessions, see {@link #sessionSwitch(int)}. */
    public enum SessionSwitch { NONE, NEXT, PREVIOUS }

    private final String mValue;

    TerminalGestureAction(String value) {
        mValue = value;
    }

    /** The value to use in {@code termux.properties}, like {@code toggle-keyboard}. */
    @NonNull
    public String getValue() {
        return mValue;
    }

    /**
     * Parses the value of a property, ignoring case and surrounding whitespace.
     *
     * @return The action or {@code null} if the value is {@code null}, blank or unknown.
     */
    @Nullable
    public static TerminalGestureAction parse(@Nullable String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (TerminalGestureAction action : values()) {
            if (action.mValue.equals(normalized)) return action;
        }
        return null;
    }

    /** Returns the action for the value, or {@code defaultAction} if it is {@code null} or invalid. */
    @NonNull
    public static TerminalGestureAction parseOrDefault(@Nullable String value, @NonNull TerminalGestureAction defaultAction) {
        TerminalGestureAction action = parse(value);
        return action == null ? defaultAction : action;
    }

    /** All the values that are valid in {@code termux.properties}, comma separated. */
    @NonNull
    public static String getValidValues() {
        StringBuilder builder = new StringBuilder();
        for (TerminalGestureAction action : values()) {
            if (builder.length() > 0) builder.append(", ");
            builder.append(action.mValue);
        }
        return builder.toString();
    }

    /**
     * The action that is really performed for a configured action. {@link #SEND_KEYS} without
     * usable keys (missing, blank or invalid in the properties file) is {@link #NONE}, so that a
     * broken configuration is a no-op that neither animates, consumes a swipe nor delays taps.
     *
     * @param configured The action configured for the gesture.
     * @param hasUsableKeys Whether the {@code *-keys} property of the gesture is a valid sequence.
     */
    @NonNull
    public static TerminalGestureAction effective(@NonNull TerminalGestureAction configured, boolean hasUsableKeys) {
        return configured == SEND_KEYS && !hasUsableKeys ? NONE : configured;
    }

    /**
     * What a soft keyboard action should do now.
     *
     * Nothing is done if the keyboard is disabled by the user's flags, which always win, nor for
     * actions that do not concern the keyboard. A toggle hides a visible keyboard and shows a hidden
     * one, and showing is idempotent.
     *
     * @param keyboardDisabled Whether the soft keyboard is disabled, see
     *                         {@code KeyboardUtils#areDisableSoftKeyboardFlagsSet}.
     * @param keyboardVisible Whether the soft keyboard is currently visible.
     */
    @NonNull
    public KeyboardOperation keyboardOperation(boolean keyboardDisabled, boolean keyboardVisible) {
        if (keyboardDisabled) return KeyboardOperation.NONE;
        switch (this) {
            case SHOW_KEYBOARD:
                return KeyboardOperation.SHOW;
            case TOGGLE_KEYBOARD:
                return keyboardVisible ? KeyboardOperation.HIDE : KeyboardOperation.SHOW;
            default:
                return KeyboardOperation.NONE;
        }
    }

    /**
     * Which way a session action should switch sessions. Nothing is done for other actions and
     * if there is no other session to switch to.
     */
    @NonNull
    public SessionSwitch sessionSwitch(int sessionCount) {
        if (sessionCount <= 1) return SessionSwitch.NONE;
        switch (this) {
            case NEXT_SESSION:
                return SessionSwitch.NEXT;
            case PREVIOUS_SESSION:
                return SessionSwitch.PREVIOUS;
            default:
                return SessionSwitch.NONE;
        }
    }

}
