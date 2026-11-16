package com.termux.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.os.CancellationSignal;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

/** Fingerprint-only authentication for an on-screen ADB shell approval. */
@SuppressWarnings("deprecation")
@RequiresApi(Build.VERSION_CODES.M)
final class AdbShellFingerprintApproval {
    interface Callback {
        void onApproved();
        void onRejected();
        void onRetry();
    }

    private final FingerprintManager fingerprintManager;
    private final Callback callback;
    private final CancellationSignal cancellation = new CancellationSignal();
    private boolean finished;

    private AdbShellFingerprintApproval(FingerprintManager fingerprintManager, Callback callback) {
        this.fingerprintManager = fingerprintManager;
        this.callback = callback;
    }

    @SuppressLint("MissingPermission")
    static AdbShellFingerprintApproval start(@NonNull Context context, @NonNull Callback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            context.checkSelfPermission(Manifest.permission.USE_FINGERPRINT) != PackageManager.PERMISSION_GRANTED) {
            callback.onRejected();
            return null;
        }
        FingerprintManager manager = context.getSystemService(FingerprintManager.class);
        if (manager == null || !manager.isHardwareDetected() || !manager.hasEnrolledFingerprints()) {
            callback.onRejected();
            return null;
        }
        AdbShellFingerprintApproval approval = new AdbShellFingerprintApproval(manager, callback);
        try {
            manager.authenticate(null, approval.cancellation, 0, managerCallback(approval), null);
            return approval;
        } catch (SecurityException | IllegalStateException exception) {
            approval.reject();
            return null;
        }
    }

    private static FingerprintManager.AuthenticationCallback managerCallback(AdbShellFingerprintApproval approval) {
        return new FingerprintManager.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(FingerprintManager.AuthenticationResult result) {
                if (approval.finished) return;
                approval.finished = true;
                approval.callback.onApproved();
            }

            @Override
            public void onAuthenticationFailed() {
                if (!approval.finished) approval.callback.onRetry();
            }

            @Override
            public void onAuthenticationError(int errorCode, CharSequence error) {
                approval.reject();
            }
        };
    }

    void cancel() {
        if (finished) return;
        finished = true;
        cancellation.cancel();
        callback.onRejected();
    }

    private void reject() {
        if (finished) return;
        finished = true;
        callback.onRejected();
    }
}
