package com.termux.app.update;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.BuildConfig;
import com.termux.shared.logger.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs checks and updates on explicit request from the settings screen; nothing here is scheduled.
 *
 * <p>An update goes through these gates, each of which ends it with a distinct {@link UpdateFailure}:
 * trusted source URL, Shizuku ready, enough free storage, download of the APK and of
 * {@code SHA256SUMS} from the fixed release, SHA-256 and size match, the archive parses, it is this
 * package with the installed signing certificates and no lower version code, then the privileged
 * install. The downloaded file lives in the app's private cache and is wiped before each run, after
 * the install and on every failure.
 */
// The singleton only ever holds the application context.
@SuppressLint("StaticFieldLeak")
public final class UpdateController {

    public interface Listener {
        @MainThread
        void onUpdateStateChanged(@NonNull UpdateState state);
    }

    static final String ACTION_INSTALL_STATUS = "dev.pschmitt.termux.app.update.INSTALL_STATUS";

    private static final String LOG_TAG = "UpdateController";
    private static final String UPDATE_DIR = "app-update";
    private static final String UPDATE_FILE = "update.apk";
    private static final long MAX_CHECKSUMS_BYTES = 64 * 1024;
    private static final long PROGRESS_INTERVAL_MS = 250;

    private static volatile UpdateController instance;

    @NonNull
    public static UpdateController getInstance(@NonNull Context context) {
        UpdateController local = instance;
        if (local == null) {
            synchronized (UpdateController.class) {
                local = instance;
                if (local == null) {
                    local = new UpdateController(context.getApplicationContext(), new HttpsUpdateNetwork());
                    instance = local;
                }
            }
        }
        return local;
    }

    private final Context context;
    private final UpdateNetwork network;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new ArrayList<>();

    private volatile UpdateState state = UpdateState.IDLE;
    @Nullable private Future<?> running;
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private volatile boolean installCommitted;

    UpdateController(@NonNull Context context, @NonNull UpdateNetwork network) {
        this.context = context;
        this.network = network;
    }

    @NonNull
    public UpdateState getState() {
        return state;
    }

    @MainThread
    public void addListener(@NonNull Listener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
    }

    @MainThread
    public void removeListener(@NonNull Listener listener) {
        listeners.remove(listener);
    }

    /** Asks GitHub whether a newer build of the channel exists. Ignored while another run is busy. */
    @MainThread
    public void check(@NonNull UpdateChannel channel) {
        if (state.phase.isBusy()) return;
        publish(UpdateState.of(UpdateState.Phase.CHECKING, null));
        cancelRequested.set(false);
        running = executor.submit(() -> {
            UpdateChecker.Result result = new UpdateChecker(network).check(
                    BuildConfig.GIT_REVISION, BuildConfig.TERMUX_PACKAGE_VARIANT, supportedAbis(), channel);
            if (cancelRequested.get()) {
                publish(UpdateState.IDLE);
                return;
            }
            switch (result.kind) {
                case AVAILABLE:
                    publish(UpdateState.of(UpdateState.Phase.AVAILABLE, result.offer));
                    break;
                case UP_TO_DATE:
                    publish(UpdateState.of(UpdateState.Phase.UP_TO_DATE, null));
                    break;
                case NOT_ELIGIBLE:
                    publish(UpdateState.of(UpdateState.Phase.NOT_ELIGIBLE, null));
                    break;
                default:
                    publish(UpdateState.checkFailed(result.detail));
                    break;
            }
        });
    }

    /** Downloads, verifies and installs the offer found by the last check. */
    @MainThread
    public void startUpdate() {
        UpdateState current = state;
        if (current.phase != UpdateState.Phase.AVAILABLE || current.offer == null) return;
        UpdateChecker.Offer offer = current.offer;
        cancelRequested.set(false);
        installCommitted = false;
        publish(UpdateState.progress(offer, 0, offer.apk.size > 0 ? offer.apk.size : -1));
        running = executor.submit(() -> runUpdate(offer));
    }

    /** Stops a check or download. Once the install is handed to the system it can no longer be stopped. */
    @MainThread
    public void cancel() {
        UpdateState.Phase phase = state.phase;
        if (phase != UpdateState.Phase.CHECKING && phase != UpdateState.Phase.DOWNLOADING
                && phase != UpdateState.Phase.VERIFYING) return;
        cancelRequested.set(true);
        Future<?> future = running;
        if (future != null) future.cancel(true);
    }

    /** Back to the idle state, e.g. after the screen showed a failure. */
    @MainThread
    public void reset() {
        if (!state.phase.isBusy()) publish(UpdateState.IDLE);
    }

    private void runUpdate(UpdateChecker.Offer offer) {
        File dir = new File(context.getCacheDir(), UPDATE_DIR);
        File file = new File(dir, UPDATE_FILE);
        try {
            UpdateFailure failure = download(offer, dir, file);
            if (failure == null) failure = verify(offer, file);
            if (failure == null) failure = install(offer, file);
            if (failure != null) publish(UpdateState.failed(offer, failure, null));
        } catch (UpdateNetwork.InsufficientStorageException e) {
            publish(UpdateState.failed(offer, UpdateFailure.INSUFFICIENT_STORAGE, null));
        } catch (IOException e) {
            publish(cancelRequested.get() || Thread.currentThread().isInterrupted()
                    ? UpdateState.failed(offer, UpdateFailure.CANCELLED, null)
                    : UpdateState.failed(offer, UpdateFailure.DOWNLOAD_FAILED, e.getMessage()));
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Update failed", e);
            publish(UpdateState.failed(offer, UpdateFailure.INSTALL_FAILED, e.getMessage()));
        } finally {
            wipe(dir);
        }
    }

