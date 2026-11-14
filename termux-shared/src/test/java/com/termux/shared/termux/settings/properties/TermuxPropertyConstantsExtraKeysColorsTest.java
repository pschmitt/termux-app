package com.termux.shared.termux.settings.properties;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TermuxPropertyConstantsExtraKeysColorsTest {

    @Test
    public void extraKeysActiveColorKeyNames() {
        assertEquals("extra-keys-button-active-text-color",
            TermuxPropertyConstants.KEY_EXTRA_KEYS_BUTTON_ACTIVE_TEXT_COLOR);
        assertEquals("extra-keys-button-active-background-color",
            TermuxPropertyConstants.KEY_EXTRA_KEYS_BUTTON_ACTIVE_BACKGROUND_COLOR);
    }

    @Test
    public void extraKeysActiveColorKeysAreLoaded() {
        assertTrue(TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST
            .contains(TermuxPropertyConstants.KEY_EXTRA_KEYS_BUTTON_ACTIVE_TEXT_COLOR));
        assertTrue(TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST
            .contains(TermuxPropertyConstants.KEY_EXTRA_KEYS_BUTTON_ACTIVE_BACKGROUND_COLOR));
    }

    @Test
    public void extraKeysCornerRadiusKeyIsLoaded() {
        assertEquals("extra-keys-button-corner-radius",
            TermuxPropertyConstants.KEY_EXTRA_KEYS_BUTTON_CORNER_RADIUS);
        assertTrue(TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST
            .contains(TermuxPropertyConstants.KEY_EXTRA_KEYS_BUTTON_CORNER_RADIUS));
    }
}
