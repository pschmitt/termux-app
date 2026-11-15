package com.termux.app.update;

import androidx.annotation.Nullable;

import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Semantic-version ordering for the versionName embedded in a checksum-verified add-on APK. */
final class AddonVersion implements Comparable<AddonVersion> {

    private static final Pattern VERSION = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                    + "(?:-((?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*)"
                    + "(?:\\.(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*))*))?"
                    + "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$");

    private final BigInteger major;
    private final BigInteger minor;
    private final BigInteger patch;
    @Nullable private final String prerelease;

    private AddonVersion(BigInteger major, BigInteger minor, BigInteger patch, @Nullable String prerelease) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.prerelease = prerelease;
    }

    @Nullable
    static AddonVersion parse(@Nullable String value) {
        if (value == null) return null;
        String normalized = value.startsWith("v") ? value.substring(1) : value;
        Matcher matcher = VERSION.matcher(normalized);
        if (!matcher.matches()) return null;
        return new AddonVersion(new BigInteger(matcher.group(1)), new BigInteger(matcher.group(2)),
                new BigInteger(matcher.group(3)), matcher.group(4));
    }

    static boolean isCandidateOlder(@Nullable String installed, @Nullable String candidate) {
        AddonVersion installedVersion = parse(installed);
        AddonVersion candidateVersion = parse(candidate);
        return installedVersion != null && candidateVersion != null && candidateVersion.compareTo(installedVersion) < 0;
    }

    @Override
    public int compareTo(AddonVersion other) {
        int result = major.compareTo(other.major);
        if (result != 0) return result;
        result = minor.compareTo(other.minor);
        if (result != 0) return result;
        result = patch.compareTo(other.patch);
        if (result != 0) return result;
        if (prerelease == null) return other.prerelease == null ? 0 : 1;
        if (other.prerelease == null) return -1;
        String[] left = prerelease.split("\\.");
        String[] right = other.prerelease.split("\\.");
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            boolean leftNumeric = left[i].matches("\\d+");
            boolean rightNumeric = right[i].matches("\\d+");
            if (leftNumeric && rightNumeric) result = new BigInteger(left[i]).compareTo(new BigInteger(right[i]));
            else if (leftNumeric != rightNumeric) result = leftNumeric ? -1 : 1;
            else result = left[i].compareTo(right[i]);
            if (result != 0) return result;
        }
        return Integer.compare(left.length, right.length);
    }
}
