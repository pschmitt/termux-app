package com.termux.app.terminal;

import android.view.InputDevice;
import android.view.MotionEvent;

import dev.pschmitt.termux.shared.termux.settings.properties.TerminalGestureAction;

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

    // Volume keys: null is the unconfigured default (the virtual Ctrl/Fn modifiers).

    private static final TermuxTerminalViewClient.VolumeKeyOutcome MODIFIER = TermuxTerminalViewClient.VolumeKeyOutcome.MODIFIER;
    private static final TermuxTerminalViewClient.VolumeKeyOutcome PASS_THROUGH = TermuxTerminalViewClient.VolumeKeyOutcome.PASS_THROUGH;
    private static final TermuxTerminalViewClient.VolumeKeyOutcome CONSUME = TermuxTerminalViewClient.VolumeKeyOutcome.CONSUME;
    private static final TermuxTerminalViewClient.VolumeKeyOutcome PERFORM = TermuxTerminalViewClient.VolumeKeyOutcome.PERFORM;

    @Test
    public void unconfiguredVolumeKeyStaysTheModifierOnKeyDownAndKeyUp() {
        for (boolean down : new boolean[] {true, false}) {
            for (int repeat : new int[] {0, 1, 5}) {
                Assert.assertEquals(MODIFIER, TermuxTerminalViewClient.resolveVolumeKey(null, down, repeat));
            }
        }
    }

    @Test
    public void volumeKeyActionNoneLeavesTheKeyToTheSystemVolume() {
        for (boolean down : new boolean[] {true, false}) {
            Assert.assertEquals(PASS_THROUGH, TermuxTerminalViewClient.resolveVolumeKey(TerminalGestureAction.NONE, down, 0));
            Assert.assertEquals(PASS_THROUGH, TermuxTerminalViewClient.resolveVolumeKey(TerminalGestureAction.NONE, down, 3));
        }
    }

    @Test
    public void configuredVolumeKeyActionRunsOnTheInitialKeyDown() {
        for (TerminalGestureAction action : TerminalGestureAction.values()) {
            if (action == TerminalGestureAction.NONE || action == TerminalGestureAction.RUN_COMMAND) continue;
            Assert.assertEquals(action.getValue(), PERFORM, TermuxTerminalViewClient.resolveVolumeKey(action, true, 0));
        }
    }

    @Test
    public void configuredVolumeKeyActionIsConsumedButNotRunOnKeyUp() {
        // Neither fired again nor leaking to the system volume.
        for (TerminalGestureAction action : TerminalGestureAction.values()) {
            if (action == TerminalGestureAction.NONE || action == TerminalGestureAction.RUN_COMMAND) continue;
            Assert.assertEquals(action.getValue(), CONSUME, TermuxTerminalViewClient.resolveVolumeKey(action, false, 0));
        }
    }

    @Test
    public void onlyFontSizeActionsRepeatWhileTheKeyIsHeld() {
        for (TerminalGestureAction action : TerminalGestureAction.values()) {
            if (action == TerminalGestureAction.NONE || action == TerminalGestureAction.RUN_COMMAND) continue;
            boolean font = action == TerminalGestureAction.FONT_SIZE_UP || action == TerminalGestureAction.FONT_SIZE_DOWN;
            Assert.assertEquals(action.getValue(), font ? PERFORM : CONSUME,
                TermuxTerminalViewClient.resolveVolumeKey(action, true, 4));
        }
    }

    @Test
    public void runCommandIsNotAllowedForVolumeButtons() {
        for (boolean down : new boolean[] {true, false})
            Assert.assertEquals(MODIFIER,
                TermuxTerminalViewClient.resolveVolumeKey(TerminalGestureAction.RUN_COMMAND, down, 0));
    }

    @Test
    public void volumeUpAndDownResolveIndependently() {
        // Each key resolves from its own configured action, so the two mappings never mix.
        Assert.assertEquals(PERFORM, TermuxTerminalViewClient.resolveVolumeKey(TerminalGestureAction.FONT_SIZE_UP, true, 0));
        Assert.assertEquals(MODIFIER, TermuxTerminalViewClient.resolveVolumeKey(null, true, 0));
        Assert.assertEquals(PERFORM, TermuxTerminalViewClient.resolveVolumeKey(TerminalGestureAction.FONT_SIZE_DOWN, true, 0));
        Assert.assertEquals(PASS_THROUGH, TermuxTerminalViewClient.resolveVolumeKey(TerminalGestureAction.NONE, true, 0));
    }

}
