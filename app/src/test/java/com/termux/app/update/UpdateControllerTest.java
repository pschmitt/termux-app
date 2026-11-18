package com.termux.app.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdateControllerTest {

    @Test
    public void integratedAppRequiresKnownCommitToOfferAnUpdate() {
        assertTrue(UpdateController.requiresKnownCommitToCheck(UpdateTarget.CORE, InstalledBuild.parse(null)));
        assertTrue(UpdateController.requiresKnownCommitToCheck(UpdateTarget.CORE, InstalledBuild.parse("unknown")));
        assertFalse(UpdateController.requiresKnownCommitToCheck(UpdateTarget.CORE,
                InstalledBuild.parse("1111111111111111111111111111111111111111")));
    }
}
