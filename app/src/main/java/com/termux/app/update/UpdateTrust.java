package com.termux.app.update;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the updater trusts, in one place: the repositories it may install from (those of the
 * {@link UpdateTarget} allowlist), the hosts it may talk to, and the integrity primitives. Nothing
 * downloaded is ever interpreted as a command or a path; release tags and asset names are validated
 * against restrictive allowlists before they can form a download URL.
 */
public final class UpdateTrust {

    /** Characters an asset name may consist of: no path separators, no spaces, no leading dot. */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_+][A-Za-z0-9._+-]{0,127}");

    private static final Pattern SHA256_LINE = Pattern.compile("([0-9a-fA-F]{64})[ \\t]+\\*?(.+)");
    private static final Pattern SAFE_TAG = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}");

    private UpdateTrust() {}

    public static boolean isSafeAssetName(@Nullable String name) {
        return name != null && SAFE_NAME.matcher(name).matches() && !name.contains("..");
    }

    /**
     * The download URL of the asset {@code name} from a pinned, validated release tag. The host and
     * repository are fixed here; only a safe tag and asset name from trusted API metadata are used.
     */
    @NonNull
    public static String assetUrl(@NonNull UpdateTarget target, @NonNull String name) {
        return assetUrl(target, UpdateTarget.LATEST_TAG, name);
    }

    @NonNull
    public static String assetUrl(@NonNull UpdateTarget target, @NonNull String tag, @NonNull String name) {
        if (!isSafeTag(tag) || !isSafeAssetName(name)) throw new IllegalArgumentException("Unsafe release asset");
        return "https://github.com" + target.assetPathPrefix(tag) + name;
    }

    public static boolean isSafeTag(@Nullable String tag) {
        return tag != null && SAFE_TAG.matcher(tag).matches() && !tag.contains("..");
    }

    /**
     * Only HTTPS release assets of {@code target}'s repository's latest release may be installed: host
     * exactly github.com, default port, no credentials, no query or fragment, no dot segments.
     * GitHub then redirects to its asset CDN, which {@link #isTrustedDownloadHost} covers.
     */
    public static boolean isTrustedAssetUrl(@NonNull UpdateTarget target, @Nullable String url) {
        return isTrustedAssetUrl(target, UpdateTarget.LATEST_TAG, url);
    }

    public static boolean isTrustedAssetUrl(@NonNull UpdateTarget target, @NonNull String tag,
                                            @Nullable String url) {
        if (!target.hasReleaseSource() || url == null || !isSafeTag(tag)) return false;
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return false;
        }
        String path = uri.getRawPath();
        if (!"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost())
                || uri.getPort() != -1 || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || path == null || !path.startsWith(target.assetPathPrefix(tag))) {
            return false;
        }
        String name = path.substring(target.assetPathPrefix(tag).length());
        return isSafeAssetName(name);
    }

    /** Hosts GitHub serves release downloads from (and redirects them to). */
    public static boolean isTrustedDownloadHost(@Nullable String host) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        return host.equals("github.com") || host.endsWith(".githubusercontent.com");
    }

    /** The only host release metadata is read from. */
    public static boolean isTrustedApiHost(@Nullable String host) {
        return host != null && host.toLowerCase(Locale.ROOT).equals("api.github.com");
    }

    /**
     * Parses {@code sha256sum} output ({@code <64 hex> <name>}, {@code *} marks binary mode) into
     * name to lowercase hex. Malformed lines are ignored; a name listed with two different digests
     * is ambiguous and dropped.
     */
    @NonNull
    public static Map<String, String> parseSha256Sums(@Nullable String text) {
        Map<String, String> sums = new LinkedHashMap<>();
        Set<String> ambiguous = new HashSet<>();
        if (text == null) return sums;
        for (String line : text.split("\n")) {
            Matcher matcher = SHA256_LINE.matcher(line.trim());
            if (!matcher.matches()) continue;
            String name = matcher.group(2);
            String digest = matcher.group(1).toLowerCase(Locale.ROOT);
            String previous = sums.put(name, digest);
            if (previous != null && !previous.equals(digest)) ambiguous.add(name);
        }
        for (String name : ambiguous) sums.remove(name);
        return sums;
    }

    @NonNull
    public static String sha256Hex(@NonNull File file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return toHex(digest.digest());
    }

    @NonNull
    public static String sha256Hex(@NonNull byte[] bytes) {
        return toHex(newDigest().digest(bytes));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

}
