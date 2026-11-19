package com.termux.app.update;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;

import com.termux.app.PermissionStatePublisher;

import dev.pschmitt.termux.shared.logger.Logger;
import rikka.shizuku.Shizuku;

/**
 * Shizuku setup from the command line ({@code termuxpp-setup-permissions}). Not exported: only the
 * app's own UID reaches it, which includes the guest's termux-am. Extra {@code command}:
 * {@code request} shows Shizuku's authorization dialog for TermuxPP, {@code open} opens the Shizuku
 * app (or its install page when it is missing) and {@code refresh} only republishes. Every command
 * republishes {@code permissions.json}, whose {@code shizuku} field holds the {@link ShizukuState}.
 */
public final class ShizukuCommandReceiver extends BroadcastReceiver {

    public static final String EXTRA_COMMAND = "command";
    private static final String LOG_TAG = "ShizukuCommandReceiver";
    private static final String INSTALL_PAGE = "https://shizuku.rikka.app/download/";

    private static boolean listening;

    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        String command = intent == null ? null : intent.getStringExtra(EXTRA_COMMAND);
        if ("request".equals(command)) {
            listen(app);
            ShizukuInstaller.requestPermission();
        } else if ("open".equals(command)) {
            open(app);
        } else if (!"refresh".equals(command)) {
            Logger.logWarn(LOG_TAG, "Unknown Shizuku command: " + command);
        }
        PermissionStatePublisher.publishNow(app);
    }

    /** Records the user's answer and republishes, so the guest sees it within a second. */
    private static void listen(@NonNull Context context) {
        if (listening || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        try {
            Shizuku.addRequestPermissionResultListener((requestCode, grantResult) -> {
                if (requestCode != ShizukuInstaller.REQUEST_CODE) return;
                ShizukuInstaller.onPermissionResult(grantResult);
                PermissionStatePublisher.publishNow(context);
            });
            listening = true;
        } catch (Throwable t) {
            Logger.logError(LOG_TAG, "Cannot listen for the Shizuku answer: " + t);
        }
    }

    private static void open(@NonNull Context context) {
        Intent intent = ShizukuInstaller.managerLaunchIntent(context);
        if (intent == null) intent = new Intent(Intent.ACTION_VIEW, Uri.parse(INSTALL_PAGE));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Logger.logError(LOG_TAG, "Cannot open Shizuku: " + e);
        }
    }
}
