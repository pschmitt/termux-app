package com.termux.app.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class ApkFactsTest {

    private static final String CORE_PKG = "dev.pschmitt.termux";
    private static final String UID = "dev.pschmitt.termux";

    private static ApkFacts facts(String pkg, String sharedUid, long version, String... signers) {
        Set<String> signerSet = new HashSet<>(Arrays.asList(signers));
        return new ApkFacts(pkg, sharedUid, version, signerSet);
    }

    private static final ApkFacts CORE = facts(CORE_PKG, UID, 118, "aa");
    private static final ApkFacts INSTALLED_CORE = CORE;

    private static UpdateFailure core(ApkFacts archive) {
        return ApkFacts.checkInstallable(UpdateTarget.CORE, INSTALLED_CORE, archive, CORE);
    }

    @Test
    public void samePackageSameSignerNewerOrEqualVersionIsInstallable() {
        assertNull(core(facts(CORE_PKG, UID, 118, "aa")));
        assertNull(core(facts(CORE_PKG, UID, 119, "aa")));
    }

    @Test
    public void otherPackageIsRefused() {
        assertEquals(UpdateFailure.WRONG_PACKAGE, core(facts("com.termux", UID, 118, "aa")));
        assertEquals(UpdateFailure.WRONG_PACKAGE, core(facts(null, UID, 118, "aa")));
    }

    @Test
    public void missingOrOtherSharedUserIdIsRefused() {
        assertEquals(UpdateFailure.SHARED_USER_MISMATCH, core(facts(CORE_PKG, null, 118, "aa")));
        assertEquals(UpdateFailure.SHARED_USER_MISMATCH, core(facts(CORE_PKG, "com.termux", 118, "aa")));
    }

    @Test
    public void signerMustMatchInstalledAndCore() {
        assertEquals(UpdateFailure.SIGNATURE_MISMATCH, core(facts(CORE_PKG, UID, 118, "bb")));
        assertEquals(UpdateFailure.SIGNATURE_MISMATCH, core(facts(CORE_PKG, UID, 118, "aa", "bb")));
        assertEquals(UpdateFailure.SIGNATURE_MISMATCH, core(facts(CORE_PKG, UID, 118)));
        ApkFacts otherCore = facts(CORE_PKG, UID, 118, "cc");
        assertEquals(UpdateFailure.SIGNATURE_MISMATCH, ApkFacts.checkInstallable(UpdateTarget.CORE,
                INSTALLED_CORE, facts(CORE_PKG, UID, 118, "aa"), otherCore));
    }

    @Test
    public void lowerVersionIsADowngrade() {
        assertEquals(UpdateFailure.DOWNGRADE, core(facts(CORE_PKG, UID, 117, "aa")));
    }

    @Test
    public void packageIsCheckedBeforeUserIdBeforeSignerBeforeVersion() {
        assertEquals(UpdateFailure.WRONG_PACKAGE, core(facts("x", null, 1, "bb")));
        assertEquals(UpdateFailure.SHARED_USER_MISMATCH, core(facts(CORE_PKG, null, 1, "bb")));
        assertEquals(UpdateFailure.SIGNATURE_MISMATCH, core(facts(CORE_PKG, UID, 1, "bb")));
        assertEquals(UpdateFailure.DOWNGRADE, core(facts(CORE_PKG, UID, 1, "aa")));
    }
}
