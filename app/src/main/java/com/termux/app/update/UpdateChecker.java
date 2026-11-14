package com.termux.app.update;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the rolling {@code latest} release of one {@link UpdateTarget} against its installed build.
 *
 * "Newer" cannot be read off the constant tag, version name or version code, so the decision is
 * made on commits: the release body names the full commit the build was made from (a release
 * without one fails the check), and GitHub's compare API says how that commit relates to the
 * installed one.
 * <ul>
 * <li>Same commit: up to date, no compare request.</li>
 * <li>{@code latest} ahead of the install: an update is available.</li>
 * <li>Identical or behind: up to date (the install is at or past {@code latest}; nothing is ever
 * offered that would go backwards).</li>
 * <li>Diverged, or HTTP 404 (GitHub does not know the installed commit, a local build that was
 * never pushed), or an install without a clean commit identity: not eligible.</li>
 * <li>Any other error: the check failed.</li>
 * </ul>
 * Version names and codes play no part in the decision; they only feed the install guard.
 */
public final class UpdateChecker {

    /** One downloadable file of the release. */
    public static final class Asset {
        public final String name;
        public final long size;

        public Asset(@NonNull String name, long size) {
            this.name = name;
            this.size = size;
        }
    }

    /** A newer build and the files that install it. */
    public static final class Offer {
        /** Full 40-digit commit of the published build. */
        public final String commit;
        public final Asset apk;
        public final Asset checksums;

        public Offer(@NonNull String commit, @NonNull Asset apk, @NonNull Asset checksums) {
            this.commit = commit;
            this.apk = apk;
            this.checksums = checksums;
        }

        @NonNull
        public String shortCommit() {
            return commit.substring(0, SHORT_HASH_LENGTH);
        }
    }

    public enum Kind { UP_TO_DATE, AVAILABLE, NOT_ELIGIBLE, FAILED }

    public static final class Result {
        public final Kind kind;
        @Nullable public final Offer offer;
        /** For {@link Kind#FAILED}: a short technical reason; never shown as markup. */
        @Nullable public final String detail;

        Result(@NonNull Kind kind, @Nullable Offer offer, @Nullable String detail) {
            this.kind = kind;
            this.offer = offer;
            this.detail = detail;
        }
    }

    /** Largest APK the updater will download. */
    public static final long MAX_APK_BYTES = 400L * 1024 * 1024;

    static final int SHORT_HASH_LENGTH = 7;

    private static final Pattern FULL_COMMIT_HASH = Pattern.compile("\\b[0-9a-f]{40}\\b");
    private static final Pattern VARIANT = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    private static final int HTTP_NOT_FOUND = 404;

    private final UpdateNetwork network;
    @Nullable private final String apiBaseUrlOverride;

    public UpdateChecker(@NonNull UpdateNetwork network) {
        this(network, null);
    }

    /** {@code apiBaseUrlOverride} replaces the target's API URL; for tests only. */
    UpdateChecker(@NonNull UpdateNetwork network, @Nullable String apiBaseUrlOverride) {
        this.network = network;
        this.apiBaseUrlOverride = apiBaseUrlOverride;
    }

    /**
     * @param target the app to check; its repository, not anything in the release, decides where
     *               the metadata comes from
     * @param installed the commit identity of the installed {@code target}
     * @param variant the package variant of the running core, e.g. {@code apt-android-7}: the core
     *                APK installed must be the same variant (ignored for companions, which have one APK)
     * @param supportedAbis the device's ABIs, most preferred first
     */
    @NonNull
    public Result check(@NonNull UpdateTarget target, @NonNull InstalledBuild installed, @Nullable String variant,
                        @NonNull List<String> supportedAbis, @NonNull UpdateChannel channel) {
        if (!installed.isComparable() || installed.commit == null) return new Result(Kind.NOT_ELIGIBLE, null, null);
        if (target.hasVariants() && (variant == null || !VARIANT.matcher(variant).matches())) {
            return new Result(Kind.FAILED, null, "unknown package variant");
        }
        try {
            // Only one channel exists; the switch keeps the extension point explicit.
            switch (channel) {
                case LATEST:
                default:
                    return checkLatest(target, installed.commit, variant, supportedAbis);
            }
        } catch (UpdateNetwork.HttpStatusException e) {
            return new Result(Kind.FAILED, null, "GitHub returned " + e.code);
        } catch (IOException | JSONException | RuntimeException e) {
            return new Result(Kind.FAILED, null, String.valueOf(e.getMessage()));
        }
    }

    private Result checkLatest(UpdateTarget target, String installedCommit, @Nullable String variant, List<String> abis)
            throws IOException, JSONException {
        String apiBaseUrl = apiBaseUrlOverride != null ? apiBaseUrlOverride : target.apiBaseUrl();
        JSONObject release = new JSONObject(network.fetchApiJson(apiBaseUrl + "/releases/tags/" + UpdateTarget.LATEST_TAG));
        Matcher matcher = FULL_COMMIT_HASH.matcher(release.optString("body", ""));
        if (!matcher.find()) throw new IOException("latest release names no commit");
        String remoteCommit = matcher.group();

        if (remoteCommit.startsWith(installedCommit)) return new Result(Kind.UP_TO_DATE, null, null);

        CompareStatus status = compare(apiBaseUrl, installedCommit, remoteCommit);
        if (status == null || status == CompareStatus.DIVERGED) return new Result(Kind.NOT_ELIGIBLE, null, null);
        if (status != CompareStatus.AHEAD) return new Result(Kind.UP_TO_DATE, null, null);

        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) throw new IOException("latest release has no assets");
        Asset apk = selectApkAsset(assets, target, variant, abis);
        if (apk == null) throw new IOException("latest release has no APK for this device");
        Asset checksums = findAsset(assets, UpdateTarget.CHECKSUMS_ASSET);
        if (checksums == null) throw new IOException("latest release has no " + UpdateTarget.CHECKSUMS_ASSET);
        return new Result(Kind.AVAILABLE, new Offer(remoteCommit, apk, checksums), null);
    }

    /** Null when GitHub answers 404: it does not know {@code base}. */
    @Nullable
    private CompareStatus compare(String apiBaseUrl, String base, String head) throws IOException, JSONException {
        try {
            String json = network.fetchApiJson(apiBaseUrl + "/compare/" + base + "..." + head + "?per_page=1");
            return CompareStatus.parse(new JSONObject(json).optString("status"));
        } catch (UpdateNetwork.HttpStatusException e) {
            if (e.code == HTTP_NOT_FOUND) return null;
            throw e;
        }
    }

    /**
     * The APK of {@code target} for the first of its candidate names that the release has. The
     * names are built by {@link UpdateTarget#apkNames}, so a release cannot steer the install to
     * another variant, file or app; anything else in it is ignored.
     */
    @Nullable
    static Asset selectApkAsset(@NonNull JSONArray assets, @NonNull UpdateTarget target, @Nullable String variant,
                                @NonNull List<String> abis) throws JSONException {
        for (String name : target.apkNames(variant, abis)) {
            Asset asset = findAsset(assets, name);
            if (asset != null) return asset;
        }
        return null;
    }

    @Nullable
    private static Asset findAsset(JSONArray assets, String name) throws JSONException {
        if (!UpdateTrust.isSafeAssetName(name)) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null || !name.equals(asset.optString("name"))) continue;
            long size = asset.optLong("size", 0);
            if (size < 0 || size > MAX_APK_BYTES) return null;
            return new Asset(name, size);
        }
        return null;
    }

}
