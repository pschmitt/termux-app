package com.termux.shared.termux.settings.properties;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.termux.shared.termux.settings.properties.TerminalGestureAction.KeyboardOperation;
import com.termux.shared.termux.settings.properties.TerminalGestureAction.SessionSwitch;

public class TerminalGestureActionTest {

    @Test
    public void parsesEveryValue() {
        assertSame(TerminalGestureAction.NONE, TerminalGestureAction.parse("none"));
        assertSame(TerminalGestureAction.SHOW_KEYBOARD, TerminalGestureAction.parse("show-keyboard"));
        assertSame(TerminalGestureAction.TOGGLE_KEYBOARD, TerminalGestureAction.parse("toggle-keyboard"));
        assertSame(TerminalGestureAction.NEXT_SESSION, TerminalGestureAction.parse("next-session"));
        assertSame(TerminalGestureAction.PREVIOUS_SESSION, TerminalGestureAction.parse("previous-session"));
        assertSame(TerminalGestureAction.OPEN_SIDEBAR, TerminalGestureAction.parse("open-sidebar"));
        assertSame(TerminalGestureAction.SEND_KEYS, TerminalGestureAction.parse("send-keys"));
        assertSame(TerminalGestureAction.SEND_KEYS, TerminalGestureAction.parse(" Send-Keys "));
    }

    @Test
    public void sendKeysWithoutUsableKeysIsNone() {
        assertSame(TerminalGestureAction.NONE, TerminalGestureAction.effective(TerminalGestureAction.SEND_KEYS, false));
        assertSame(TerminalGestureAction.SEND_KEYS, TerminalGestureAction.effective(TerminalGestureAction.SEND_KEYS, true));
    }

    @Test
    public void otherActionsDoNotDependOnKeys() {
        for (TerminalGestureAction action : TerminalGestureAction.values()) {
            if (action == TerminalGestureAction.SEND_KEYS) continue;
            assertSame(action, TerminalGestureAction.effective(action, false));
            assertSame(action, TerminalGestureAction.effective(action, true));
        }
    }

    @Test
    public void sendKeysIsNeitherKeyboardNorSessionAction() {
        assertEquals(KeyboardOperation.NONE, TerminalGestureAction.SEND_KEYS.keyboardOperation(false, true));
        assertEquals(SessionSwitch.NONE, TerminalGestureAction.SEND_KEYS.sessionSwitch(5));
    }

    @Test
    public void everyActionRoundTripsThroughItsValue() {
        for (TerminalGestureAction action : TerminalGestureAction.values())
            assertSame(action, TerminalGestureAction.parse(action.getValue()));
    }

    @Test
    public void parseIgnoresCaseAndWhitespace() {
        assertSame(TerminalGestureAction.TOGGLE_KEYBOARD, TerminalGestureAction.parse("  Toggle-Keyboard \t"));
        assertSame(TerminalGestureAction.OPEN_SIDEBAR, TerminalGestureAction.parse("OPEN-SIDEBAR"));
    }

    @Test
    public void parseRejectsNullBlankAndUnknown() {
        assertNull(TerminalGestureAction.parse(null));
        assertNull(TerminalGestureAction.parse(""));
        assertNull(TerminalGestureAction.parse("   "));
        assertNull(TerminalGestureAction.parse("toggle"));
        assertNull(TerminalGestureAction.parse("toggle_keyboard"));
        assertNull(TerminalGestureAction.parse("2"));
    }

    @Test
    public void openMenuParsesAndIsAcceptedForEveryGestureKey() {
        assertSame(TerminalGestureAction.OPEN_MENU, TerminalGestureAction.parse("open-menu"));
        assertSame(TerminalGestureAction.OPEN_MENU, TerminalGestureAction.parse("  Open-Menu "));
        assertEquals("open-menu", TerminalGestureAction.OPEN_MENU.getValue());
        for (String key : new String[] {
            TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_ACTION,
            TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_ACTION,
            TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_ACTION,
            TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_LEFT_ACTION}) {
            assertSame(TerminalGestureAction.OPEN_MENU, TermuxSharedProperties.getGestureActionInternalPropertyValueFromValue(
                key, "open-menu", TerminalGestureAction.NONE));
        }
        // Needs no keys, so it is never turned into none.
        assertSame(TerminalGestureAction.OPEN_MENU, TerminalGestureAction.effective(TerminalGestureAction.OPEN_MENU, false));
    }

    @Test
    public void parseOrDefaultFallsBack() {
        assertSame(TerminalGestureAction.SHOW_KEYBOARD,
            TerminalGestureAction.parseOrDefault("bogus", TerminalGestureAction.SHOW_KEYBOARD));
        assertSame(TerminalGestureAction.NONE,
            TerminalGestureAction.parseOrDefault("none", TerminalGestureAction.SHOW_KEYBOARD));
    }

    @Test
    public void validValuesListsEveryValue() {
        assertEquals("none, show-keyboard, toggle-keyboard, next-session, previous-session, open-sidebar, open-menu, send-keys",
            TerminalGestureAction.getValidValues());
    }

    @Test
    public void showKeyboardAlwaysShowsUnlessDisabled() {
        assertEquals(KeyboardOperation.SHOW, TerminalGestureAction.SHOW_KEYBOARD.keyboardOperation(false, false));
        assertEquals(KeyboardOperation.SHOW, TerminalGestureAction.SHOW_KEYBOARD.keyboardOperation(false, true));
    }

    @Test
    public void toggleKeyboardHidesVisibleAndShowsHidden() {
        assertEquals(KeyboardOperation.SHOW, TerminalGestureAction.TOGGLE_KEYBOARD.keyboardOperation(false, false));
        assertEquals(KeyboardOperation.HIDE, TerminalGestureAction.TOGGLE_KEYBOARD.keyboardOperation(false, true));
    }

