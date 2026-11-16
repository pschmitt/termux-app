package com.termux.app;

import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import dev.pschmitt.termux.shared.interact.DialogUtils;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.WindowManager;

import java.security.cert.X509Certificate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Local-only UI for certificate enrollment and authorization management. */
public final class AdbShellApprovalActivity extends Activity {
    private static final String CHANNEL = "adb_shell_approval";
    private static final int NOTIFICATION = 8024;
    private static Pending pending;
    private AdbShellFingerprintApproval authentication;
    private Pending displayed;

    private static final class Pending {
        final String id = UUID.randomUUID().toString();
        final String fingerprint;
        final String name;
        final long expires = SystemClock.elapsedRealtime() + 120_000;
        final CountDownLatch done = new CountDownLatch(1);
        volatile boolean approved;

        Pending(String fingerprint, String name) {
            this.fingerprint = fingerprint;
            this.name = name;
        }
    }

    static boolean request(Context context, X509Certificate certificate, String name) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        Pending request = new Pending(AdbShellAuthorizations.fingerprint(certificate), name);
        synchronized (AdbShellApprovalActivity.class) {
            if (pending != null) return false;
            pending = request;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                    "ADB shell approvals", NotificationManager.IMPORTANCE_HIGH));
            }
            Intent intent = new Intent(context, AdbShellApprovalActivity.class)
                .putExtra("request", request.id);
            PendingIntent action = PendingIntent.getActivity(context, NOTIFICATION, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL) : new Notification.Builder(context);
            manager.notify(NOTIFICATION, builder.setSmallIcon(com.termux.R.drawable.ic_service_notification)
                .setContentTitle("Approve TermuxPP ADB client")
                .setContentText("Tap to review the certificate and authenticate with your fingerprint.")
                .setContentIntent(action).setAutoCancel(true).build());
            return request.done.await(120, TimeUnit.SECONDS) && request.approved &&
                SystemClock.elapsedRealtime() < request.expires;
        } finally {
            synchronized (AdbShellApprovalActivity.class) {
                if (pending == request) pending = null;
            }
            manager.cancel(NOTIFICATION);
        }
    }

    static synchronized void cancelPending() {
        if (pending != null) {
            pending.approved = false;
            pending.done.countDown();
            pending = null;
        }
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if ("manage".equals(getIntent().getAction())) {
            showAuthorizations();
            return;
        }
        synchronized (AdbShellApprovalActivity.class) {
            displayed = pending;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || displayed == null ||
            !displayed.id.equals(getIntent().getStringExtra("request")) ||
            SystemClock.elapsedRealtime() >= displayed.expires) {
            finish();
            return;
        }
        DialogUtils.builder(this, com.termux.R.drawable.ui_ic_terminal, false).setTitle("Authorize ADB shell client?")
            .setMessage(displayed.name + "\n\nSHA-256 certificate fingerprint:\n" + displayed.fingerprint +
                "\n\nCompare this fingerprint with the CLI. This grants shell access to your TermuxPP data.")
            .setPositiveButton("Verify fingerprint", (dialog, which) -> authenticate())
            .setNegativeButton(android.R.string.cancel, (dialog, which) -> finish())
            .setOnCancelListener(dialog -> finish()).show();
    }

    private void authenticate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            finish();
            return;
        }
        AlertDialog prompt = DialogUtils.builder(this, com.termux.R.drawable.ui_ic_terminal, false).setTitle("Touch the fingerprint sensor")
            .setMessage("Only an enrolled fingerprint can authorize this client. PIN and face unlock are not accepted.")
            .setNegativeButton(android.R.string.cancel, (dialog, which) -> finish())
            .setOnCancelListener(dialog -> finish()).create();
        prompt.show();
        authentication = AdbShellFingerprintApproval.start(this, new AdbShellFingerprintApproval.Callback() {
            @Override public void onApproved() {
                synchronized (AdbShellApprovalActivity.class) {
                    if (pending == displayed && SystemClock.elapsedRealtime() < displayed.expires) {
                        displayed.approved = true;
                        displayed.done.countDown();
                    }
                }
                prompt.dismiss();
                finish();
            }
            @Override public void onRejected() {
                prompt.dismiss();
                finish();
            }
            @Override public void onRetry() {
                prompt.setMessage("Fingerprint not recognized. Try again or cancel.");
            }
        });
    }

    private void showAuthorizations() {
        AdbShellAuthorizations authorizations = new AdbShellAuthorizations(this);
        java.util.List<AdbShellAuthorizations.Entry> entries = authorizations.list();
        String[] labels = new String[entries.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = entries.get(i).name + "\n" + entries.get(i).fingerprint;
        }
        MaterialAlertDialogBuilder dialog = DialogUtils.builder(this, com.termux.R.drawable.ui_ic_terminal, false).setTitle("ADB shell authorizations")
            .setNegativeButton(android.R.string.cancel, (d, which) -> finish())
            .setOnCancelListener(d -> finish());
        if (entries.isEmpty()) dialog.setMessage("No authorized clients.");
        else dialog.setItems(labels, (d, which) -> {
            AdbShellAuthorizations.Entry entry = entries.get(which);
            DialogUtils.builder(this, com.termux.R.drawable.ui_ic_terminal, false).setTitle("Revoke this client?")
                .setMessage(entry.name + "\n" + entry.fingerprint)
                .setPositiveButton("Revoke", (confirmation, button) -> {
                    if (authorizations.revoke(entry.fingerprint)) {
                        startService(new Intent(this, AdbShellService.class)
                            .setAction(AdbShellService.ACTION_AUTHORIZATIONS_CHANGED));
                    }
                    showAuthorizations();
                }).setNegativeButton(android.R.string.cancel, (confirmation, button) -> showAuthorizations())
                .setOnCancelListener(confirmation -> showAuthorizations()).show();
        });
        dialog.show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && authentication != null) authentication.cancel();
        if (displayed != null) {
            synchronized (AdbShellApprovalActivity.class) {
                if (!displayed.approved) displayed.done.countDown();
            }
            finish();
        }
    }
}
