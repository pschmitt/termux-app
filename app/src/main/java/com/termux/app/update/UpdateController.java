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
import dev.pschmitt.termux.shared.logger.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs checks and updates on explicit request from the Updates screen; nothing here is scheduled.
 *
 * <p>One check covers the integrated TermuxPP application. No unattended updates run. Only one
 * check or update runs at a time. Replacing the APK ends its processes, including terminal sessions
 * and background Tasker actions.
 *
 * <p>An update goes through these gates, each of which ends it with a distinct {@link UpdateFailure}:
 * trusted source URL, Shizuku ready, enough free storage, download of the APK and of
 * {@code SHA256SUMS} from the rolling release, SHA-256 and size match, the archive parses, it is
 * the expected package with the shared user id and the installed signing certificates, and no
 * lower version code, then the privileged install. The downloaded file lives in
 * the app's private cache and is wiped before each run, after the install and on every failure.
 */
// The singleton only ever holds the application context.
@SuppressLint("StaticFieldLeak")
public final class UpdateController {

    public interface Listener {
        @MainThread
        void onUpdateStateChanged(@NonNull UpdateTarget target, @NonNull UpdateState state);
    }

    static final String ACTION_INSTALL_STATUS = "dev.pschmitt.termux.app.update.INSTALL_STATUS";

    private static final String LOG_TAG = "UpdateController";
    private static final String UPDATE_DIR = "app-update";
    private static final String UPDATE_FILE = "update.apk";
    private static final String ADDON_CHECK_DIR_PREFIX = "app-update-check-";
    private static final long MAX_CHECKSUMS_BYTES = 64 * 1024;
    private static final long PROGRESS_INTERVAL_MS = 250;
    private static final int MAX_PARALLEL_CHECKS = 3;

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
    private final ExecutorService checkExecutor = Executors.newFixedThreadPool(MAX_PARALLEL_CHECKS);
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<UpdateTarget, UpdateState> states = new ConcurrentHashMap<>();

    private final Object runLock = new Object();
    /** The thread of the task that is running, to interrupt it on cancel. Guarded by {@link #runLock}. */
    @Nullable private Thread runner;
    /** Active child checks, so cancellation interrupts the network workers as well as their coordinator. */
    private final List<Thread> checkRunners = new ArrayList<>();
    /** A task is queued or running; set from the main thread, cleared when it ends. */
    private volatile boolean taskActive;
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    /** The target whose install was handed to the system and has not answered yet. */
    @Nullable private volatile UpdateTarget installing;

    UpdateController(@NonNull Context context, @NonNull UpdateNetwork network) {
        this.context = context;
        this.network = network;
        for (UpdateTarget target : UpdateTarget.values()) {
            if (target.versionedApkPrefix != null) {
                wipe(new File(context.getCacheDir(), ADDON_CHECK_DIR_PREFIX + target.id));
            }
        }
    }

    @NonNull
    public UpdateState getState(@NonNull UpdateTarget target) {
        UpdateState state = states.get(target);
        return state == null ? UpdateState.IDLE : state;
    }

    /** A check or an update is running; no new one may start. */
    public boolean isBusy() {
        if (taskActive) return true;
        for (UpdateTarget target : UpdateTarget.values()) {
            if (getState(target).phase.isBusy()) return true;
        }
        return false;
    }

    /** A check or a download that {@link #cancel()} can still stop. */
    public boolean isCancellable() {
        for (UpdateTarget target : UpdateTarget.values()) {
            UpdateState.Phase phase = getState(target).phase;
            if (phase == UpdateState.Phase.CHECKING || phase == UpdateState.Phase.DOWNLOADING
                    || phase == UpdateState.Phase.VERIFYING) return true;
        }
        return false;
    }

    @MainThread
    public void addListener(@NonNull Listener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
    }

    @MainThread
    public void removeListener(@NonNull Listener listener) {
        listeners.remove(listener);
    }