    @Test
    public void disabledKeyboardWins() {
        for (boolean visible : new boolean[] {false, true}) {
            assertEquals(KeyboardOperation.NONE, TerminalGestureAction.SHOW_KEYBOARD.keyboardOperation(true, visible));
            assertEquals(KeyboardOperation.NONE, TerminalGestureAction.TOGGLE_KEYBOARD.keyboardOperation(true, visible));
        }
    }

    @Test
    public void nonKeyboardActionsLeaveTheKeyboardAlone() {
        for (TerminalGestureAction action : new TerminalGestureAction[] {
            TerminalGestureAction.NONE, TerminalGestureAction.NEXT_SESSION,
            TerminalGestureAction.PREVIOUS_SESSION, TerminalGestureAction.OPEN_SIDEBAR,
            TerminalGestureAction.OPEN_MENU}) {
            assertEquals(KeyboardOperation.NONE, action.keyboardOperation(false, false));
            assertEquals(KeyboardOperation.NONE, action.keyboardOperation(false, true));
        }
    }

    @Test
    public void sessionActionsSwitchOnlyWithMoreThanOneSession() {
        assertEquals(SessionSwitch.NEXT, TerminalGestureAction.NEXT_SESSION.sessionSwitch(2));
        assertEquals(SessionSwitch.PREVIOUS, TerminalGestureAction.PREVIOUS_SESSION.sessionSwitch(5));
        for (int count : new int[] {-1, 0, 1}) {
            assertEquals(SessionSwitch.NONE, TerminalGestureAction.NEXT_SESSION.sessionSwitch(count));
            assertEquals(SessionSwitch.NONE, TerminalGestureAction.PREVIOUS_SESSION.sessionSwitch(count));
        }
    }

    @Test
    public void otherActionsDoNotSwitchSessions() {
        assertEquals(SessionSwitch.NONE, TerminalGestureAction.NONE.sessionSwitch(3));
        assertEquals(SessionSwitch.NONE, TerminalGestureAction.SHOW_KEYBOARD.sessionSwitch(3));
        assertEquals(SessionSwitch.NONE, TerminalGestureAction.TOGGLE_KEYBOARD.sessionSwitch(3));
        assertEquals(SessionSwitch.NONE, TerminalGestureAction.OPEN_SIDEBAR.sessionSwitch(3));
        assertEquals(SessionSwitch.NONE, TerminalGestureAction.OPEN_MENU.sessionSwitch(3));
    }

    @Test
    public void propertiesConverterAcceptsValidAndMissingValues() {
        // Invalid values are logged through the Android Logger, which is not available here.
        assertSame(TerminalGestureAction.TOGGLE_KEYBOARD, TermuxSharedProperties.getGestureActionInternalPropertyValueFromValue(
            TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_ACTION, "toggle-keyboard", TerminalGestureAction.NONE));
        assertSame(TerminalGestureAction.OPEN_SIDEBAR, TermuxSharedProperties.getGestureActionInternalPropertyValueFromValue(
            TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_ACTION, " Open-Sidebar", TerminalGestureAction.NONE));
        assertSame(TerminalGestureAction.SHOW_KEYBOARD, TermuxSharedProperties.getGestureActionInternalPropertyValueFromValue(
            TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_ACTION, null, TerminalGestureAction.SHOW_KEYBOARD));
    }

    /** SharedProperties only caches primitives and Strings: an enum would silently be dropped and the default used. */
    @Test
    public void loadedValuesAreCacheableByTheLoaderAndParseBack() {
        String[] keys = {
            TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_ACTION, TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_ACTION,
            TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_ACTION, TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_LEFT_ACTION};
        for (String key : keys) {
            for (TerminalGestureAction action : TerminalGestureAction.values()) {
                Object internal = TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(null, key, action.getValue());
                assertTrue(key + " " + action, com.termux.shared.settings.properties.SharedProperties.putToMap(new java.util.HashMap<>(), key, internal));
                assertSame(action, TerminalGestureAction.parse((String) internal));
            }
            Object missing = TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(null, key, null);
            assertTrue(com.termux.shared.settings.properties.SharedProperties.putToMap(new java.util.HashMap<>(), key, missing));
        }
    }

    @Test
    public void propertyKeysDefaultsAndLoading() {
        assertEquals("terminal-single-tap-action", TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_ACTION);
        assertEquals("terminal-double-tap-action", TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_ACTION);
        assertEquals("terminal-triple-tap-action", TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_ACTION);
        assertEquals("extra-keys-swipe-left-action", TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_LEFT_ACTION);

        assertSame(TerminalGestureAction.SHOW_KEYBOARD, TermuxPropertyConstants.DEFAULT_TERMINAL_SINGLE_TAP_ACTION);
        assertSame(TerminalGestureAction.NONE, TermuxPropertyConstants.DEFAULT_TERMINAL_DOUBLE_TAP_ACTION);
        assertSame(TerminalGestureAction.NONE, TermuxPropertyConstants.DEFAULT_TERMINAL_TRIPLE_TAP_ACTION);
        assertSame(TerminalGestureAction.NONE, TermuxPropertyConstants.DEFAULT_EXTRA_KEYS_SWIPE_LEFT_ACTION);

        assertTrue(TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST.contains(TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_ACTION));
        assertTrue(TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST.contains(TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_ACTION));
        assertTrue(TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST.contains(TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_ACTION));
        assertTrue(TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST.contains(TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_LEFT_ACTION));
    }

}
