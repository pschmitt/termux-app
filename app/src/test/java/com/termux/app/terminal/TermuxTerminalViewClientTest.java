package com.termux.app.terminal;

import android.view.InputDevice;
import android.view.MotionEvent;

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
    public void touchTapShowsSoftKeyboard() {
        // The decision must not depend on terminal mouse tracking (tmux `mouse on`): there is no
        // such input, so a touch tap always shows the keyboard, tracked or not.
        Assert.assertTrue(TermuxTerminalViewClient.shouldShowSoftKeyboardOnSingleTapUp(tap(InputDevice.SOURCE_TOUCHSCREEN)));
    }

    @Test
    public void stylusTapShowsSoftKeyboard() {
        Assert.assertTrue(TermuxTerminalViewClient.shouldShowSoftKeyboardOnSingleTapUp(tap(InputDevice.SOURCE_STYLUS | InputDevice.SOURCE_TOUCHSCREEN)));
    }

    @Test
    public void externalMouseClickDoesNotShowSoftKeyboard() {
        Assert.assertFalse(TermuxTerminalViewClient.shouldShowSoftKeyboardOnSingleTapUp(tap(InputDevice.SOURCE_MOUSE)));
    }

}
