package com.termux.app.update;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.pm.PackageInfoCompat;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * Reads package name, shared user id, version code and signing certificates from an installed app
 * and from an APK file, and the commit a companion declares in its manifest.
 */
public final class ApkInspector {

    private ApkInspector() {}

    /** Facts about {@code target} as installed; null if it is not installed or the package manager cannot tell. */
    @Nullable
    public static ApkFacts installed(@NonNull Context context, @NonNull UpdateTarget target) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(target.packageName, signingFlags());
            return facts(info);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    /** Whether the package manager knows {@code target}. */
    public static boolean isInstalled(@NonNull Context context, @NonNull UpdateTarget target) {
        try {
            context.getPackageManager().getPackageInfo(target.packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

    /**
     * The commit identity a companion declares in its manifest, as the installed package manager
     * reports it; non-comparable when absent or not in the {@code git-<hex>} form.
     */
    @NonNull
    public static InstalledBuild declaredBuild(@NonNull Context context, @NonNull UpdateTarget target) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(target.packageName,
                    PackageManager.GET_META_DATA);
            Bundle metaData = info.applicationInfo == null ? null : info.applicationInfo.metaData;
            Object value = metaData == null ? null : metaData.get(UpdateTarget.REVISION_META_DATA);
            return InstalledBuild.parseMetaData(value instanceof String ? (String) value : null);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return InstalledBuild.parseMetaData(null);
        }
    }

    /** Facts about the APK at {@code file}; null if it cannot be parsed. */
    @Nullable
    public static ApkFacts archive(@NonNull Context context, @NonNull File file) {
        try {
            PackageInfo info = context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), signingFlags());
            return info == null ? null : facts(info);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static int signingFlags() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    @NonNull
    private static ApkFacts facts(@NonNull PackageInfo info) {
        return new ApkFacts(info.packageName, info.sharedUserId, PackageInfoCompat.getLongVersionCode(info), digests(info));
    }

    @SuppressWarnings("deprecation")
    private static Set<String> digests(PackageInfo info) {
        Signature[] signatures = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            SigningInfo signing = info.signingInfo;
            if (signing != null) signatures = signing.getApkContentsSigners();
        } else {
            signatures = info.signatures;
        }
        Set<String> result = new HashSet<>();
        if (signatures != null) {
            for (Signature signature : signatures) result.add(UpdateTrust.sha256Hex(signature.toByteArray()));
        }
        return result;
    }

}
