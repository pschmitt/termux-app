package com.termux.app.update;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;

/** The network side of an update: the one place that touches HTTP, and what the tests replace. */
public interface UpdateNetwork {

    interface ProgressListener {
        /** {@code total} is -1 while the size is unknown. */
        void onProgress(long done, long total);
    }

    /** Thrown for any HTTP status outside 2xx. */
    class HttpStatusException extends IOException {
        public final int code;

        public HttpStatusException(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    /** Thrown when the device (or the download) runs out of storage. */
    class InsufficientStorageException extends IOException {
        public InsufficientStorageException(String message) {
            super(message);
        }
    }

    /** GET of release metadata from the API host (at most {@link HttpsUpdateNetwork#MAX_JSON_BYTES}). */
    @NonNull String fetchApiJson(@NonNull String url) throws IOException;

    /** GET of a small text asset such as SHA256SUMS from a trusted download host. */
    @NonNull String fetchAssetText(@NonNull String url, long maxBytes) throws IOException;

    /** Streams {@code url} (trusted download hosts only) into {@code dest}, at most {@code maxBytes}. */
    void download(@NonNull String url, @NonNull File dest, long maxBytes, @NonNull ProgressListener listener)
            throws IOException;

    long freeBytes(@NonNull File dir);

}
