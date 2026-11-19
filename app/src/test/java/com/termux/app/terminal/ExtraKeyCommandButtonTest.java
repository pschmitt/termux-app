package com.termux.app.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeyButton;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysConstants;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysInfo;

import org.json.JSONException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ExtraKeyCommandButtonTest {

    @Test
    public void parsesCommandButtonWithoutTurningCommandIntoKeyInput() throws Exception {
        ExtraKeysInfo info = new ExtraKeysInfo("[[{\"command\":\"printf 'two words'\",\"display\":\"RUN\"}]]",
            "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES);
        ExtraKeyButton button = info.getMatrix()[0][0];
        assertEquals("printf 'two words'", button.getCommand());
        assertEquals("RUN", button.getDisplay());
        assertEquals("", button.getKey());
        assertNull(button.getPopup());
    }

    @Test(expected = JSONException.class)
    public void rejectsCommandCombinedWithKey() throws Exception {
        new ExtraKeysInfo("[[{\"key\":\"ESC\",\"command\":\"pwd\"}]]",
            "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES);
    }

    @Test(expected = JSONException.class)
    public void rejectsBlankCommand() throws Exception {
        new ExtraKeysInfo("[[{\"command\":\"  \"}]]",
            "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES);
    }
}
