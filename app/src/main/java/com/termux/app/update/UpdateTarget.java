package com.termux.app.update;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Apps shown in Updates, as a fixed allowlist. Only targets with an explicitly configured fork
 * release source can be installed. Repository, package and asset names are constants here; nothing
 * a release or network response can add a target or change what an existing one points at.
 *
 * <p>Core and the API/Tasker companions use a rolling {@code latest} prerelease and commit
 * comparison. Add-ons use the newest stable tagged fork release and a versioned APK asset. The
 * package identities and release conventions are constants here, not supplied by the network.
 */
public enum UpdateTarget {

    CORE("core", "pschmitt/termuxpp-app", "dev.pschmitt.termux", null, null),
    API("api", "pschmitt/termuxpp-api", "dev.pschmitt.termux.api", "termux-api-app_debug.apk", null),
    TASKER("tasker", "pschmitt/termuxpp-tasker", "dev.pschmitt.termux.tasker", "termux-tasker-app_debug.apk", null),
    FLOAT("float", null, "dev.pschmitt.termux.window", null, null),
    BOOT("boot", "pschmitt/termuxpp-boot", "dev.pschmitt.termux.boot", null, "termux-boot-app_"),
    STYLING("styling", "pschmitt/termuxpp-styling", "dev.pschmitt.termux.styling", null, "termux-styling-app_"),
    WIDGET("widget", "pschmitt/termuxpp-widget", "dev.pschmitt.termux.widget", null, "termux-widget-app_");

    /** The rolling prerelease every target follows. */
    public static final String LATEST_TAG = "latest";

    public static final String CHECKSUMS_ASSET = "SHA256SUMS";

    /**
     * Manifest meta-data of a companion naming the commit it was built from, as
     * {@code git-<hex commit>[-dirty]} (the prefix keeps the value a string for the resource compiler).
     */
    public static final String REVISION_META_DATA = "dev.pschmitt.termux.GIT_REVISION";

    public static final String REVISION_PREFIX = "git-";

    /** The shared user id of the core and both companions; an APK that lacks it is never installed. */
    public static final String SHARED_USER_ID = "dev.pschmitt.termux";

    /** Short stable name, used in preference keys. */
    public final String id;
    /** {@code owner/name} of the GitHub repository whose latest release may be installed. */
    public final String repository;
    /** Android application id the installed and the downloaded app must have. */
    public final String packageName;
    /** Fixed APK asset name for commit-based companions; null for core and versioned add-ons. */
    @Nullable private final String fixedApkName;
    @Nullable public final String versionedApkPrefix;

    UpdateTarget(@NonNull String id, @Nullable String repository, @NonNull String packageName,
                 @Nullable String fixedApkName, @Nullable String versionedApkPrefix) {
        this.id = id;
        this.repository = repository;
        this.packageName = packageName;
        this.fixedApkName = fixedApkName;
        this.versionedApkPrefix = versionedApkPrefix;
    }

    /** The target for an application id in this fixed list, or null for an unknown package. */
    @Nullable
    public static UpdateTarget forPackage(@Nullable String packageName) {
        for (UpdateTarget target : values()) {
            if (target.packageName.equals(packageName)) return target;
        }
        return null;
    }

    @NonNull
    public String apiBaseUrl() {
        if (repository == null) throw new IllegalStateException("No release source for " + id);
        return "https://api.github.com/repos/" + repository;
    }

    /** The API endpoint used by this target's manually maintained release channel. */
    @NonNull
    public String releaseApiUrl() {
        return apiBaseUrl() + (versionedApkPrefix == null
                ? "/releases/tags/" + LATEST_TAG : "/releases/latest");
    }

    public boolean hasReleaseSource() {
        return repository != null;
    }

    /** Path of the latest release's downloads on github.com, with trailing slash. */
    @NonNull
    String assetPathPrefix(@NonNull String tag) {
        if (repository == null) throw new IllegalStateException("No release source for " + id);
        return "/" + repository + "/releases/download/" + tag + "/";
    }

    @NonNull
    String fixedAssetPathPrefix() {
        return assetPathPrefix(LATEST_TAG);
    }

    /** Whether this is an app the updater can check and install. */
    public boolean isUpdatable() {
        return hasReleaseSource();
    }

    /** Whether the APK depends on the package variant and the device ABI (only the core does). */
    public boolean hasVariants() {
        return this == CORE;
    }

    /**
     * The APK asset names this target may install, best first. Names are built here, so a release
     * cannot steer the install to another file; anything else in it is ignored.
     */
    @NonNull
    List<String> apkNames(@Nullable String variant, @NonNull List<String> abis) {
        List<String> names = new ArrayList<>();
        if (!isUpdatable()) return names;
        if (fixedApkName != null) {
            names.add(fixedApkName);
            return names;
        }
        if (versionedApkPrefix != null) return names;
        if (variant == null) return names;
        for (String abi : abis) names.add(coreApkName(variant, abi));
        names.add(coreApkName(variant, "universal"));
        return names;
    }

    @NonNull
    static String coreApkName(@NonNull String variant, @NonNull String abi) {
        return String.format(Locale.ROOT, "termux-app_%s-debug_%s.apk", variant, abi);
    }

}
