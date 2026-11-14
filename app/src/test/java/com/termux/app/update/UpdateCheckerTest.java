package com.termux.app.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class UpdateCheckerTest {

    private static final String API = "https://api.example.test/repos/x/y";
    private static final String INSTALLED = "1111111111111111111111111111111111111111";
    private static final String REMOTE = "2222222222222222222222222222222222222222";
    private static final String VARIANT = "apt-android-7";

    /** Canned API answers; anything not registered is a 404. */
    private static final class FakeNetwork implements UpdateNetwork {
        final List<String> requests = new ArrayList<>();
        String releaseJson;
        String compareJson;
        int compareStatus = 200;
        IOException releaseError;

        @Override
        public String fetchApiJson(String url) throws IOException {
            requests.add(url);
            if (url.equals(API + "/releases/tags/latest")) {
                if (releaseError != null) throw releaseError;
                return releaseJson;
            }
            if (url.startsWith(API + "/compare/")) {
                if (compareStatus != 200) throw new HttpStatusException(compareStatus);
                return compareJson;
            }
            throw new HttpStatusException(404);
        }

        @Override
        public String fetchAssetText(String url, long maxBytes) {
            throw new AssertionError("the checker must not fetch assets");
        }

        @Override
        public void download(String url, File dest, long maxBytes, ProgressListener listener) {
            throw new AssertionError("the checker must not download");
        }

        @Override
        public long freeBytes(File dir) {
            return Long.MAX_VALUE;
        }
    }

    private static String release(String body, String... assetNamesAndSizes) throws Exception {
        JSONArray assets = new JSONArray();
        for (int i = 0; i < assetNamesAndSizes.length; i += 2) {
            assets.put(new JSONObject().put("name", assetNamesAndSizes[i]).put("size", Long.parseLong(assetNamesAndSizes[i + 1])));
        }
        return new JSONObject().put("body", body).put("assets", assets).toString();
    }

    private static String body(String commit) {
        return "Automated build of the latest main (" + commit + "), built today.";
    }

    private static FakeNetwork network(String status) throws Exception {
        FakeNetwork network = new FakeNetwork();
        network.releaseJson = release(body(REMOTE),
                "termux-app_apt-android-7-debug_universal.apk", "1000",
                "termux-app_apt-android-7-debug_arm64-v8a.apk", "600",
                "termux-app_apt-android-5-debug_arm64-v8a.apk", "601",
                "SHA256SUMS", "300");
        network.compareJson = new JSONObject().put("status", status).toString();
        return network;
    }

    private static UpdateChecker.Result check(FakeNetwork network, String revision, String... abis) {
        return new UpdateChecker(network, API).check(revision, VARIANT, Arrays.asList(abis), UpdateChannel.LATEST);
    }

    @Test
    public void aheadOffersTheBestApkForTheDevice() throws Exception {
        UpdateChecker.Result result = check(network("ahead"), INSTALLED, "arm64-v8a", "armeabi-v7a");
        assertEquals(UpdateChecker.Kind.AVAILABLE, result.kind);
        UpdateChecker.Offer offer = result.offer;
        assertNotNull(offer);
        assertEquals(REMOTE, offer.commit);
        assertEquals("2222222", offer.shortCommit());
        assertEquals("termux-app_apt-android-7-debug_arm64-v8a.apk", offer.apk.name);
        assertEquals(600, offer.apk.size);
        assertEquals("SHA256SUMS", offer.checksums.name);
    }

    @Test
    public void fallsBackToTheUniversalApk() throws Exception {
        UpdateChecker.Result result = check(network("ahead"), INSTALLED, "x86_64");
        assertEquals(UpdateChecker.Kind.AVAILABLE, result.kind);
        assertEquals("termux-app_apt-android-7-debug_universal.apk", result.offer.apk.name);
    }

    @Test
    public void neverPicksAnotherVariant() throws Exception {
        FakeNetwork network = network("ahead");
        network.releaseJson = release(body(REMOTE),
                "termux-app_apt-android-5-debug_universal.apk", "1000", "SHA256SUMS", "300");
        UpdateChecker.Result result = check(network, INSTALLED, "arm64-v8a");
        assertEquals(UpdateChecker.Kind.FAILED, result.kind);
        assertNull(result.offer);
    }

    @Test
    public void sameCommitIsUpToDateWithoutACompare() throws Exception {
        FakeNetwork network = network("ahead");
        UpdateChecker.Result result = check(network, REMOTE, "arm64-v8a");
        assertEquals(UpdateChecker.Kind.UP_TO_DATE, result.kind);
        assertEquals(1, network.requests.size());
        // A short installed hash that prefixes the release commit is the same commit.
        assertEquals(UpdateChecker.Kind.UP_TO_DATE, check(network("ahead"), "2222222", "arm64-v8a").kind);
    }

    @Test
    public void identicalAndBehindAreUpToDate() throws Exception {
        assertEquals(UpdateChecker.Kind.UP_TO_DATE, check(network("identical"), INSTALLED, "arm64-v8a").kind);
        assertEquals(UpdateChecker.Kind.UP_TO_DATE, check(network("behind"), INSTALLED, "arm64-v8a").kind);
    }

    @Test
    public void divergedAndUnknownStatusesAreNotEligible() throws Exception {
        assertEquals(UpdateChecker.Kind.NOT_ELIGIBLE, check(network("diverged"), INSTALLED, "arm64-v8a").kind);
        assertEquals(UpdateChecker.Kind.NOT_ELIGIBLE, check(network("sideways"), INSTALLED, "arm64-v8a").kind);
    }

    @Test
    public void unknownInstalledCommitIsNotEligible() throws Exception {
        FakeNetwork network = network("ahead");
        network.compareStatus = 404;
        assertEquals(UpdateChecker.Kind.NOT_ELIGIBLE, check(network, INSTALLED, "arm64-v8a").kind);
    }

    @Test
    public void nonComparableBuildsNeverTouchTheNetwork() throws Exception {
        FakeNetwork network = network("ahead");
        for (String revision : new String[] {null, "", "unknown", INSTALLED + "-dirty"}) {
            assertEquals(UpdateChecker.Kind.NOT_ELIGIBLE, check(network, revision, "arm64-v8a").kind);
        }
        assertTrue(network.requests.isEmpty());
    }

    @Test
    public void otherHttpErrorsFail() throws Exception {
        FakeNetwork network = network("ahead");
        network.compareStatus = 500;
        UpdateChecker.Result result = check(network, INSTALLED, "arm64-v8a");
        assertEquals(UpdateChecker.Kind.FAILED, result.kind);
        assertEquals("GitHub returned 500", result.detail);

        FakeNetwork noRelease = network("ahead");
        noRelease.releaseError = new UpdateNetwork.HttpStatusException(404);
        assertEquals(UpdateChecker.Kind.FAILED, check(noRelease, INSTALLED, "arm64-v8a").kind);

        FakeNetwork offline = network("ahead");
        offline.releaseError = new IOException("offline");
        assertEquals("offline", check(offline, INSTALLED, "arm64-v8a").detail);
    }

    @Test
    public void releaseWithoutACommitFails() throws Exception {
        FakeNetwork network = network("ahead");
        network.releaseJson = release("built today", "SHA256SUMS", "300");
        assertEquals(UpdateChecker.Kind.FAILED, check(network, INSTALLED, "arm64-v8a").kind);
        // A short hash is not an identity.
        network.releaseJson = release("commit 2222222", "SHA256SUMS", "300");
        assertEquals(UpdateChecker.Kind.FAILED, check(network, INSTALLED, "arm64-v8a").kind);
    }

    @Test
    public void garbageMetadataFailsInsteadOfThrowing() throws Exception {
        FakeNetwork network = network("ahead");
        network.releaseJson = "<html>";
        assertEquals(UpdateChecker.Kind.FAILED, check(network, INSTALLED, "arm64-v8a").kind);
        network.releaseJson = release(body(REMOTE));
        assertEquals(UpdateChecker.Kind.FAILED, check(network, INSTALLED, "arm64-v8a").kind);
    }

    @Test
    public void missingChecksumsFail() throws Exception {
        FakeNetwork network = network("ahead");
        network.releaseJson = release(body(REMOTE), "termux-app_apt-android-7-debug_universal.apk", "1000");
        UpdateChecker.Result result = check(network, INSTALLED, "arm64-v8a");
        assertEquals(UpdateChecker.Kind.FAILED, result.kind);
        assertNull(result.offer);
    }

    @Test
    public void oversizedApkIsNotOffered() throws Exception {
        FakeNetwork network = network("ahead");
        network.releaseJson = release(body(REMOTE),
                "termux-app_apt-android-7-debug_universal.apk", String.valueOf(UpdateChecker.MAX_APK_BYTES + 1),
                "SHA256SUMS", "300");
        assertEquals(UpdateChecker.Kind.FAILED, check(network, INSTALLED, "arm64-v8a").kind);
    }

    @Test
    public void invalidVariantFailsWithoutARequest() throws Exception {
        FakeNetwork network = network("ahead");
        UpdateChecker.Result result = new UpdateChecker(network, API)
                .check(INSTALLED, "../x", Collections.singletonList("arm64-v8a"), UpdateChannel.LATEST);
        assertEquals(UpdateChecker.Kind.FAILED, result.kind);
        assertTrue(network.requests.isEmpty());
    }

    @Test
    public void channelParsingDefaultsToLatest() {
        assertEquals(UpdateChannel.LATEST, UpdateChannel.fromValue(null));
        assertEquals(UpdateChannel.LATEST, UpdateChannel.fromValue("nightly"));
        assertEquals(UpdateChannel.LATEST, UpdateChannel.fromValue("latest"));
    }

    @Test
    public void compareStatusParsing() {
        assertEquals(CompareStatus.AHEAD, CompareStatus.parse("ahead"));
        assertEquals(CompareStatus.BEHIND, CompareStatus.parse("BEHIND"));
        assertEquals(CompareStatus.DIVERGED, CompareStatus.parse(null));
        assertEquals(CompareStatus.DIVERGED, CompareStatus.parse("whatever"));
    }
}
