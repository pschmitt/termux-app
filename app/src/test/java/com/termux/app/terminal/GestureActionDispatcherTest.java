package com.termux.app.terminal;

import androidx.annotation.NonNull;

import com.termux.shared.termux.extrakeys.TerminalKeySequence;
import com.termux.shared.termux.settings.properties.TerminalGestureAction;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GestureActionDispatcherTest {

    private static final class FakeTarget implements GestureActionDispatcher.Target {
        int sessionCount = 3;
        boolean keyboardDisabled;
        boolean keyboardVisible;
        int visibilityQueries;
        final List<String> calls = new ArrayList<>();
        final List<TerminalKeySequence.Step> sent = new ArrayList<>();

        @Override public int getSessionCount() { return sessionCount; }
        @Override public void switchSession(boolean forward) { calls.add(forward ? "next" : "previous"); }
        @Override public void openSidebar() { calls.add("sidebar"); }
        @Override public void openMenu() { calls.add("menu"); }
        @Override public boolean isSoftKeyboardDisabled() { return keyboardDisabled; }
        @Override public boolean isSoftKeyboardVisible() { visibilityQueries++; return keyboardVisible; }
        @Override public void showSoftKeyboard() { calls.add("show"); }
        @Override public void hideSoftKeyboard() { calls.add("hide"); }
        @Override public void sendKeys(@NonNull TerminalKeySequence keys) {
            calls.add("keys");
            keys.send((key, ctrl, alt, shift, fn) -> sent.add(new TerminalKeySequence.Step(key, ctrl, alt, shift, fn)));
        }
    }

    private FakeTarget mTarget;

    @Before
    public void setUp() {
        mTarget = new FakeTarget();
    }

    @Test
    public void noneDoesNothing() {
        GestureActionDispatcher.perform(TerminalGestureAction.NONE, mTarget);
        assertTrue(mTarget.calls.isEmpty());
    }

    @Test
    public void nextAndPreviousSessionSwitchSessions() {
        GestureActionDispatcher.perform(TerminalGestureAction.NEXT_SESSION, mTarget);
        GestureActionDispatcher.perform(TerminalGestureAction.PREVIOUS_SESSION, mTarget);
        assertEquals(List.of("next", "previous"), mTarget.calls);
    }

    @Test
    public void sessionActionsAreHarmlessWithOneOrNoSession() {
        for (int count : new int[] {0, 1}) {
            mTarget.sessionCount = count;
            GestureActionDispatcher.perform(TerminalGestureAction.NEXT_SESSION, mTarget);
            GestureActionDispatcher.perform(TerminalGestureAction.PREVIOUS_SESSION, mTarget);
        }
        assertTrue(mTarget.calls.isEmpty());
    }

    @Test
    public void sessionActionsWorkWithTheKeyboardDisabled() {
        mTarget.keyboardDisabled = true;
        GestureActionDispatcher.perform(TerminalGestureAction.NEXT_SESSION, mTarget);
        assertEquals(List.of("next"), mTarget.calls);
    }

    @Test
    public void openSidebarOpensTheSidebar() {
        GestureActionDispatcher.perform(TerminalGestureAction.OPEN_SIDEBAR, mTarget);
        assertEquals(List.of("sidebar"), mTarget.calls);
    }

    @Test
    public void openSidebarWorksWithTheKeyboardDisabled() {
        mTarget.keyboardDisabled = true;
        GestureActionDispatcher.perform(TerminalGestureAction.OPEN_SIDEBAR, mTarget);
        assertEquals(List.of("sidebar"), mTarget.calls);
    }

    @Test
    public void openMenuOpensTheMenuOnlyAndNeverTouchesTheKeyboard() {
        GestureActionDispatcher.perform(TerminalGestureAction.OPEN_MENU, mTarget);
        assertEquals(List.of("menu"), mTarget.calls);
        assertEquals(0, mTarget.visibilityQueries);
    }

    @Test
    public void openMenuWorksWithTheKeyboardDisabledOrASingleSession() {
        mTarget.keyboardDisabled = true;
        mTarget.sessionCount = 1;
        GestureActionDispatcher.perform(TerminalGestureAction.OPEN_MENU, mTarget);
        assertEquals(List.of("menu"), mTarget.calls);
    }

    @Test
    public void showKeyboardShowsEvenIfAlreadyVisible() {
        GestureActionDispatcher.perform(TerminalGestureAction.SHOW_KEYBOARD, mTarget);
        mTarget.keyboardVisible = true;
        GestureActionDispatcher.perform(TerminalGestureAction.SHOW_KEYBOARD, mTarget);
        assertEquals(List.of("show", "show"), mTarget.calls);
    }

    @Test
    public void toggleKeyboardShowsWhenHiddenAndHidesWhenVisible() {
        GestureActionDispatcher.perform(TerminalGestureAction.TOGGLE_KEYBOARD, mTarget);
        mTarget.keyboardVisible = true;
        GestureActionDispatcher.perform(TerminalGestureAction.TOGGLE_KEYBOARD, mTarget);
        assertEquals(List.of("show", "hide"), mTarget.calls);
    }

    @Test
    public void disabledKeyboardWinsOverKeyboardActions() {
        mTarget.keyboardDisabled = true;
        GestureActionDispatcher.perform(TerminalGestureAction.SHOW_KEYBOARD, mTarget);
        GestureActionDispatcher.perform(TerminalGestureAction.TOGGLE_KEYBOARD, mTarget);
        mTarget.keyboardVisible = true;
        GestureActionDispatcher.perform(TerminalGestureAction.TOGGLE_KEYBOARD, mTarget);
        assertTrue(mTarget.calls.isEmpty());
        assertEquals(0, mTarget.visibilityQueries);
    }


    @Test
    public void sendKeysSendsEscape() {
        GestureActionDispatcher.perform(TerminalGestureAction.SEND_KEYS, TerminalKeySequence.parse("ESC"), mTarget);
        assertEquals(java.util.Arrays.asList("keys"), mTarget.calls);
        assertEquals(java.util.Arrays.asList(new TerminalKeySequence.Step("ESC", false, false, false, false)), mTarget.sent);
    }

    @Test
    public void sendKeysSendsCtrlL() {
        GestureActionDispatcher.perform(TerminalGestureAction.SEND_KEYS, TerminalKeySequence.parse("CTRL l"), mTarget);
        assertEquals(java.util.Arrays.asList(new TerminalKeySequence.Step("l", true, false, false, false)), mTarget.sent);
    }

    @Test
    public void sendKeysSendsTheSequenceInOrderWithPerKeyModifiers() {
        GestureActionDispatcher.perform(TerminalGestureAction.SEND_KEYS,
            TerminalKeySequence.parse("ESC :wq ENTER CTRL ALT x UP"), mTarget);
        assertEquals(java.util.Arrays.asList(
            new TerminalKeySequence.Step("ESC", false, false, false, false),
            new TerminalKeySequence.Step(":wq", false, false, false, false),
            new TerminalKeySequence.Step("ENTER", false, false, false, false),
            new TerminalKeySequence.Step("x", true, true, false, false),
            new TerminalKeySequence.Step("UP", false, false, false, false)), mTarget.sent);
    }

    @Test
    public void sendKeysWithoutKeysDoesNothing() {
        GestureActionDispatcher.perform(TerminalGestureAction.SEND_KEYS, null, mTarget);
        GestureActionDispatcher.perform(TerminalGestureAction.SEND_KEYS, mTarget);
        assertTrue(mTarget.calls.isEmpty());
        assertTrue(mTarget.sent.isEmpty());
    }

    @Test
    public void otherActionsIgnoreKeys() {
        GestureActionDispatcher.perform(TerminalGestureAction.NEXT_SESSION, TerminalKeySequence.parse("ESC"), mTarget);
        assertEquals(java.util.Arrays.asList("next"), mTarget.calls);
        assertTrue(mTarget.sent.isEmpty());
    }

    @Test
    public void sendKeysNeverTouchesKeyboardOrSessions() {
        GestureActionDispatcher.perform(TerminalGestureAction.SEND_KEYS, TerminalKeySequence.parse("ESC"), mTarget);
        assertEquals(0, mTarget.visibilityQueries);
        assertEquals(java.util.Arrays.asList("keys"), mTarget.calls);
    }

}
