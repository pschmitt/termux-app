package com.termux.app.update;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.Set;

/** What the install guard needs to know about an installed or a downloaded APK. */
public final class ApkFacts {

    public final String packageName;
    public final long versionCode;
    /** SHA-256 (lowercase hex) of every current signing certificate. */
    public final Set<String> signerDigests;

    public ApkFacts(@Nullable String packageName, long versionCode, @NonNull Set<String> signerDigests) {
        this.packageName = packageName;
        this.versionCode = versionCode;
        this.signerDigests = Collections.unmodifiableSet(signerDigests);
    }

    /**
     * Whether {@code archive} may replace {@code installed}; null when it may, else the reason.
     * Android enforces the same signing key itself, but a refusal before the session is cheaper and
     * clearer. A same version code is allowed (rolling builds share one) when it is the same
     * package signed by the same certificates; an older one is a downgrade.
     */
    @Nullable
    public static UpdateFailure checkInstallable(@NonNull ApkFacts installed, @NonNull ApkFacts archive) {
        if (archive.packageName == null || !archive.packageName.equals(installed.packageName)) {
            return UpdateFailure.WRONG_PACKAGE;
        }
        if (archive.signerDigests.isEmpty() || installed.signerDigests.isEmpty()
                || !archive.signerDigests.equals(installed.signerDigests)) {
            return UpdateFailure.SIGNATURE_MISMATCH;
        }
        if (archive.versionCode < installed.versionCode) return UpdateFailure.DOWNGRADE;
        return null;
    }

}
