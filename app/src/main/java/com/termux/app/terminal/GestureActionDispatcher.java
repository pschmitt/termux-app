package com.termux.app.terminal;

import androidx.annotation.NonNull;

import com.termux.shared.termux.settings.properties.TerminalGestureAction;

/**
 * Carries out a {@link TerminalGestureAction} that a tap or a swipe was mapped to, on a
 * {@link Target} that does the actual work, so that the dispatch can be unit tested.
 */
public final class GestureActionDispatcher {

    /** What the actions act on. */
    public interface Target {
        /** The number of terminal sessions. */
        int getSessionCount();

        /** Switch to the next or previous session, wrapping around, like the hardware shortcuts do. */
        void switchSession(boolean forward);

        /** Open the sidebar, if it may be opened now. */
        void openSidebar();

        /** Whether the user's flags disable the soft keyboard. */
        boolean isSoftKeyboardDisabled();

        /** Whether the soft keyboard is visible now. */
        boolean isSoftKeyboardVisible();

        void showSoftKeyboard();

        void hideSoftKeyboard();
    }

    private GestureActionDispatcher() {
    }

    public static void perform(@NonNull TerminalGestureAction action, @NonNull Target target) {
        switch (action) {
            case NEXT_SESSION:
            case PREVIOUS_SESSION:
                // Switching never touches the keyboard.
                switch (action.sessionSwitch(target.getSessionCount())) {
                    case NEXT:
                        target.switchSession(true);
                        break;
                    case PREVIOUS:
                        target.switchSession(false);
                        break;
                    default:
                        break;
                }
                break;

            case OPEN_SIDEBAR:
                target.openSidebar();
                break;

            case SHOW_KEYBOARD:
            case TOGGLE_KEYBOARD:
                boolean disabled = target.isSoftKeyboardDisabled();
                // Do not even ask for the visibility if the keyboard is disabled.
                boolean visible = !disabled && target.isSoftKeyboardVisible();
                switch (action.keyboardOperation(disabled, visible)) {
                    case SHOW:
                        target.showSoftKeyboard();
                        break;
                    case HIDE:
                        target.hideSoftKeyboard();
                        break;
                    default:
                        break;
                }
                break;

            case NONE:
            default:
                break;
        }
    }

}
