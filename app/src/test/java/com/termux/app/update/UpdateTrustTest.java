package com.termux.app.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public class UpdateTrustTest {

    private static final String DIGEST_A = repeat('a', 64);
    private static final String DIGEST_B = repeat('b', 64);

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, c);
        return new String(chars);
    }

    @Test
    public void safeAssetNames() {
        assertTrue(UpdateTrust.isSafeAssetName("termux-app_apt-android-7-debug_universal.apk"));
        assertTrue(UpdateTrust.isSafeAssetName("SHA256SUMS"));
        assertFalse(UpdateTrust.isSafeAssetName(null));
        assertFalse(UpdateTrust.isSafeAssetName(""));
        assertFalse(UpdateTrust.isSafeAssetName(".hidden"));
        assertFalse(UpdateTrust.isSafeAssetName("a/b.apk"));
        assertFalse(UpdateTrust.isSafeAssetName("a\\b.apk"));
        assertFalse(UpdateTrust.isSafeAssetName("a..b.apk"));
        assertFalse(UpdateTrust.isSafeAssetName("a b.apk"));
        assertFalse(UpdateTrust.isSafeAssetName("a%2e.apk"));
        assertFalse(UpdateTrust.isSafeAssetName("a;rm.apk"));
        assertFalse(UpdateTrust.isSafeAssetName(repeat('a', 129)));
    }

    @Test
    public void assetUrlIsBuiltFromConstants() {
        String url = UpdateTrust.assetUrl(UpdateTarget.CORE, "SHA256SUMS");
        assertEquals("https://github.com/pschmitt/termuxpp-app/releases/download/latest/SHA256SUMS", url);
        assertTrue(UpdateTrust.isTrustedAssetUrl(UpdateTarget.CORE, url));
        assertEquals("https://github.com/pschmitt/termuxpp-api/releases/download/latest/termux-api-app_debug.apk",
                UpdateTrust.assetUrl(UpdateTarget.API, "termux-api-app_debug.apk"));
        assertEquals("https://github.com/pschmitt/termuxpp-tasker/releases/download/latest/SHA256SUMS",
                UpdateTrust.assetUrl(UpdateTarget.TASKER, "SHA256SUMS"));
    }

    @Test
    public void aTargetOnlyTrustsItsOwnRepository() {
        for (UpdateTarget target : UpdateTarget.values()) {
            if (!target.hasReleaseSource()) continue;
            String tag = target.versionedApkPrefix == null ? UpdateTarget.LATEST_TAG : "v1.2.3";
            String name = target.versionedApkPrefix == null ? "x.apk"
                    : target.versionedApkPrefix + "v1.2.3+github.debug.apk";
            String own = UpdateTrust.assetUrl(target, tag, name);
            assertTrue(UpdateTrust.isTrustedAssetUrl(target, tag, own));
            for (UpdateTarget other : UpdateTarget.values()) {
                if (other != target) assertFalse(UpdateTrust.isTrustedAssetUrl(other, tag, own));
            }
        }
        // Prefix confusion: termux-app is a prefix of no other repository path segment.
        assertFalse(UpdateTrust.isTrustedAssetUrl(UpdateTarget.CORE,
                "https://github.com/pschmitt/termuxpp-app-evil/releases/download/latest/x.apk"));
        assertFalse(UpdateTrust.isTrustedAssetUrl(UpdateTarget.API,
                "https://github.com/pschmitt/termuxpp-api-evil/releases/download/latest/x.apk"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void assetUrlRejectsUnsafeNames() {
        UpdateTrust.assetUrl(UpdateTarget.CORE, "../evil");
    }

    @Test
    public void trustedAssetUrls() {
        String base = "https://github.com/pschmitt/termuxpp-app/releases/download/latest/";
        assertTrue(UpdateTrust.isTrustedAssetUrl(UpdateTarget.CORE, base + "x.apk"));
        assertFalse(UpdateTrust.isTrustedAssetUrl(UpdateTarget.CORE, null));
        assertFalse(UpdateTrust.isTrustedAssetUrl(UpdateTarget.CORE, "http://github.com/pschmitt/termuxpp-app/releases/download/latest/x.apk"));
        assertFalse(UpdateTrust.isTrustedAssetUrl(UpdateTarget.CORE, "https://evil.example/pschmitt/termuxpp-app/releases/download/latest/x.apk"));
        assertFalse(UpdateTrust.isTrustedAssetUrl(UpdateTarget.CORE, "https://github.com.evil.example/pschmitt/termuxpp-app/releases/download/latest/x.apk"));
        assertFalse(UpdateTrust.isTrustedAssetUrl(UpdateTarget.CORE, "https://user@github.com/pschmitt/termuxpp-app/releases/download/latest/x.apk"));
        assertFalse(UpdateTrust.isTrustedAssetUrl(UpdateTarget.CORE, "https://github.com:8443/pschmitt/termuxpp-app/releases/download/latest/x.apk"));
        assertFalse(UpdateTrust.isTrustedAssetUrl(UpdateTarget.CORE, base + "x.apk?token=1"));
        assertFalse(UpdateTrust.isTrustedAssetUrl(UpdateTarget.CORE, base + "x.apk#frag"));
        assertFalse(UpdateTrust.isTrustedAssetUrl(UpdateTarget.CORE, base + "../../../../other/repo/x.apk"));
        assertFalse(UpdateTrust.isTrustedAssetUrl(UpdateTarget.CORE, "https://github.com/other/repo/releases/download/latest/x.apk"));
        assertFalse(UpdateTrust.isTrustedAssetUrl(UpdateTarget.CORE, "https://github.com/pschmitt/termuxpp-app/releases/download/v1/x.apk"));
        assertFalse(UpdateTrust.isTrustedAssetUrl(UpdateTarget.CORE, base + "sub/x.apk"));
        assertFalse(UpdateTrust.isTrustedAssetUrl(UpdateTarget.CORE, "not a url"));
    }

    @Test
    public void hostChecks() {
        assertTrue(UpdateTrust.isTrustedDownloadHost("github.com"));
        assertTrue(UpdateTrust.isTrustedDownloadHost("GITHUB.com"));
        assertTrue(UpdateTrust.isTrustedDownloadHost("release-assets.githubusercontent.com"));
        assertFalse(UpdateTrust.isTrustedDownloadHost("githubusercontent.com"));
        assertFalse(UpdateTrust.isTrustedDownloadHost("evilgithubusercontent.com"));
        assertFalse(UpdateTrust.isTrustedDownloadHost("github.com.evil.example"));
        assertFalse(UpdateTrust.isTrustedDownloadHost(null));
        assertTrue(UpdateTrust.isTrustedApiHost("api.github.com"));
        assertFalse(UpdateTrust.isTrustedApiHost("github.com"));
        assertFalse(UpdateTrust.isTrustedApiHost(null));
    }

    @Test
    public void parsesSha256Sums() {
        String text = DIGEST_A + "  one.apk\n"
                + DIGEST_B.toUpperCase() + " *two.apk\r\n"
                + "garbage line\n"
                + "abc  short.apk\n"
                + "\n";
        Map<String, String> sums = UpdateTrust.parseSha256Sums(text);
        assertEquals(2, sums.size());
        assertEquals(DIGEST_A, sums.get("one.apk"));
        assertEquals(DIGEST_B, sums.get("two.apk"));
        assertNull(sums.get("short.apk"));
    }

    @Test
    public void ambiguousNamesAreDropped() {
        String text = DIGEST_A + "  one.apk\n" + DIGEST_B + "  one.apk\n" + DIGEST_A + "  two.apk\n";
        Map<String, String> sums = UpdateTrust.parseSha256Sums(text);
        assertNull(sums.get("one.apk"));
        assertEquals(DIGEST_A, sums.get("two.apk"));
    }

    @Test
    public void repeatedIdenticalLinesAreFine() {
        Map<String, String> sums = UpdateTrust.parseSha256Sums(DIGEST_A + "  one.apk\n" + DIGEST_A + "  one.apk\n");
        assertEquals(DIGEST_A, sums.get("one.apk"));
    }

    @Test
    public void nullSumsAreEmpty() {
        assertTrue(UpdateTrust.parseSha256Sums(null).isEmpty());
    }

    @Test
    public void sha256OfBytes() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                UpdateTrust.sha256Hex("abc".getBytes(StandardCharsets.US_ASCII)));
    }
}
