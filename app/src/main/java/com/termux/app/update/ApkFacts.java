package com.termux.app.update;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.Set;

/** What the install guard needs to know about an installed or a downloaded APK. */
public final class ApkFacts {

    public final String packageName;
    /** The {@code android:sharedUserId} of the manifest, if any. */
    @Nullable public final String sharedUserId;
    public final long versionCode;
    /** SHA-256 (lowercase hex) of every current signing certificate. */
    public final Set<String> signerDigests;

    public ApkFacts(@Nullable String packageName, @Nullable String sharedUserId, long versionCode,
                    @NonNull Set<String> signerDigests) {
        this.packageName = packageName;
        this.sharedUserId = sharedUserId;
        this.versionCode = versionCode;
        this.signerDigests = Collections.unmodifiableSet(signerDigests);
    }

    /**
     * Whether {@code archive} may replace {@code installed}, the installed copy of {@code target};
     * null when it may, else the reason. {@code core} is this running app: every target shares its
     * user id, so an archive signed by anyone else could neither install nor be trusted next to it.
     * Android enforces the same signing key itself, but a refusal before the session is cheaper and
     * clearer. A same version code is allowed (rolling builds share one) when it is the same
     * package signed by the same certificates; an older one is a downgrade.
     */
    @Nullable
    public static UpdateFailure checkInstallable(@NonNull UpdateTarget target, @Nullable ApkFacts installed,
                                                 @NonNull ApkFacts archive, @NonNull ApkFacts core) {
        if (archive.packageName == null || !archive.packageName.equals(target.packageName)
                || (installed != null && !archive.packageName.equals(installed.packageName))) {
            return UpdateFailure.WRONG_PACKAGE;
        }
        if (!UpdateTarget.SHARED_USER_ID.equals(archive.sharedUserId)) return UpdateFailure.SHARED_USER_MISMATCH;
        if (archive.signerDigests.isEmpty() || core.signerDigests.isEmpty()
                || (installed != null && installed.signerDigests.isEmpty())
                || (installed != null && !archive.signerDigests.equals(installed.signerDigests))
                || !archive.signerDigests.equals(core.signerDigests)) {
            return UpdateFailure.SIGNATURE_MISMATCH;
        }
        if (installed != null && archive.versionCode < installed.versionCode) return UpdateFailure.DOWNGRADE;
        return null;
    }

}
