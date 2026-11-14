package com.termux.app.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class ApkFactsTest {

    private static Set<String> set(String... values) {
        Set<String> result = new HashSet<>();
        Collections.addAll(result, values);
        return result;
    }

    private static final ApkFacts INSTALLED = new ApkFacts("dev.pschmitt.termux", 118, set("aa"));

    @Test
    public void samePackageSameSignerNewerOrEqualVersionIsInstallable() {
        assertNull(ApkFacts.checkInstallable(INSTALLED, new ApkFacts("dev.pschmitt.termux", 118, set("aa"))));
        assertNull(ApkFacts.checkInstallable(INSTALLED, new ApkFacts("dev.pschmitt.termux", 119, set("aa"))));
    }

    @Test
    public void otherPackageIsRefused() {
        assertEquals(UpdateFailure.WRONG_PACKAGE,
                ApkFacts.checkInstallable(INSTALLED, new ApkFacts("com.termux", 118, set("aa"))));
        assertEquals(UpdateFailure.WRONG_PACKAGE,
                ApkFacts.checkInstallable(INSTALLED, new ApkFacts(null, 118, set("aa"))));
    }

    @Test
    public void otherOrMissingSignerIsRefused() {
        assertEquals(UpdateFailure.SIGNATURE_MISMATCH,
                ApkFacts.checkInstallable(INSTALLED, new ApkFacts("dev.pschmitt.termux", 118, set("bb"))));
        assertEquals(UpdateFailure.SIGNATURE_MISMATCH,
                ApkFacts.checkInstallable(INSTALLED, new ApkFacts("dev.pschmitt.termux", 118, set("aa", "bb"))));
        assertEquals(UpdateFailure.SIGNATURE_MISMATCH,
                ApkFacts.checkInstallable(INSTALLED, new ApkFacts("dev.pschmitt.termux", 118, set())));
        assertEquals(UpdateFailure.SIGNATURE_MISMATCH, ApkFacts.checkInstallable(
                new ApkFacts("dev.pschmitt.termux", 118, set()), new ApkFacts("dev.pschmitt.termux", 118, set())));
    }

    @Test
    public void lowerVersionIsADowngrade() {
        assertEquals(UpdateFailure.DOWNGRADE,
                ApkFacts.checkInstallable(INSTALLED, new ApkFacts("dev.pschmitt.termux", 117, set("aa"))));
    }

    @Test
    public void packageIsCheckedBeforeSignerBeforeVersion() {
        assertEquals(UpdateFailure.WRONG_PACKAGE,
                ApkFacts.checkInstallable(INSTALLED, new ApkFacts("x", 1, set("bb"))));
        assertEquals(UpdateFailure.SIGNATURE_MISMATCH,
                ApkFacts.checkInstallable(INSTALLED, new ApkFacts("dev.pschmitt.termux", 1, set("bb"))));
    }
}