    /** Gates 1 to 3 and the transfer; returns null on success. */
    @Nullable
    private UpdateFailure download(UpdateChecker.Offer offer, File dir, File file) throws IOException {
        String apkUrl = UpdateTrust.assetUrl(offer.apk.name);
        String sumsUrl = UpdateTrust.assetUrl(offer.checksums.name);
        if (!UpdateTrust.isTrustedAssetUrl(apkUrl) || !UpdateTrust.isTrustedAssetUrl(sumsUrl)) {
            return UpdateFailure.NOT_TRUSTED;
        }
        if (ShizukuInstaller.state(context) != ShizukuState.READY) return UpdateFailure.SHIZUKU_NOT_READY;

        wipe(dir);
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("cannot create " + dir);
        // The APK, plus room for the install session's own copy and the archive parse.
        if (offer.apk.size > 0 && network.freeBytes(dir) < offer.apk.size * 2) {
            return UpdateFailure.INSUFFICIENT_STORAGE;
        }

        long total = offer.apk.size > 0 ? offer.apk.size : -1;
        long[] last = {0};
        network.download(apkUrl, file, UpdateChecker.MAX_APK_BYTES, (done, reported) -> {
            long now = System.currentTimeMillis();
            if (done != (reported >= 0 ? reported : total) && now - last[0] < PROGRESS_INTERVAL_MS) return;
            last[0] = now;
            publish(UpdateState.progress(offer, done, reported >= 0 ? reported : total));
        });
        return null;
    }

    /** Checksum, archive identity and version guard; returns null when the file may be installed. */
    @Nullable
    private UpdateFailure verify(UpdateChecker.Offer offer, File file) throws IOException {
        if (Thread.currentThread().isInterrupted()) return UpdateFailure.CANCELLED;
        publish(UpdateState.of(UpdateState.Phase.VERIFYING, offer));

        String sums = network.fetchAssetText(UpdateTrust.assetUrl(offer.checksums.name), MAX_CHECKSUMS_BYTES);
        Map<String, String> table = UpdateTrust.parseSha256Sums(sums);
        String expected = table.get(offer.apk.name);
        if (expected == null) return UpdateFailure.CHECKSUM_UNAVAILABLE;
        if (offer.apk.size > 0 && file.length() != offer.apk.size) return UpdateFailure.CHECKSUM_MISMATCH;
        if (!expected.equals(UpdateTrust.sha256Hex(file))) return UpdateFailure.CHECKSUM_MISMATCH;

        ApkFacts installed = ApkInspector.installed(context);
        ApkFacts archive = ApkInspector.archive(context, file);
        if (installed == null || archive == null) return UpdateFailure.UNREADABLE_APK;
        return ApkFacts.checkInstallable(installed, archive);
    }

    @Nullable
    private UpdateFailure install(UpdateChecker.Offer offer, File file) throws Exception {
        if (Thread.currentThread().isInterrupted() || cancelRequested.get()) return UpdateFailure.CANCELLED;
        // Re-check just before the privileged call: Shizuku may have stopped during the download.
        if (ShizukuInstaller.state(context) != ShizukuState.READY) return UpdateFailure.SHIZUKU_NOT_READY;
        publish(UpdateState.of(UpdateState.Phase.INSTALLING, offer));
        installCommitted = true;
        try {
            ShizukuInstaller.install(context, file, statusSender());
        } catch (Exception e) {
            installCommitted = false;
            throw e;
        }
        return null;
    }

    private IntentSender statusSender() {
        Intent intent = new Intent(context, InstallStatusReceiver.class).setAction(ACTION_INSTALL_STATUS);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        // The system adds the status extras to the intent, so it must be mutable (API 31+ asks).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getBroadcast(context, 0, intent, flags).getIntentSender();
    }

    /** The verdict from {@link InstallStatusReceiver}. On success this process is normally replaced first. */
    void onInstallStatus(@NonNull Intent intent) {
        if (!installCommitted && state.phase != UpdateState.Phase.INSTALLING) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        UpdateChecker.Offer offer = state.offer;
        installCommitted = false;
        switch (status) {
            case PackageInstaller.STATUS_SUCCESS:
                publish(UpdateState.of(UpdateState.Phase.IDLE, null));
                break;
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                // The privileged session should not need a confirmation. If it asks, refuse: no
                // confirmation intent from the system is launched on the update's behalf.
                publish(UpdateState.failed(offer, UpdateFailure.INSTALL_FAILED, "installer asked for confirmation"));
                break;
            default:
                publish(UpdateState.failed(offer, UpdateFailure.INSTALL_FAILED, message));
                break;
        }
    }

    private void publish(UpdateState next) {
        state = next;
        mainHandler.post(() -> {
            // Deliver the newest state; a burst of progress posts collapses to the last value.
            UpdateState latest = state;
            for (Listener listener : new ArrayList<>(listeners)) listener.onUpdateStateChanged(latest);
        });
    }

    private static List<String> supportedAbis() {
        return Arrays.asList(Build.SUPPORTED_ABIS);
    }

    private static void wipe(File dir) {
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!child.delete()) Logger.logError(LOG_TAG, "Could not delete " + child.getName());
            }
        }
        // The directory itself is cheap to keep; an empty one holds nothing.
    }

}
