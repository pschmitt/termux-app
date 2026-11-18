package com.termux.app.update;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What {@code BuildConfig.GIT_REVISION} says about the running build: a commit of the repository,
 * or nothing comparable (local build with uncommitted changes, no git, anything unrecognised).
 */
public final class InstalledBuild {

    private static final Pattern HASH = Pattern.compile("[0-9a-f]{7,40}");
    private static final Pattern DESCRIBE = Pattern.compile("v?\\d+\\.\\d+\\.\\d+-\\d+-g([0-9a-f]{7,40})");

    /** Lowercase hex commit (7 to 40 digits), or null when the build is not comparable. */
    @Nullable
    public final String commit;

    private InstalledBuild(@Nullable String commit) {
        this.commit = commit;
    }

    public boolean isComparable() {
        return commit != null;
    }

    /**
     * What {@link UpdateTarget#REVISION_META_DATA} says: {@code git-<hex commit>}.
     * A value without the prefix, with a {@code -dirty} suffix or in any other shape is not comparable.
     */
    @NonNull
    public static InstalledBuild parseMetaData(@Nullable String metaData) {
        if (metaData == null) return new InstalledBuild(null);
        String value = metaData.trim().toLowerCase(Locale.ROOT);
        if (!value.startsWith(UpdateTarget.REVISION_PREFIX)) return new InstalledBuild(null);
        String hash = value.substring(UpdateTarget.REVISION_PREFIX.length());
        return HASH.matcher(hash).matches() ? new InstalledBuild(hash) : new InstalledBuild(null);
    }

    @NonNull
    public static InstalledBuild parse(@Nullable String gitRevision) {
        if (gitRevision == null) return new InstalledBuild(null);
        String revision = gitRevision.trim().toLowerCase(Locale.ROOT);
        if (HASH.matcher(revision).matches()) return new InstalledBuild(revision);
        Matcher describe = DESCRIBE.matcher(revision);
        if (describe.matches()) return new InstalledBuild(describe.group(1));
        return new InstalledBuild(null);
    }

}
