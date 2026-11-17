package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Persistent allowlist of individual ADB shell client certificates. */
public final class AdbShellAuthorizations {
    private static final String PREFERENCES = "adb_shell_authorizations";
    private final SharedPreferences preferences;

    public AdbShellAuthorizations(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    static String fingerprint(X509Certificate certificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    synchronized boolean contains(X509Certificate certificate) throws Exception {
        return preferences.contains(fingerprint(certificate));
    }

    synchronized boolean authorize(X509Certificate certificate, String name) throws Exception {
        validateName(name);
        String id = fingerprint(certificate);
        if (!preferences.contains(id) && preferences.getAll().size() >= 32) return false;
        return preferences.edit().putString(id, name.trim()).commit();
    }

    synchronized void clear() {
        preferences.edit().clear().commit();
    }

    static void validateName(String name) {
        if (name == null || name.trim().isEmpty() || name.length() > 128) {
            throw new IllegalArgumentException("Invalid client name");
        }
        for (int offset = 0; offset < name.length();) {
            int character = name.codePointAt(offset);
            int type = Character.getType(character);
            if (Character.isISOControl(character) || type == Character.FORMAT || type == Character.SURROGATE) {
                throw new IllegalArgumentException("Invalid client name");
            }
            offset += Character.charCount(character);
        }
    }

    public synchronized boolean revoke(String fingerprint) {
        if (!validFingerprint(fingerprint)) throw new IllegalArgumentException("Invalid fingerprint");
        return preferences.edit().remove(fingerprint).commit();
    }

    public synchronized List<Entry> list() {
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (validFingerprint(entry.getKey()) && entry.getValue() instanceof String) {
                entries.add(new Entry(entry.getKey(), (String) entry.getValue()));
            }
        }
        Collections.sort(entries, (left, right) -> left.fingerprint.compareTo(right.fingerprint));
        return entries;
    }

    private static boolean validFingerprint(String fingerprint) {
        return fingerprint != null && fingerprint.matches("[0-9a-f]{64}");
    }

    public static final class Entry {
        public final String fingerprint;
        public final String name;

        Entry(String fingerprint, String name) {
            this.fingerprint = fingerprint;
            this.name = name;
        }
    }
}
