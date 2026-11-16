package com.termux.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;

@RunWith(RobolectricTestRunner.class)
public class AdbShellAuthorizationsTest {
    private static final String CERTIFICATE =
        "MIIBczCCARmgAwIBAgIUeGRAJvAwkiG1hrDYoBnRokqjBs8wCgYIKoZIzj0EAwIw" +
        "DzENMAsGA1UEAwwEVGVzdDAeFw0yNjExMTYxNjM4MTNaFw0yNjExMTcxNjM4MTNa" +
        "MA8xDTALBgNVBAMMBFRlc3QwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAARqy4TI" +
        "w4NEshlTuu2chY+R1y0SzTUaWqOZMCz9Ggh4D47htiNXNdRj/FF6E5maeiuYx/3v" +
        "xBkOle4Ad8EmSOyNo1MwUTAdBgNVHQ4EFgQU3cML+mCfbgZCL3NKpTbx8wXYIGQw" +
        "HwYDVR0jBBgwFoAU3cML+mCfbgZCL3NKpTbx8wXYIGQwDwYDVR0TAQH/BAUwAwEB" +
        "/zAKBggqhkjOPQQDAgNIADBFAiA6t6b/D1G3yECb7PI5UmN4njYv7FgRCkHSJ22l" +
        "sCBsJAIhALzcnVaMGf3c2TIq5UT1J6iqNC9P4eSPMYbSmTbnIjZ7";

    private X509Certificate certificate;
    private AdbShellAuthorizations authorizations;

    @Before
    public void setUp() throws Exception {
        RuntimeEnvironment.getApplication().getSharedPreferences("adb_shell_authorizations", 0).edit().clear().commit();
        byte[] bytes = Base64.getDecoder().decode(CERTIFICATE);
        certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
            .generateCertificate(new ByteArrayInputStream(bytes));
        authorizations = new AdbShellAuthorizations(RuntimeEnvironment.getApplication());
    }

    @Test
    public void authorizeListAndRevokeCertificate() throws Exception {
        assertFalse(authorizations.contains(certificate));
        assertTrue(authorizations.authorize(certificate, " workstation "));
        assertTrue(new AdbShellAuthorizations(RuntimeEnvironment.getApplication()).contains(certificate));
        assertEquals(1, authorizations.list().size());
        assertEquals("workstation", authorizations.list().get(0).name);
        String fingerprint = AdbShellAuthorizations.fingerprint(certificate);
        assertEquals(fingerprint, authorizations.list().get(0).fingerprint);
        assertTrue(authorizations.revoke(fingerprint));
        assertFalse(authorizations.contains(certificate));
        assertTrue(authorizations.list().isEmpty());
    }

    @Test
    public void disablingAccessClearsAuthorizations() throws Exception {
        assertTrue(authorizations.authorize(certificate, "client"));
        authorizations.clear();
        assertFalse(new AdbShellAuthorizations(RuntimeEnvironment.getApplication()).contains(certificate));
        assertTrue(authorizations.list().isEmpty());
    }

    @Test
    public void authorizationCountIsBounded() throws Exception {
        android.content.SharedPreferences.Editor editor = RuntimeEnvironment.getApplication()
            .getSharedPreferences("adb_shell_authorizations", 0).edit();
        for (int i = 0; i < 32; i++) {
            editor.putString(String.format(java.util.Locale.ROOT, "%064x", i), "fixture");
        }
        assertTrue(editor.commit());
        assertFalse(authorizations.authorize(certificate, "additional"));
        assertEquals(32, authorizations.list().size());
        assertTrue(authorizations.revoke(String.format(java.util.Locale.ROOT, "%064x", 0)));
        assertTrue(authorizations.authorize(certificate, "replacement"));
        assertEquals(32, authorizations.list().size());
    }

    @Test
    public void rejectInvalidNamesAndFingerprints() throws Exception {
        try {
            authorizations.authorize(certificate, "  ");
            fail("Blank client names must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(authorizations.list().isEmpty());
        }
        try {
            authorizations.authorize(certificate, "trusted‮client");
            fail("Names containing formatting controls must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(authorizations.list().isEmpty());
        }
        try {
            authorizations.revoke("not-a-fingerprint");
            fail("Invalid fingerprints must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(authorizations.list().isEmpty());
        }
    }
}
