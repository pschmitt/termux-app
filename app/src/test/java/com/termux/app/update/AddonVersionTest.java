package com.termux.app.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class AddonVersionTest {

    @Test
    public void parsesOptionalTagPrefixAndBuildMetadata() {
        assertEquals(0, AddonVersion.parse("v1.2.3+github.debug").compareTo(AddonVersion.parse("1.2.3")));
        assertNull(AddonVersion.parse("1.02.3"));
        assertNull(AddonVersion.parse("not-a-version"));
    }

    @Test
    public void comparesNumericComponentsWithoutOverflow() {
        assertEquals(1, Integer.signum(AddonVersion.parse("999999999999999999999.0.0")
                .compareTo(AddonVersion.parse("999999999999999999998.999.999"))));
    }

    @Test
    public void candidateOlderThanInstalledIsRejectedButEqualOrUnknownIsReinstallable() {
        assertEquals(true, AddonVersion.isCandidateOlder("1.0.0", "0.9.0"));
        assertEquals(false, AddonVersion.isCandidateOlder("1.0.0", "1.0.0+other-build"));
        assertEquals(false, AddonVersion.isCandidateOlder("local", "1.1.0"));
    }

    @Test
    public void followsSemanticPrereleasePrecedence() {
        assertEquals(-1, Integer.signum(AddonVersion.parse("1.0.0-alpha.2").compareTo(AddonVersion.parse("1.0.0-alpha.10"))));
        assertEquals(-1, Integer.signum(AddonVersion.parse("1.0.0-rc.1").compareTo(AddonVersion.parse("1.0.0"))));
        assertEquals(1, Integer.signum(AddonVersion.parse("1.0.0-alpha.1").compareTo(AddonVersion.parse("1.0.0-alpha"))));
    }
}