    /**
     * Local only, no network: refreshes the integrated app's installed state. Leaves a target that
     * is checking, updating or has a result alone.
     */
    @MainThread
    public void refreshInstalled() {
        for (UpdateTarget target : UpdateTarget.values()) {
            if (!target.hasReleaseSource()) continue;
            UpdateState.Phase phase = getState(target).phase;
            boolean installed = ApkInspector.isInstalled(context, target);
            if (!installed && phase != UpdateState.Phase.NOT_INSTALLED && !phase.isBusy()) {
                publish(target, UpdateState.of(UpdateState.Phase.NOT_INSTALLED, null));
            } else if (installed && phase == UpdateState.Phase.NOT_INSTALLED) {
                publish(target, UpdateState.IDLE);
            }
        }
    }

    /**
     * Asks GitHub whether a newer build of the channel exists. Ignored while another run is busy.
     */
    @MainThread
    public void checkAll(@NonNull UpdateChannel channel) {
        if (isBusy()) return;
        cancelRequested.set(false);
        for (UpdateTarget target : UpdateTarget.values()) {
            if (!target.isUpdatable()) continue;
            publish(target, UpdateState.of(UpdateState.Phase.CHECKING, null));
        }
        submit(() -> runChecks(channel));
    }

    /** Checks the core release when the app is not yet installed. */
    @MainThread
    public void checkOne(@NonNull UpdateTarget target, @NonNull UpdateChannel channel) {
        if (!target.isUpdatable() || isBusy() || ApkInspector.isInstalled(context, target)) return;
        cancelRequested.set(false);
        publish(target, UpdateState.of(UpdateState.Phase.CHECKING, null));
        submit(() -> publishCheckResult(target, performCheck(target, channel)));
    }

    /**
     * Checks one target, installed or not (the command-line self-update). Ignored while another run
     * is busy.
     */
    @MainThread
    public void checkTarget(@NonNull UpdateTarget target, @NonNull UpdateChannel channel) {
        if (!target.isUpdatable() || isBusy()) return;
        cancelRequested.set(false);
        publish(target, UpdateState.of(UpdateState.Phase.CHECKING, null));
        submit(() -> publishCheckResult(target, performCheck(target, channel)));
    }

    private void runChecks(UpdateChannel channel) {
        List<UpdateTarget> targets = new ArrayList<>();
        for (UpdateTarget target : UpdateTarget.values()) {
            if (target.isUpdatable() && getState(target).phase == UpdateState.Phase.CHECKING) targets.add(target);
        }

        int next = 0;
        List<Future<CheckResult>> pending = new ArrayList<>();
        try {
            while (next < targets.size() || !pending.isEmpty()) {
                while (!cancelRequested.get() && next < targets.size() && pending.size() < MAX_PARALLEL_CHECKS) {
                    UpdateTarget target = targets.get(next++);
                    Future<CheckResult> future = checkExecutor.submit(() -> {
                        Thread current = Thread.currentThread();
                        synchronized (runLock) {
                            checkRunners.add(current);
                        }
                        try {
                            if (cancelRequested.get()) return new CheckResult(target, null);
                            return new CheckResult(target, performCheck(target, channel));
                        } finally {
                            synchronized (runLock) {
                                checkRunners.remove(current);
                            }
                        }
                    });
                    pending.add(future);
                }
                if (cancelRequested.get()) {
                    interruptChecks();
                    awaitChecks(pending);
                    pending.clear();
                    break;
                }
                Future<CheckResult> future = pending.get(0);
                try {
                    CheckResult result = future.get();
                    if (result.result != null) publishCheckResult(result.target, result.result);
                } catch (ExecutionException e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Update check failed", e.getCause());
                } finally {
                    pending.remove(future);
                }
            }
        } catch (InterruptedException e) {
            cancelRequested.set(true);
            interruptChecks();
            awaitChecks(pending);
            Thread.currentThread().interrupt();
        } finally {
            for (UpdateTarget target : targets) {
                if (getState(target).phase == UpdateState.Phase.CHECKING) {
                    if (target.versionedApkPrefix != null && cancelRequested.get()) {
                        wipe(new File(context.getCacheDir(), ADDON_CHECK_DIR_PREFIX + target.id));
                    }
                    publish(target, UpdateState.IDLE);
                }
            }
        }
    }

