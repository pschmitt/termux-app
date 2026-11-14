package com.termux.shared.termux.extrakeys;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ExtraKeysColorParserTest {

    private static final int DEFAULT = 0xFFFF0000;

    @Test
    public void parsesOpaqueSixDigitColors() {
        assertEquals(Integer.valueOf(0xFFD8B4FE), ExtraKeysColorParser.parseColor("#D8B4FE"));
        assertEquals(Integer.valueOf(0xFF3B1D6A), ExtraKeysColorParser.parseColor("#3b1d6a"));
        assertEquals(Integer.valueOf(0xFF000000), ExtraKeysColorParser.parseColor("#000000"));
        assertEquals(Integer.valueOf(0xFFFFFFFF), ExtraKeysColorParser.parseColor("#ffffff"));
    }

    @Test
    public void parsesAlphaColors() {
        assertEquals(Integer.valueOf(0x80D8B4FE), ExtraKeysColorParser.parseColor("#80D8B4FE"));
        assertEquals(Integer.valueOf(0x00000000), ExtraKeysColorParser.parseColor("#00000000"));
        assertEquals(Integer.valueOf(0xFF3B1D6A), ExtraKeysColorParser.parseColor("#FF3B1D6A"));
    }

    @Test
    public void expandsShortColors() {
        assertEquals(Integer.valueOf(0xFFAA33FF), ExtraKeysColorParser.parseColor("#A3F"));
        assertEquals(Integer.valueOf(0xFF000000), ExtraKeysColorParser.parseColor("#000"));
    }

    @Test
    public void ignoresSurroundingWhitespace() {
        assertEquals(Integer.valueOf(0xFFD8B4FE), ExtraKeysColorParser.parseColor("  #D8B4FE \t"));
    }

    @Test
    public void rejectsMissingOrEmptyValues() {
        assertNull(ExtraKeysColorParser.parseColor(null));
        assertNull(ExtraKeysColorParser.parseColor(""));
        assertNull(ExtraKeysColorParser.parseColor("   "));
        assertNull(ExtraKeysColorParser.parseColor("#"));
    }

    @Test
    public void rejectsInvalidValues() {
        assertNull(ExtraKeysColorParser.parseColor("D8B4FE"));
        assertNull(ExtraKeysColorParser.parseColor("#D8B4F"));
        assertNull(ExtraKeysColorParser.parseColor("#D8B4FE0"));
        assertNull(ExtraKeysColorParser.parseColor("#D8B4FE001"));
        assertNull(ExtraKeysColorParser.parseColor("#GGGGGG"));
        assertNull(ExtraKeysColorParser.parseColor("#D8B4FEZZ"));
        assertNull(ExtraKeysColorParser.parseColor("purple"));
        assertNull(ExtraKeysColorParser.parseColor("0xD8B4FE"));
        assertNull(ExtraKeysColorParser.parseColor("#-1B4FE"));
        assertNull(ExtraKeysColorParser.parseColor("#+8B4FE"));
        assertNull(ExtraKeysColorParser.parseColor("#１２３"));
    }

    @Test
    public void fallsBackToDefaultWhenMissingOrInvalid() {
        assertEquals(DEFAULT, ExtraKeysColorParser.parseColor(null, DEFAULT));
        assertEquals(DEFAULT, ExtraKeysColorParser.parseColor("", DEFAULT));
        assertEquals(DEFAULT, ExtraKeysColorParser.parseColor("not-a-color", DEFAULT));
        assertEquals(0xFFD8B4FE, ExtraKeysColorParser.parseColor("#D8B4FE", DEFAULT));
    }
}
