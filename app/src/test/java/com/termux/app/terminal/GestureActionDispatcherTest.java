package com.termux.app.terminal;

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

        @Override public int getSessionCount() { return sessionCount; }
        @Override public void switchSession(boolean forward) { calls.add(forward ? "next" : "previous"); }
        @Override public void openSidebar() { calls.add("sidebar"); }
        @Override public boolean isSoftKeyboardDisabled() { return keyboardDisabled; }
        @Override public boolean isSoftKeyboardVisible() { visibilityQueries++; return keyboardVisible; }
        @Override public void showSoftKeyboard() { calls.add("show"); }
        @Override public void hideSoftKeyboard() { calls.add("hide"); }
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

}
