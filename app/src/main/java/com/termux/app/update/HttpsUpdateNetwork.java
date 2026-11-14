package com.termux.app.update;

import android.os.StatFs;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * {@link UpdateNetwork} over {@link HttpURLConnection}. Every hop, the first request and each
 * redirect behind it, must be HTTPS on the default port to a host the caller's policy allows;
 * redirects are followed by hand so none can leave those hosts or downgrade to HTTP. Reads are
 * bounded, timeouts are set, and nothing the server says is used as a path or a command.
 */
public final class HttpsUpdateNetwork implements UpdateNetwork {

    public static final long MAX_JSON_BYTES = 2 * 1024 * 1024;

    private static final int MAX_REDIRECTS = 5;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final long REPORT_EVERY_BYTES = 256 * 1024;

    private interface HostPolicy {
        boolean allows(String host);
    }

    @NonNull
    @Override
    public String fetchApiJson(@NonNull String url) throws IOException {
        return readText(url, UpdateTrust::isTrustedApiHost, "application/vnd.github+json", MAX_JSON_BYTES);
    }

    @NonNull
    @Override
    public String fetchAssetText(@NonNull String url, long maxBytes) throws IOException {
        return readText(url, UpdateTrust::isTrustedDownloadHost, "*/*", maxBytes);
    }

    @Override
    public void download(@NonNull String url, @NonNull File dest, long maxBytes, @NonNull ProgressListener listener)
            throws IOException {
        HttpURLConnection connection = open(url, UpdateTrust::isTrustedDownloadHost, "application/octet-stream");
        try {
            long total = connection.getContentLength();
            if (total > maxBytes) throw new IOException("Download larger than " + maxBytes + " bytes");
            long done = 0;
            long reported = 0;
            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buffer = new byte[64 * 1024];
                while (true) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled");
                    int read = in.read(buffer);
                    if (read < 0) break;
                    done += read;
                    if (done > maxBytes) throw new IOException("Download larger than " + maxBytes + " bytes");
                    out.write(buffer, 0, read);
                    if (done - reported >= REPORT_EVERY_BYTES) {
                        reported = done;
                        listener.onProgress(done, total);
                    }
                }
                out.getFD().sync();
            } catch (InterruptedIOException e) {
                throw e;
            } catch (IOException e) {
                String message = e.getMessage();
                if (message != null && (message.contains("ENOSPC") || message.toLowerCase(Locale.ROOT).contains("no space left"))) {
                    throw new InsufficientStorageException(message);
                }
                throw e;
            }
            if (total >= 0 && done != total) throw new IOException("Download ended early");
            listener.onProgress(done, total);
        } finally {
            connection.disconnect();
        }
    }

    @Override
    public long freeBytes(@NonNull File dir) {
        return new StatFs(dir.getAbsolutePath()).getAvailableBytes();
    }

    private static String readText(String url, HostPolicy policy, String accept, long maxBytes) throws IOException {
        HttpURLConnection connection = open(url, policy, accept);
        try {
            if (connection.getContentLength() > maxBytes) throw new IOException("Response too large");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (out.size() + read > maxBytes) throw new IOException("Response too large");
                    out.write(buffer, 0, read);
                }
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url, HostPolicy policy, String accept) throws IOException {
        URL current = new URL(url);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            String host = current.getHost();
            if (!"https".equals(current.getProtocol()) || host == null || !policy.allows(host)
                    || current.getUserInfo() != null || (current.getPort() != -1 && current.getPort() != 443)) {
                throw new IOException("Refusing to connect to " + host);
            }
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", accept);
            connection.setRequestProperty("User-Agent", "TermuxPP-updater");
            int code = connection.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new IOException("Redirect without a location");
                current = new URL(current, location);
                continue;
            }
            if (code < 200 || code >= 300) {
                connection.disconnect();
                throw new HttpStatusException(code);
            }
            return connection;
        }
        throw new IOException("Too many redirects");
    }

}
