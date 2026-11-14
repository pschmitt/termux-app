package com.termux.shared.termux.extrakeys;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TerminalKeySequenceTest {

    private static TerminalKeySequence.Step step(String key, boolean ctrl, boolean alt, boolean shift, boolean fn) {
        return new TerminalKeySequence.Step(key, ctrl, alt, shift, fn);
    }

    private static TerminalKeySequence.Step plain(String key) {
        return step(key, false, false, false, false);
    }

    /** What the sequence sends, as the sink sees it. */
    private static List<TerminalKeySequence.Step> sent(String value) {
        TerminalKeySequence sequence = TerminalKeySequence.parse(value);
        assertNotNull("not valid: " + value, sequence);
        List<TerminalKeySequence.Step> steps = new ArrayList<>();
        sequence.send((key, ctrl, alt, shift, fn) -> steps.add(step(key, ctrl, alt, shift, fn)));
        return steps;
    }

    @Test
    public void escapeIsOneKey() {
        assertEquals(Arrays.asList(plain("ESC")), sent("ESC"));
    }

    @Test
    public void ctrlLIsLWithCtrl() {
        assertEquals(Arrays.asList(step("l", true, false, false, false)), sent("CTRL l"));
    }

    @Test
    public void aliasesOfTheExtraKeysAreResolved() {
        assertEquals(Arrays.asList(plain("ESC")), sent("ESCAPE"));
        assertEquals(Arrays.asList(step("l", true, false, false, false)), sent("CONTROL l"));
        assertEquals(Arrays.asList(plain("ENTER"), plain("PGUP"), plain("PGDN"), plain("DEL"), plain("BKSP"),
            plain("LEFT"), plain("RIGHT"), plain("DOWN")),
            sent("RETURN PAGEUP PAGE_DOWN DELETE BACKSPACE LT RT DN"));
        assertEquals(Arrays.asList(step("x", false, false, true, false)), sent("SHFT x"));
        assertEquals(Arrays.asList(step("F1", false, false, false, true)), sent("FUNCTION F1"));
        assertEquals(Arrays.asList(plain("\\"), plain("\""), plain("'")), sent("BACKSLASH QUOTE APOSTROPHE"));
    }

    @Test
    public void modifiersApplyToTheNextKeyOnly() {
        assertEquals(Arrays.asList(
            step("c", true, false, false, false),
            plain("d")), sent("CTRL c d"));
    }

    @Test
    public void severalModifiersCombine() {
        assertEquals(Arrays.asList(step("UP", true, true, true, true)), sent("CTRL ALT SHIFT FN UP"));
        assertEquals(Arrays.asList(step("x", true, true, false, false)), sent("ALT CTRL x"));
    }

    @Test
    public void aSequenceKeepsItsOrder() {
        assertEquals(Arrays.asList(plain("ESC"), plain(":wq"), plain("ENTER")), sent("ESC :wq ENTER"));
        assertEquals(Arrays.asList(plain("TAB"), plain("TAB"), plain("F5")), sent("TAB TAB F5"));
    }

    @Test
    public void everyNamedKeyOfTheExtraKeysIsAccepted() {
        for (String name : ExtraKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS.keySet())
            assertEquals(Arrays.asList(plain(name)), sent(name));
    }

    @Test
    public void whitespaceIsFlexible() {
        assertEquals(Arrays.asList(plain("ESC"), step("l", true, false, false, false)), sent("  ESC \t CTRL   l  "));
    }

    @Test
    public void namesAreCaseSensitiveLikeInTheExtraKeys() {
        // lower case is literal text, as in a macro
        assertEquals(Arrays.asList(plain("esc")), sent("esc"));
        assertEquals(Arrays.asList(plain("ctrl"), plain("l")), sent("ctrl l"));
    }

    @Test
    public void normalizedValueResolvesAliases() {
        assertEquals("ESC", TerminalKeySequence.parse("ESCAPE").getNormalizedValue());
        assertEquals("CTRL l", TerminalKeySequence.parse("  CONTROL   l ").getNormalizedValue());
        // The normalized value parses to the same sequence.
        assertEquals(sent("CONTROL l ESCAPE"), sent(TerminalKeySequence.parse("CONTROL l ESCAPE").getNormalizedValue()));
    }

    @Test
    public void nullAndBlankAreInvalid() {
        assertNull(TerminalKeySequence.parse(null));
        assertNull(TerminalKeySequence.parse(""));
        assertNull(TerminalKeySequence.parse("  \t "));
        assertEquals("no keys given", TerminalKeySequence.getError(""));
    }

    @Test
    public void aModifierWithoutAKeyIsInvalid() {
        assertNull(TerminalKeySequence.parse("CTRL"));
        assertNull(TerminalKeySequence.parse("ESC CTRL"));
        assertNull(TerminalKeySequence.parse("CTRL ALT"));
        assertNotNull(TerminalKeySequence.getError("ESC SHIFT"));
    }

    @Test
    public void appSpecificExtraKeysWordsAreRejected() {
        for (String word : new String[]{"KEYBOARD", "DRAWER", "PASTE", "SCROLL"}) {
            assertNull(TerminalKeySequence.parse(word));
            assertNull(TerminalKeySequence.parse("ESC " + word));
            assertNull(TerminalKeySequence.parse("CTRL " + word));
        }
    }

    @Test
    public void tooManyKeysAreInvalid() {
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < TerminalKeySequence.MAX_TOKENS; i++) many.append("a ");
        assertNotNull(TerminalKeySequence.parse(many.toString()));
        many.append("a");
        assertNull(TerminalKeySequence.parse(many.toString()));
    }

    @Test
    public void validValueHasNoError() {
        assertNull(TerminalKeySequence.getError("CTRL l"));
    }

    @Test
    public void sequenceIsImmutable() {
        TerminalKeySequence sequence = TerminalKeySequence.parse("ESC");
        try {
            sequence.getSteps().add(plain("x"));
            assertTrue("steps can be modified", false);
        } catch (UnsupportedOperationException expected) {
            // fine
        }
    }

    @Test
    public void sendingTwiceSendsTheSameKeys() {
        TerminalKeySequence sequence = TerminalKeySequence.parse("CTRL l");
        List<TerminalKeySequence.Step> first = new ArrayList<>();
        List<TerminalKeySequence.Step> second = new ArrayList<>();
        sequence.send((key, c, a, s, f) -> first.add(step(key, c, a, s, f)));
        sequence.send((key, c, a, s, f) -> second.add(step(key, c, a, s, f)));
        assertEquals(first, second);
    }

}
