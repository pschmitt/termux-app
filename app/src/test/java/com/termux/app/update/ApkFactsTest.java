package com.termux.app.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class ApkFactsTest {

    private static final String CORE_PKG = "dev.pschmitt.termux";
    private static final String API_PKG = "dev.pschmitt.termux.api";
    private static final String TASKER_PKG = "dev.pschmitt.termux.tasker";
    private static final String UID = "dev.pschmitt.termux";

    private static Set<String> set(String... values) {
        Set<String> result = new HashSet<>();
        Collections.addAll(result, values);
        return result;
    }

    private static ApkFacts facts(String pkg, String sharedUid, long version, String... signers) {
        return new ApkFacts(pkg, sharedUid, version, set(signers));
    }

    private static final ApkFacts CORE = facts(CORE_PKG, UID, 118, "aa");
    private static final ApkFacts INSTALLED_CORE = CORE;
    private static final ApkFacts INSTALLED_API = facts(API_PKG, UID, 5, "aa");

    private static UpdateFailure core(ApkFacts archive) {
        return ApkFacts.checkInstallable(UpdateTarget.CORE, INSTALLED_CORE, archive, CORE);
    }

    private static UpdateFailure api(ApkFacts archive) {
        return ApkFacts.checkInstallable(UpdateTarget.API, INSTALLED_API, archive, CORE);
    }

    @Test
    public void samePackageSameSignerNewerOrEqualVersionIsInstallable() {
        assertNull(core(facts(CORE_PKG, UID, 118, "aa")));
        assertNull(core(facts(CORE_PKG, UID, 119, "aa")));
        assertNull(api(facts(API_PKG, UID, 5, "aa")));
        assertNull(api(facts(API_PKG, UID, 6, "aa")));
    }

    @Test
    public void otherPackageIsRefused() {
        assertEquals(UpdateFailure.WRONG_PACKAGE, core(facts("com.termux", UID, 118, "aa")));
        assertEquals(UpdateFailure.WRONG_PACKAGE, core(facts(null, UID, 118, "aa")));
    }

    @Test
    public void aTargetNeverAcceptsAnotherTargetsApk() {
        assertEquals(UpdateFailure.WRONG_PACKAGE, api(facts(CORE_PKG, UID, 118, "aa")));
        assertEquals(UpdateFailure.WRONG_PACKAGE, api(facts(TASKER_PKG, UID, 9, "aa")));
        assertEquals(UpdateFailure.WRONG_PACKAGE, core(facts(API_PKG, UID, 118, "aa")));
        assertEquals(UpdateFailure.WRONG_PACKAGE, ApkFacts.checkInstallable(UpdateTarget.TASKER,
                facts(TASKER_PKG, UID, 9, "aa"), facts(API_PKG, UID, 9, "aa"), CORE));
    }

    @Test
    public void installedCopyMustBeTheTargetsPackageToo() {
        assertEquals(UpdateFailure.WRONG_PACKAGE, ApkFacts.checkInstallable(UpdateTarget.API,
                facts(TASKER_PKG, UID, 5, "aa"), facts(API_PKG, UID, 5, "aa"), CORE));
    }

    @Test
    public void missingOrOtherSharedUserIdIsRefused() {
        assertEquals(UpdateFailure.SHARED_USER_MISMATCH, api(facts(API_PKG, null, 5, "aa")));
        assertEquals(UpdateFailure.SHARED_USER_MISMATCH, api(facts(API_PKG, "com.termux", 5, "aa")));
    }

    @Test
    public void signerMustMatchInstalledAndCore() {
        assertEquals(UpdateFailure.SIGNATURE_MISMATCH, core(facts(CORE_PKG, UID, 118, "bb")));
        assertEquals(UpdateFailure.SIGNATURE_MISMATCH, core(facts(CORE_PKG, UID, 118, "aa", "bb")));
        assertEquals(UpdateFailure.SIGNATURE_MISMATCH, core(facts(CORE_PKG, UID, 118)));
        assertEquals(UpdateFailure.SIGNATURE_MISMATCH, api(facts(API_PKG, UID, 5, "bb")));
        // Archive equals the installed companion but not this app: still refused.
        ApkFacts otherCore = facts(CORE_PKG, UID, 118, "cc");
        assertEquals(UpdateFailure.SIGNATURE_MISMATCH, ApkFacts.checkInstallable(UpdateTarget.API,
                INSTALLED_API, facts(API_PKG, UID, 5, "aa"), otherCore));
        // Nothing signed on any side is never fine.
        assertEquals(UpdateFailure.SIGNATURE_MISMATCH, ApkFacts.checkInstallable(UpdateTarget.CORE,
                facts(CORE_PKG, UID, 118), facts(CORE_PKG, UID, 118), facts(CORE_PKG, UID, 118)));
    }

    @Test
    public void firstInstallRequiresForkPackageSharedUidAndCoreSigner() {
        ApkFacts archive = facts(UpdateTarget.BOOT.packageName, UID, 1000, "aa");
        assertNull(ApkFacts.checkInstallable(UpdateTarget.BOOT, null, archive, CORE));
        assertEquals(UpdateFailure.WRONG_PACKAGE, ApkFacts.checkInstallable(UpdateTarget.BOOT, null,
                facts(UpdateTarget.WIDGET.packageName, UID, 1000, "aa"), CORE));
        assertEquals(UpdateFailure.SHARED_USER_MISMATCH, ApkFacts.checkInstallable(UpdateTarget.BOOT, null,
                facts(UpdateTarget.BOOT.packageName, "com.termux", 1000, "aa"), CORE));
        assertEquals(UpdateFailure.SIGNATURE_MISMATCH, ApkFacts.checkInstallable(UpdateTarget.BOOT, null,
                facts(UpdateTarget.BOOT.packageName, UID, 1000, "bb"), CORE));
    }

    @Test
    public void lowerVersionIsADowngrade() {
        assertEquals(UpdateFailure.DOWNGRADE, core(facts(CORE_PKG, UID, 117, "aa")));
        assertEquals(UpdateFailure.DOWNGRADE, api(facts(API_PKG, UID, 4, "aa")));
    }

    @Test
    public void packageIsCheckedBeforeUserIdBeforeSignerBeforeVersion() {
        assertEquals(UpdateFailure.WRONG_PACKAGE, core(facts("x", null, 1, "bb")));
        assertEquals(UpdateFailure.SHARED_USER_MISMATCH, core(facts(CORE_PKG, null, 1, "bb")));
        assertEquals(UpdateFailure.SIGNATURE_MISMATCH, core(facts(CORE_PKG, UID, 1, "bb")));
        assertEquals(UpdateFailure.DOWNGRADE, core(facts(CORE_PKG, UID, 1, "aa")));
    }
}
