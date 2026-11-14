package com.termux.app.terminal;

import android.view.InputDevice;
import android.view.MotionEvent;

import com.termux.shared.termux.settings.properties.TerminalGestureAction;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class TermuxTerminalViewClientTest {

    private static MotionEvent tap(int source) {
        MotionEvent event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 10f, 10f, 0);
        event.setSource(source);
        return event;
    }

    @Test
    public void touchTapActsOnTheSingleTapAction() {
        // The decision must not depend on terminal mouse tracking (tmux `mouse on`): there is no
        // such input, so a touch tap always acts, tracked or not.
        Assert.assertTrue(TermuxTerminalViewClient.shouldActOnSingleTapUp(
            tap(InputDevice.SOURCE_TOUCHSCREEN), 1, TerminalGestureAction.SHOW_KEYBOARD));
        Assert.assertTrue(TermuxTerminalViewClient.shouldActOnSingleTapUp(
            tap(InputDevice.SOURCE_TOUCHSCREEN), 0, TerminalGestureAction.TOGGLE_KEYBOARD));
    }

    @Test
    public void stylusTapActs() {
        Assert.assertTrue(TermuxTerminalViewClient.shouldActOnSingleTapUp(
            tap(InputDevice.SOURCE_STYLUS | InputDevice.SOURCE_TOUCHSCREEN), 1, TerminalGestureAction.OPEN_SIDEBAR));
    }

    @Test
    public void externalMouseClickNeverActs() {
        Assert.assertFalse(TermuxTerminalViewClient.shouldActOnSingleTapUp(
            tap(InputDevice.SOURCE_MOUSE), 1, TerminalGestureAction.SHOW_KEYBOARD));
    }

    @Test
    public void singleTapActionNoneDoesNotAct() {
        Assert.assertFalse(TermuxTerminalViewClient.shouldActOnSingleTapUp(
            tap(InputDevice.SOURCE_TOUCHSCREEN), 1, TerminalGestureAction.NONE));
    }

    @Test
    public void singleTapIsLeftToTheTapCountsWhileAHigherTapActionIsConfigured() {
        // Otherwise a double tap would also run the single tap action.
        Assert.assertFalse(TermuxTerminalViewClient.shouldActOnSingleTapUp(
            tap(InputDevice.SOURCE_TOUCHSCREEN), 2, TerminalGestureAction.SHOW_KEYBOARD));
        Assert.assertFalse(TermuxTerminalViewClient.shouldActOnSingleTapUp(
            tap(InputDevice.SOURCE_TOUCHSCREEN), 3, TerminalGestureAction.SHOW_KEYBOARD));
    }

}
