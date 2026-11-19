package com.termux.app.update;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import dev.pschmitt.termux.shared.logger.Logger;
import dev.pschmitt.termux.shared.termux.nix.TermuxNix;

/**
 * Self-update from the command line ({@code termuxpp-self-update} in the guest), the same updater
 * as Settings > About. Not exported: only the app's own UID reaches it, which includes the
 * guest's termux-am. Extra {@code command}: {@code check}, {@code update} (check first when no
 * offer is known, then install) or {@code cancel}.
 *
 * <p>The state of the TermuxPP target goes to {@code update-status.json} in the nixpp runtime
 * directory on every change; the app also records its installed version there on start, so the
 * CLI can tell that an update took effect after the app restarted.
 */
public final class UpdateCommandReceiver extends BroadcastReceiver {

    public static final String EXTRA_COMMAND = "command";
    private static final String LOG_TAG = "UpdateCommandReceiver";
    private static final String STATUS_FILE = "update-status.json";

    private static boolean listening;
    /** Install as soon as a check finds an update; set by the update command. */
    private static boolean installWhenAvailable;

    @Override
    public void onReceive(Context context, Intent intent) {
        String command = intent == null ? null : intent.getStringExtra(EXTRA_COMMAND);
        Context app = context.getApplicationContext();
        UpdateController controller = UpdateController.getInstance(app);
        listen(app, controller);
        UpdateTarget target = UpdateTarget.CORE;
        if ("check".equals(command)) {
            installWhenAvailable = false;
            controller.checkTarget(target, UpdateChannel.LATEST);
        } else if ("update".equals(command)) {
            if (controller.getState(target).phase == UpdateState.Phase.AVAILABLE) {
                installWhenAvailable = false;
                controller.startUpdate(target);
            } else {
                installWhenAvailable = true;
                controller.checkTarget(target, UpdateChannel.LATEST);
            }
        } else if ("cancel".equals(command)) {
            installWhenAvailable = false;
            controller.cancel();
        } else {
            Logger.logWarn(LOG_TAG, "Unknown update command: " + command);
        }
        write(app, controller.getState(target), command);
    }

    @MainThread
    private static void listen(@NonNull Context context, @NonNull UpdateController controller) {
        if (listening) return;
        listening = true;
        controller.addListener((target, state) -> {
            if (target != UpdateTarget.CORE) return;
            write(context, state, null);
            if (installWhenAvailable && state.phase == UpdateState.Phase.AVAILABLE) {
                installWhenAvailable = false;
                controller.startUpdate(target);
            } else if (installWhenAvailable && !state.phase.isBusy()) {
                installWhenAvailable = false;
            }
        });
    }

    /** Record the installed version with an idle state, on app start. */
    public static void publishInstalled(@NonNull Context context) {
        write(context.getApplicationContext(), UpdateState.IDLE, null);
    }

    private static void write(@NonNull Context context, @NonNull UpdateState state, @Nullable String command) {
        File runtime = new File(TermuxNix.RUNTIME_DIR_PATH);
        if (!runtime.isDirectory()) return;
        File target = new File(runtime, STATUS_FILE);
        File temporary = new File(runtime, "." + STATUS_FILE + ".tmp");
        try {
            JSONObject status = new JSONObject();
            status.put("updated", System.currentTimeMillis() / 1000);
            putInstalled(context, status);
            status.put("phase", state.phase.name());
            if (command != null) status.put("command", command);
            if (state.offer != null) {
                status.put("offer", state.offer.commit);
                if (state.offer.releaseTag != null) status.put("releaseTag", state.offer.releaseTag);
                status.put("size", state.offer.apk.size);
            }
            if (state.phase == UpdateState.Phase.DOWNLOADING) {
                status.put("done", state.done);
                status.put("total", state.total);
            }
            if (state.failure != null) status.put("failure", state.failure.name());
            if (state.detail != null) status.put("detail", state.detail);
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write((status.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!temporary.renameTo(target)) throw new IOException("rename failed");
        } catch (IOException | JSONException e) {
            Logger.logWarn(LOG_TAG, "Cannot write the update status: " + e.getMessage());
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
        }
    }

    /**
     * The installed build: version name plus commit, and the install time, which changes with
     * every install even when the version name does not.
     */
    private static void putInstalled(@NonNull Context context, @NonNull JSONObject status) throws JSONException {
        String version = "";
        long installedAt = 0;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (info.versionName != null) version = info.versionName;
            installedAt = info.lastUpdateTime / 1000;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        // The identity the updater compares (UpdateController.performCheck).
        InstalledBuild build = InstalledBuild.parse(com.termux.BuildConfig.GIT_REVISION);
        if (build.isComparable()) {
            String commit = build.commit;
            version += " (" + commit.substring(0, Math.min(12, commit.length())) + ")";
            status.put("commit", commit);
        }
        status.put("installed", version.trim());
        status.put("installedAt", installedAt);
    }
}
