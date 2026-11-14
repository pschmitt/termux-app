package com.termux.app.update;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.pm.PackageInfoCompat;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/** Reads package name, version code and signing certificates from the installed app and from an APK file. */
public final class ApkInspector {

    private ApkInspector() {}

    /** Facts about this app as installed; null if the package manager cannot tell. */
    @Nullable
    public static ApkFacts installed(@NonNull Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), signingFlags());
            return facts(info);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
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
        return new ApkFacts(info.packageName, PackageInfoCompat.getLongVersionCode(info), digests(info));
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
