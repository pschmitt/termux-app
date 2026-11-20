package com.termux.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import dev.pschmitt.termux.shared.logger.Logger;
import dev.pschmitt.termux.shared.termux.nix.TermuxNix;

/**
 * Publishes the connected Wi-Fi name to {@code network.json} in the nixpp runtime directory
 * ({@code /run/nixpp-runtime/network.json} in the guest), where the guest service supervisor
 * starts and stops services restricted with {@code services.<name>.allowedSsids}.
 *
 * <p>The file is rewritten when the SSID changes. Every minute its modification time is renewed
 * without rewriting it: the guest treats state older than five minutes (by mtime) as unknown, so
 * restricted services stop when the app is gone. Network callbacks are debounced, since signal
 * strength changes report new capabilities all the time.
 * {@code ssid} is null without Wi-Fi or when Android withholds the name (no location permission).
 */
public final class NetworkStatePublisher {

    private static final String LOG_TAG = "NetworkStatePublisher";
    private static final String FILE_NAME = "network.json";
    private static final long REFRESH_MS = 60_000;
    private static final long CHANGE_DEBOUNCE_MS = 1_000;

    private final Context context;
    private final HandlerThread thread = new HandlerThread("termuxpp-network-state");
    private Handler handler;
    @Nullable private ConnectivityManager.NetworkCallback callback;
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            publish();
            handler.removeCallbacks(this);
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    /* The SSID last written, only touched on the handler thread. */
    private boolean published;
    @Nullable private String publishedSsid;

    private void networkChanged() {
        handler.removeCallbacks(refresh);
        handler.postDelayed(refresh, CHANGE_DEBOUNCE_MS);
    }

    public NetworkStatePublisher(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    public void start() {
        thread.start();
        handler = new Handler(thread.getLooper());
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity != null) {
            callback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(@NonNull Network network) { networkChanged(); }
                @Override public void onLost(@NonNull Network network) { networkChanged(); }
                @Override public void onCapabilitiesChanged(@NonNull Network network,
                                                            @NonNull NetworkCapabilities capabilities) {
                    networkChanged();
                }
            };
            try {
                connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback);
            } catch (RuntimeException e) {
                Logger.logWarn(LOG_TAG, "Cannot follow Wi-Fi changes: " + e.getMessage());
                callback = null;
            }
        }
        handler.post(refresh);
    }

    public void stop() {
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity != null && callback != null) {
            try {
                connectivity.unregisterNetworkCallback(callback);
            } catch (RuntimeException ignored) {
            }
        }
        callback = null;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        thread.quitSafely();
    }

    private void publish() {
        File runtime = new File(TermuxNix.RUNTIME_DIR_PATH);
        // Nothing to tell without a nixpp runtime.
        if (!runtime.isDirectory()) return;
        String ssid = AdbShellService.currentSsid(context);
        File target = new File(runtime, FILE_NAME);
        // Unchanged: only keep it fresh, no rewrite and no fsync.
        if (published && Objects.equals(ssid, publishedSsid) && target.isFile()
            && target.setLastModified(System.currentTimeMillis())) {
            return;
        }
        File temporary = new File(runtime, "." + FILE_NAME + ".tmp");
        try {
            JSONObject state = new JSONObject();
            state.put("ssid", ssid == null ? JSONObject.NULL : ssid);
            state.put("updated", System.currentTimeMillis() / 1000);
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write((state.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!temporary.setReadable(false, false) || !temporary.setReadable(true, true)
                || !temporary.setWritable(false, false) || !temporary.setWritable(true, true)) {
                Logger.logWarn(LOG_TAG, "Cannot restrict " + temporary);
            }
            if (!temporary.renameTo(target)) throw new IOException("rename failed");
            published = true;
            publishedSsid = ssid;
        } catch (IOException | JSONException e) {
            Logger.logWarn(LOG_TAG, "Cannot publish the network state: " + e.getMessage());
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
        }
    }
}