    private void interruptChecks() {
        synchronized (runLock) {
            for (Thread thread : checkRunners) thread.interrupt();
        }
    }

    private static void awaitChecks(List<? extends Future<?>> checks) {
        boolean interrupted = false;
        for (Future<?> future : checks) {
            while (true) {
                try {
                    future.get();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException e) {
                    break;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static final class CheckResult {
        final UpdateTarget target;
        final UpdateChecker.Result result;

        CheckResult(UpdateTarget target, UpdateChecker.Result result) {
            this.target = target;
            this.result = result;
        }
    }

    private UpdateChecker.Result performCheck(UpdateTarget target, UpdateChannel channel) {
        boolean installedApp = ApkInspector.isInstalled(context, target);
        InstalledBuild installed = !installedApp ? InstalledBuild.parse(null)
                : target == UpdateTarget.CORE ? InstalledBuild.parse(BuildConfig.GIT_REVISION)
                : ApkInspector.declaredBuild(context, target);
        if (installedApp && requiresKnownCommitToCheck(target, installed)) {
            return new UpdateChecker.Result(UpdateChecker.Kind.NOT_ELIGIBLE, null, null);
        }
        UpdateChecker.Result result = new UpdateChecker(network).check(target, installed, BuildConfig.TERMUX_PACKAGE_VARIANT,
                supportedAbis(), channel, !installedApp);
        if (target.versionedApkPrefix == null || !installedApp || result.kind != UpdateChecker.Kind.AVAILABLE
                || result.offer == null) return result;
        return compareVerifiedAddonCandidate(target, result.offer);
    }

    /** Rolling releases require a known commit identity for safe comparison. */
    static boolean requiresKnownCommitToCheck(UpdateTarget target, InstalledBuild installed) {
        return target == UpdateTarget.CORE && !installed.isComparable();
    }

    /** Download and verify a signed add-on candidate before comparing its actual embedded version. */
    private UpdateChecker.Result compareVerifiedAddonCandidate(UpdateTarget target, UpdateChecker.Offer offer) {
        File dir = new File(context.getCacheDir(), ADDON_CHECK_DIR_PREFIX + target.id);
        File apk = new File(dir, UPDATE_FILE);
        boolean keepStaged = false;
        try {
            String tag = offer.releaseTag;
            String apkUrl = UpdateTrust.assetUrl(target, tag, offer.apk.name);
            String sumsUrl = UpdateTrust.assetUrl(target, tag, offer.checksums.name);
            if (!UpdateTrust.isTrustedAssetUrl(target, tag, apkUrl)
                    || !UpdateTrust.isTrustedAssetUrl(target, tag, sumsUrl)) {
                return new UpdateChecker.Result(UpdateChecker.Kind.FAILED, null, "untrusted release asset");
            }
            if (offer.apk.size <= 0 || offer.apk.size > UpdateChecker.MAX_APK_BYTES) {
                return new UpdateChecker.Result(UpdateChecker.Kind.FAILED, null, "invalid APK size");
            }
            wipe(dir);
            if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("cannot create check cache");
            if (network.freeBytes(dir) < offer.apk.size * 2) {
                return new UpdateChecker.Result(UpdateChecker.Kind.FAILED, null, "insufficient storage");
            }
            String sumsText = network.fetchAssetText(sumsUrl, MAX_CHECKSUMS_BYTES);
            String expected = UpdateTrust.parseSha256Sums(sumsText).get(offer.apk.name);
            if (expected == null) return new UpdateChecker.Result(UpdateChecker.Kind.FAILED, null, "checksum unavailable");
            network.download(apkUrl, apk, UpdateChecker.MAX_APK_BYTES, (done, total) -> {});
            if (apk.length() != offer.apk.size || !expected.equals(UpdateTrust.sha256Hex(apk))) {
                return new UpdateChecker.Result(UpdateChecker.Kind.FAILED, null, "checksum mismatch");
            }

            ApkFacts installedFacts = ApkInspector.installed(context, target);
            ApkFacts coreFacts = ApkInspector.installed(context, UpdateTarget.CORE);
            ApkFacts candidate = ApkInspector.archive(context, apk);
            if (installedFacts == null || coreFacts == null || candidate == null) {
                return new UpdateChecker.Result(UpdateChecker.Kind.NOT_ELIGIBLE, null, null);
            }
            UpdateFailure installability = ApkFacts.checkInstallable(target, installedFacts, candidate, coreFacts);
            if (installability != null) {
                return new UpdateChecker.Result(UpdateChecker.Kind.NOT_ELIGIBLE, null, null);
            }
            String installedDigest = ApkInspector.installedApkSha256(context, target);
            if (expected.equals(installedDigest)) {
                return new UpdateChecker.Result(UpdateChecker.Kind.UP_TO_DATE, null, null);
            }

            if (AddonVersion.isCandidateOlder(installedFacts.versionName, candidate.versionName)) {
                // Do not downgrade when an installed package carries a newer declared version.
                return new UpdateChecker.Result(UpdateChecker.Kind.NOT_ELIGIBLE, null, null);
            }
            // Equal or unparseable version names remain an explicit reinstall offer: versionCode is
            // fixed in these projects and cannot prove two APKs contain identical builds.
            if (cancelRequested.get() || Thread.currentThread().isInterrupted()) {
                return new UpdateChecker.Result(UpdateChecker.Kind.FAILED, null, "check cancelled");
            }
            keepStaged = true;
            return new UpdateChecker.Result(UpdateChecker.Kind.AVAILABLE, offer.withStagedApk(apk), null);
        } catch (UpdateNetwork.InsufficientStorageException e) {
            return new UpdateChecker.Result(UpdateChecker.Kind.FAILED, null, "insufficient storage");
        } catch (IOException | RuntimeException e) {
            return new UpdateChecker.Result(UpdateChecker.Kind.FAILED, null, String.valueOf(e.getMessage()));
        } finally {
            if (!keepStaged) wipe(dir);
        }
    }

    private void publishCheckResult(UpdateTarget target, UpdateChecker.Result result) {
        if (cancelRequested.get()) {
            if (target.versionedApkPrefix != null) {
                wipe(new File(context.getCacheDir(), ADDON_CHECK_DIR_PREFIX + target.id));
            }
            publish(target, UpdateState.IDLE);
            return;
        }
        switch (result.kind) {
            case AVAILABLE:
                publish(target, UpdateState.of(UpdateState.Phase.AVAILABLE, result.offer));
                break;
            case UP_TO_DATE:
                publish(target, UpdateState.of(UpdateState.Phase.UP_TO_DATE, null));
                break;
            case NOT_ELIGIBLE:
                publish(target, UpdateState.of(UpdateState.Phase.NOT_ELIGIBLE, null));
                break;
            case UNAVAILABLE:
                publish(target, UpdateState.of(UpdateState.Phase.UNAVAILABLE, null));
                break;
            default:
                publish(target, UpdateState.checkFailed(result.detail));
                break;
        }
    }

    /** Downloads, verifies and installs the offer the last check found for {@code target}. */
    @MainThread
    public void startUpdate(@NonNull UpdateTarget target) {
        if (!target.isUpdatable() || isBusy()) return;
        UpdateState current = getState(target);
        if (current.phase != UpdateState.Phase.AVAILABLE || current.offer == null) return;
        UpdateChecker.Offer offer = current.offer;
        cancelRequested.set(false);
        installing = null;
        publish(target, UpdateState.progress(offer, 0, offer.apk.size > 0 ? offer.apk.size : -1));
        submit(() -> runUpdate(target, offer));
    }

    /**
     * Stops a check or download. The task ends the states itself (a stopped check leaves the
     * remaining targets idle). Once the install is handed to the system it can no longer be stopped.
     */
    @MainThread
    public void cancel() {
        if (!isCancellable()) return;
        cancelRequested.set(true);
        synchronized (runLock) {
            if (runner != null) runner.interrupt();
            for (Thread thread : checkRunners) thread.interrupt();
        }
    }

    /** Runs {@code body} on the single worker thread; the task always runs to its end, so the busy flag is reliable. */
    private void submit(@NonNull Runnable body) {
        taskActive = true;
        executor.execute(() -> {
            synchronized (runLock) {
                runner = Thread.currentThread();
            }
            try {
                body.run();
            } catch (RuntimeException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Updater task failed", e);
            } finally {
                synchronized (runLock) {
                    runner = null;
                    // Clear an interrupt that arrived too late, so the next task starts clean.
                    Thread.interrupted();
                }
                taskActive = false;
            }
        });
    }

    /** Back to the idle state of every target that is not busy, e.g. after the screen showed a failure. */
    @MainThread
    public void reset() {
        for (UpdateTarget target : UpdateTarget.values()) {
            boolean busy = getState(target).phase.isBusy();
            if (target.isUpdatable() && !busy) publish(target, UpdateState.IDLE);
            if (target.versionedApkPrefix != null && !busy) {
                wipe(new File(context.getCacheDir(), ADDON_CHECK_DIR_PREFIX + target.id));
            }
        }
        refreshInstalled();
    }

    private void runUpdate(UpdateTarget target, UpdateChecker.Offer offer) {
        File dir = offer.stagedApk == null ? new File(context.getCacheDir(), UPDATE_DIR) : offer.stagedApk.getParentFile();
        File file = offer.stagedApk == null ? new File(dir, UPDATE_FILE) : offer.stagedApk;
        try {
            UpdateFailure failure = download(target, offer, dir, file);
            if (failure == null) failure = verify(target, offer, file);
            if (failure == null) failure = install(target, offer, file);
            if (failure != null) publish(target, UpdateState.failed(offer, failure, null));
        } catch (UpdateNetwork.InsufficientStorageException e) {
            publish(target, UpdateState.failed(offer, UpdateFailure.INSUFFICIENT_STORAGE, null));
        } catch (IOException e) {
            publish(target, cancelRequested.get() || Thread.currentThread().isInterrupted()
                    ? UpdateState.failed(offer, UpdateFailure.CANCELLED, null)
                    : UpdateState.failed(offer, UpdateFailure.DOWNLOAD_FAILED, e.getMessage()));
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Update failed", e);
            publish(target, UpdateState.failed(offer, UpdateFailure.INSTALL_FAILED, e.getMessage()));
        } finally {
            wipe(dir);
        }
    }

    /** Gates 1 to 3 and the transfer; returns null on success. */
    @Nullable
    private UpdateFailure download(UpdateTarget target, UpdateChecker.Offer offer, File dir, File file) throws IOException {
        String tag = offer.releaseTag == null ? UpdateTarget.LATEST_TAG : offer.releaseTag;
        String apkUrl = UpdateTrust.assetUrl(target, tag, offer.apk.name);
        String sumsUrl = UpdateTrust.assetUrl(target, tag, offer.checksums.name);
        if (!UpdateTrust.isTrustedAssetUrl(target, tag, apkUrl) || !UpdateTrust.isTrustedAssetUrl(target, tag, sumsUrl)) {
            return UpdateFailure.NOT_TRUSTED;
        }
        if (ShizukuInstaller.state(context) != ShizukuState.READY) return UpdateFailure.SHIZUKU_NOT_READY;
        if (offer.stagedApk != null && file.isFile() && file.length() == offer.apk.size) return null;

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
            publish(target, UpdateState.progress(offer, done, reported >= 0 ? reported : total));
        });
        return null;
    }

    /** Checksum, archive identity and version guard; returns null when the file may be installed. */
    @Nullable
    private UpdateFailure verify(UpdateTarget target, UpdateChecker.Offer offer, File file) throws IOException {
        if (Thread.currentThread().isInterrupted()) return UpdateFailure.CANCELLED;
        publish(target, UpdateState.of(UpdateState.Phase.VERIFYING, offer));

        String tag = offer.releaseTag == null ? UpdateTarget.LATEST_TAG : offer.releaseTag;
        String sums = network.fetchAssetText(UpdateTrust.assetUrl(target, tag, offer.checksums.name), MAX_CHECKSUMS_BYTES);
        Map<String, String> table = UpdateTrust.parseSha256Sums(sums);
        String expected = table.get(offer.apk.name);
        if (expected == null) return UpdateFailure.CHECKSUM_UNAVAILABLE;
        if (offer.apk.size > 0 && file.length() != offer.apk.size) return UpdateFailure.CHECKSUM_MISMATCH;
        if (!expected.equals(UpdateTrust.sha256Hex(file))) return UpdateFailure.CHECKSUM_MISMATCH;

        ApkFacts installed = ApkInspector.installed(context, target);
        ApkFacts core = ApkInspector.installed(context, UpdateTarget.CORE);
        ApkFacts archive = ApkInspector.archive(context, file);
        if (core == null || archive == null) return UpdateFailure.UNREADABLE_APK;
        return ApkFacts.checkInstallable(target, installed, archive, core);
    }

    @Nullable
    private UpdateFailure install(UpdateTarget target, UpdateChecker.Offer offer, File file) throws Exception {
        if (Thread.currentThread().isInterrupted() || cancelRequested.get()) return UpdateFailure.CANCELLED;
        // Re-check just before the privileged call: Shizuku may have stopped during the download.
        if (ShizukuInstaller.state(context) != ShizukuState.READY) return UpdateFailure.SHIZUKU_NOT_READY;
        publish(target, UpdateState.of(UpdateState.Phase.INSTALLING, offer));
        installing = target;
        try {
            ShizukuInstaller.install(context, target, file, statusSender());
        } catch (Exception e) {
            installing = null;
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

    /**
     * The verdict from {@link InstallStatusReceiver}. On success this process is normally replaced
     * first: installing any of the three apps ends the processes of all of them.
     */
    void onInstallStatus(@NonNull Intent intent) {
        UpdateTarget target = installing;
        if (target == null) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        UpdateChecker.Offer offer = getState(target).offer;
        installing = null;
        switch (status) {
            case PackageInstaller.STATUS_SUCCESS:
                publish(target, UpdateState.of(UpdateState.Phase.IDLE, null));
                break;
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                // The privileged session should not need a confirmation. If it asks, refuse: no
                // confirmation intent from the system is launched on the update's behalf.
                publish(target, UpdateState.failed(offer, UpdateFailure.INSTALL_FAILED, "installer asked for confirmation"));
                break;
            default:
                publish(target, UpdateState.failed(offer, UpdateFailure.INSTALL_FAILED, message));
                break;
        }
    }

    private void publish(UpdateTarget target, UpdateState next) {
        states.put(target, next);
        mainHandler.post(() -> {
            // Deliver the newest state; a burst of progress posts collapses to the last value.
            UpdateState latest = getState(target);
            for (Listener listener : new ArrayList<>(listeners)) listener.onUpdateStateChanged(target, latest);
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
