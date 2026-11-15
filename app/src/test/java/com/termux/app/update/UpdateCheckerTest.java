package com.termux.app.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
            if (url.endsWith("/releases/tags/latest") || url.endsWith("/releases/latest")) {
                if (releaseError != null) throw releaseError;
                if (releaseJson != null) return releaseJson;
                throw new HttpStatusException(404);
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

    private static String taggedRelease(String tag, String... assetNamesAndSizes) throws Exception {
        JSONArray assets = new JSONArray();
        for (int i = 0; i < assetNamesAndSizes.length; i += 2) {
            assets.put(new JSONObject().put("name", assetNamesAndSizes[i])
                    .put("size", Long.parseLong(assetNamesAndSizes[i + 1])));
        }
        return new JSONObject().put("tag_name", tag).put("assets", assets).toString();
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
        return new UpdateChecker(network, API).check(
                UpdateTarget.CORE, InstalledBuild.parse(revision), VARIANT, Arrays.asList(abis), UpdateChannel.LATEST);
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
                .check(UpdateTarget.CORE, InstalledBuild.parse(INSTALLED), "../x",
                        Collections.singletonList("arm64-v8a"), UpdateChannel.LATEST);
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

    private static UpdateChecker.Result checkCompanion(FakeNetwork network, UpdateTarget target, InstalledBuild installed) {
        // No variant and no ABIs: companions have one APK.
        return new UpdateChecker(network, API).check(target, installed, null,
                Collections.<String>emptyList(), UpdateChannel.LATEST);
    }

    private static FakeNetwork companionNetwork(String status, String... assets) throws Exception {
        FakeNetwork network = new FakeNetwork();
        network.releaseJson = release(body(REMOTE), assets);
        network.compareJson = new JSONObject().put("status", status).toString();
        return network;
    }

    @Test
    public void absentAddonCanBeInstalledFromItsForkRelease() throws Exception {
        FakeNetwork network = new FakeNetwork();
        network.releaseJson = taggedRelease("v0.8.1",
                "termux-boot-app_v0.8.1+github.debug.apk", "700", "checksums-sha256.txt", "100");
        UpdateChecker.Result result = new UpdateChecker(network, API).check(UpdateTarget.BOOT,
                InstalledBuild.parse(null), null, Collections.<String>emptyList(), UpdateChannel.LATEST, true);
        assertEquals(UpdateChecker.Kind.AVAILABLE, result.kind);
        assertEquals("v0.8.1", result.offer.shortCommit());
        assertEquals("termux-boot-app_v0.8.1+github.debug.apk", result.offer.apk.name);
        assertEquals("checksums-sha256.txt", result.offer.checksums.name);
        assertEquals(Collections.singletonList(API + "/releases/latest"), network.requests);
    }

    @Test
    public void installedAddonReleaseProvidesCandidateForVerifiedComparison() throws Exception {
        FakeNetwork network = new FakeNetwork();
        network.releaseJson = taggedRelease("v0.8.1",
                "termux-boot-app_v0.8.1+github.debug.apk", "700", "checksums-sha256.txt", "100");
        UpdateChecker.Result result = new UpdateChecker(network, API).check(UpdateTarget.BOOT,
                InstalledBuild.parse(null), null, Collections.<String>emptyList(), UpdateChannel.LATEST, false);
        assertEquals(UpdateChecker.Kind.AVAILABLE, result.kind);
        assertNotNull(result.offer);
        assertEquals("v0.8.1", result.offer.releaseTag);
        assertEquals(Collections.singletonList(API + "/releases/latest"), network.requests);
    }

    @Test
    public void missingAddonReleaseIsGracefullyUnavailable() {
        FakeNetwork network = new FakeNetwork();
        UpdateChecker.Result result = new UpdateChecker(network, API).check(UpdateTarget.WIDGET,
                InstalledBuild.parse(null), null, Collections.<String>emptyList(), UpdateChannel.LATEST, true);
        assertEquals(UpdateChecker.Kind.UNAVAILABLE, result.kind);
        assertNull(result.offer);
        assertEquals(Collections.singletonList(API + "/releases/latest"), network.requests);
    }

    @Test
    public void companionsOfferTheirOwnFixedApk() throws Exception {
        FakeNetwork api = companionNetwork("ahead", "termux-api-app_debug.apk", "700", "SHA256SUMS", "100");
        UpdateChecker.Result result = checkCompanion(api, UpdateTarget.API, InstalledBuild.parseMetaData("git-" + INSTALLED));
        assertEquals(UpdateChecker.Kind.AVAILABLE, result.kind);
        assertEquals("termux-api-app_debug.apk", result.offer.apk.name);
        assertEquals(REMOTE, result.offer.commit);

        FakeNetwork tasker = companionNetwork("ahead", "termux-tasker-app_debug.apk", "800", "SHA256SUMS", "100");
        result = checkCompanion(tasker, UpdateTarget.TASKER, InstalledBuild.parseMetaData("git-" + INSTALLED));
        assertEquals(UpdateChecker.Kind.AVAILABLE, result.kind);
        assertEquals("termux-tasker-app_debug.apk", result.offer.apk.name);
    }

    @Test
    public void absentCompanionCanBeInstalledWithoutComparingCommits() throws Exception {
        FakeNetwork network = companionNetwork("ahead", "termux-api-app_debug.apk", "700", "SHA256SUMS", "100");
        UpdateChecker.Result result = new UpdateChecker(network, API).check(UpdateTarget.API,
                InstalledBuild.parse(null), null, Collections.<String>emptyList(), UpdateChannel.LATEST, true);
        assertEquals(UpdateChecker.Kind.AVAILABLE, result.kind);
        assertEquals(Collections.singletonList(API + "/releases/tags/latest"), network.requests);
    }

    @Test
    public void companionsNeverTakeAnotherAppsApk() throws Exception {
        InstalledBuild installed = InstalledBuild.parseMetaData("git-" + INSTALLED);
        // A tasker release that also ships the API app's, the core's and an unknown APK.
        String[] assets = {"termux-api-app_debug.apk", "1", "termux-app_apt-android-7-debug_universal.apk", "2",
                "evil.apk", "3", "SHA256SUMS", "4"};
        UpdateChecker.Result tasker = checkCompanion(companionNetwork("ahead", assets), UpdateTarget.TASKER, installed);
        assertEquals(UpdateChecker.Kind.FAILED, tasker.kind);
        assertNull(tasker.offer);

        String[] taskerAssets = {"termux-tasker-app_debug.apk", "1", "SHA256SUMS", "4"};
        UpdateChecker.Result api = checkCompanion(companionNetwork("ahead", taskerAssets), UpdateTarget.API, installed);
        assertEquals(UpdateChecker.Kind.FAILED, api.kind);
        assertNull(api.offer);

        // And the core never takes a companion's APK either.
        FakeNetwork core = companionNetwork("ahead", "termux-api-app_debug.apk", "1", "SHA256SUMS", "4");
        UpdateChecker.Result coreResult = check(core, INSTALLED, "arm64-v8a");
        assertEquals(UpdateChecker.Kind.FAILED, coreResult.kind);
    }

    @Test
    public void companionsAreCheckedAgainstTheirOwnRepository() {
        FakeNetwork network = new FakeNetwork();
        for (UpdateTarget target : UpdateTarget.values()) {
            network.requests.clear();
            UpdateChecker.Result result = new UpdateChecker(network).check(target, InstalledBuild.parse(INSTALLED), VARIANT,
                    Collections.singletonList("arm64-v8a"), UpdateChannel.LATEST);
            if (target.isUpdatable()) {
                if (target.versionedApkPrefix != null) {
                    assertEquals(UpdateChecker.Kind.UNAVAILABLE, result.kind);
                    assertEquals(Collections.singletonList(target.releaseApiUrl()), network.requests);
                } else {
                    assertEquals(Collections.singletonList(target.releaseApiUrl()), network.requests);
                }
            } else {
                assertEquals(UpdateChecker.Kind.NOT_ELIGIBLE, result.kind);
                assertTrue(network.requests.isEmpty());
            }
        }
        assertEquals("pschmitt/termuxpp-app", UpdateTarget.CORE.repository);
        assertEquals("pschmitt/termuxpp-api", UpdateTarget.API.repository);
        assertEquals("pschmitt/termuxpp-tasker", UpdateTarget.TASKER.repository);
    }

    @Test
    public void unknownCompanionBuildsCanReinstallFromFixedLatestRelease() throws Exception {
        String[] metas = {null, "", "unknown", "git-unknown", "git-" + INSTALLED + "-dirty", INSTALLED};
        for (String meta : metas) {
            FakeNetwork network = companionNetwork("ahead", "termux-api-app_debug.apk", "700", "SHA256SUMS", "100");
            UpdateChecker.Result result = checkCompanion(network, UpdateTarget.API, InstalledBuild.parseMetaData(meta));
            assertEquals(meta, UpdateChecker.Kind.AVAILABLE, result.kind);
            assertEquals("termux-api-app_debug.apk", result.offer.apk.name);
            assertEquals(Collections.singletonList(API + "/releases/tags/latest"), network.requests);
        }

        FakeNetwork core = network("ahead");
        UpdateChecker.Result coreResult = check(core, null, "arm64-v8a");
        assertEquals(UpdateChecker.Kind.NOT_ELIGIBLE, coreResult.kind);
        assertTrue(core.requests.isEmpty());
    }

    @Test
    public void companionCommitsCompareLikeTheCores() throws Exception {
        InstalledBuild installed = InstalledBuild.parseMetaData("git-" + INSTALLED);
        String[] assets = {"termux-api-app_debug.apk", "700", "SHA256SUMS", "100"};
        assertEquals(UpdateChecker.Kind.UP_TO_DATE,
                checkCompanion(companionNetwork("behind", assets), UpdateTarget.API, installed).kind);
        assertEquals(UpdateChecker.Kind.NOT_ELIGIBLE,
                checkCompanion(companionNetwork("diverged", assets), UpdateTarget.API, installed).kind);
        FakeNetwork unknown = companionNetwork("ahead", assets);
        unknown.compareStatus = 404;
        assertEquals(UpdateChecker.Kind.NOT_ELIGIBLE, checkCompanion(unknown, UpdateTarget.API, installed).kind);
        assertEquals(UpdateChecker.Kind.UP_TO_DATE, checkCompanion(companionNetwork("ahead", assets), UpdateTarget.API,
                InstalledBuild.parseMetaData("git-" + REMOTE)).kind);
    }

    @Test
    public void targetAllowlistIsFixed() {
        assertEquals(7, UpdateTarget.values().length);
        assertTrue(UpdateTarget.FLOAT.isUpdatable());
        assertTrue(UpdateTarget.BOOT.isUpdatable());
        assertTrue(UpdateTarget.STYLING.isUpdatable());
        assertTrue(UpdateTarget.WIDGET.isUpdatable());
        assertEquals("pschmitt/termuxpp-float", UpdateTarget.FLOAT.repository);
        assertEquals("termux-float-app_", UpdateTarget.FLOAT.versionedApkPrefix);
        assertEquals("pschmitt/termuxpp-boot", UpdateTarget.BOOT.repository);
        assertEquals("pschmitt/termuxpp-styling", UpdateTarget.STYLING.repository);
        assertEquals("pschmitt/termuxpp-widget", UpdateTarget.WIDGET.repository);
        assertEquals(UpdateTarget.API, UpdateTarget.forPackage("dev.pschmitt.termux.api"));
        assertEquals(UpdateTarget.TASKER, UpdateTarget.forPackage("dev.pschmitt.termux.tasker"));
        assertEquals(UpdateTarget.CORE, UpdateTarget.forPackage("dev.pschmitt.termux"));
        assertNull(UpdateTarget.forPackage("com.termux"));
        assertNull(UpdateTarget.forPackage("dev.pschmitt.termux.evil"));
        assertNull(UpdateTarget.forPackage(null));
    }
}
