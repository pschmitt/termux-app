package com.termux.app.update;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The apps the updater may install, as a fixed allowlist: TermuxPP itself and the two forked
 * companions. Repository, package and asset names are constants here; nothing a release, an
 * installed app or the network says can add a target or change what an existing one points at.
 *
 * <p>Every target is its own GitHub repository with its own rolling {@code latest} prerelease, and
 * its own commit identity. The commit of the installed core is {@code BuildConfig.GIT_REVISION};
 * a companion carries its own in the {@link #REVISION_META_DATA} manifest entry. The two are never
 * mixed up: the {@code termux-app} submodule a companion pins is not part of its identity.
 */
public enum UpdateTarget {

    CORE("core", "pschmitt/termux-app", "dev.pschmitt.termux", null),
    API("api", "pschmitt/termux-api", "dev.pschmitt.termux.api", "termux-api-app_debug.apk"),
    TASKER("tasker", "pschmitt/termux-tasker", "dev.pschmitt.termux.tasker", "termux-tasker-app_debug.apk");

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
    /** The single APK asset name, or null for the core, whose names depend on variant and ABI. */
    @Nullable private final String fixedApkName;

    UpdateTarget(@NonNull String id, @NonNull String repository, @NonNull String packageName,
                 @Nullable String fixedApkName) {
        this.id = id;
        this.repository = repository;
        this.packageName = packageName;
        this.fixedApkName = fixedApkName;
    }

    /** The target for an application id, or null: only the three known packages have one. */
    @Nullable
    public static UpdateTarget forPackage(@Nullable String packageName) {
        for (UpdateTarget target : values()) {
            if (target.packageName.equals(packageName)) return target;
        }
        return null;
    }

    @NonNull
    public String apiBaseUrl() {
        return "https://api.github.com/repos/" + repository;
    }

    /** Path of the latest release's downloads on github.com, with trailing slash. */
    @NonNull
    String assetPathPrefix() {
        return "/" + repository + "/releases/download/" + LATEST_TAG + "/";
    }

    /** Whether the APK depends on the package variant and the device ABI (only the core does). */
    public boolean hasVariants() {
        return fixedApkName == null;
    }

    /**
     * The APK asset names this target may install, best first. Names are built here, so a release
     * cannot steer the install to another file; anything else in it is ignored.
     */
    @NonNull
    List<String> apkNames(@Nullable String variant, @NonNull List<String> abis) {
        List<String> names = new ArrayList<>();
        if (fixedApkName != null) {
            names.add(fixedApkName);
            return names;
        }
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
