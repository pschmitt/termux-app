package com.termux.app.fragments.settings.termux;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.termux.BuildConfig;
import com.termux.R;
import com.termux.app.update.InstalledBuild;
import com.termux.app.update.ShizukuInstaller;
import com.termux.app.update.ShizukuState;
import com.termux.app.update.UpdateChannel;
import com.termux.app.update.UpdateController;
import com.termux.app.update.UpdateFailure;
import com.termux.app.update.UpdateState;
import com.termux.shared.logger.Logger;

import rikka.shizuku.Shizuku;

/**
 * Settings &gt; Update. Everything here happens on a tap: checking, the confirmation and the
 * install. Opening the screen only reads local state.
 */
@Keep
public class UpdatePreferencesFragment extends PreferenceFragmentCompat implements UpdateController.Listener {

    private static final String LOG_TAG = "UpdatePreferencesFragment";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Shizuku.OnBinderReceivedListener binderReceived = () -> mainHandler.post(this::refreshShizuku);
    private final Shizuku.OnBinderDeadListener binderDead = () -> mainHandler.post(this::refreshShizuku);
    private final Shizuku.OnRequestPermissionResultListener permissionResult = (requestCode, grantResult) -> {
        ShizukuInstaller.onPermissionResult(grantResult);
        mainHandler.post(this::refreshShizuku);
    };

    private UpdateController controller;
    private boolean shizukuListening;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        setPreferencesFromResource(R.xml.termux_update_preferences, rootKey);
        controller = UpdateController.getInstance(context);

        Preference current = findPreference("update_current");
        if (current != null) current.setSummary(currentBuildSummary(context));

        Preference check = findPreference("update_check");
        if (check != null) {
            check.setOnPreferenceClickListener(p -> {
                if (controller.getState().phase.isBusy()) controller.cancel();
                else controller.check(selectedChannel());
                return true;
            });
        }

        Preference install = findPreference("update_install");
        if (install != null) {
            install.setOnPreferenceClickListener(p -> {
                confirmInstall();
                return true;
            });
        }

        Preference shizuku = findPreference("update_shizuku");
        if (shizuku != null) {
            shizuku.setOnPreferenceClickListener(p -> {
                onShizukuClicked();
                return true;
            });
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        controller.addListener(this);
        try {
            Shizuku.addBinderReceivedListener(binderReceived);
            Shizuku.addBinderDeadListener(binderDead);
            Shizuku.addRequestPermissionResultListener(permissionResult);
            shizukuListening = true;
        } catch (Throwable t) {
            Logger.logError(LOG_TAG, "Could not listen to Shizuku: " + t);
        }
        refreshShizuku();
        render(controller.getState());
    }

    @Override
    public void onStop() {
        controller.removeListener(this);
        if (shizukuListening) {
            try {
                Shizuku.removeBinderReceivedListener(binderReceived);
                Shizuku.removeBinderDeadListener(binderDead);
                Shizuku.removeRequestPermissionResultListener(permissionResult);
            } catch (Throwable t) {
                Logger.logError(LOG_TAG, "Could not stop listening to Shizuku: " + t);
            }
            shizukuListening = false;
        }
        super.onStop();
    }

    @Override
    public void onUpdateStateChanged(@NonNull UpdateState state) {
        render(state);
    }

    @NonNull
    private UpdateChannel selectedChannel() {
        ListPreference channel = findPreference("update_channel");
        return UpdateChannel.fromValue(channel == null ? null : channel.getValue());
    }

    private void confirmInstall() {
        Context context = getContext();
        UpdateState state = controller.getState();
        if (context == null || state.phase != UpdateState.Phase.AVAILABLE || state.offer == null) return;
        new AlertDialog.Builder(context)
                .setTitle(R.string.termux_update_confirm_title)
                .setMessage(getString(R.string.termux_update_confirm_message, state.offer.shortCommit()))
                .setPositiveButton(R.string.termux_update_confirm_positive, (dialog, which) -> controller.startUpdate())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void onShizukuClicked() {
        Context context = getContext();
        if (context == null) return;
        switch (ShizukuInstaller.state(context)) {
            case PERMISSION_NEEDED:
                ShizukuInstaller.requestPermission();
                break;
            case READY:
            case UNAVAILABLE:
                break;
            default:
                Intent intent = ShizukuInstaller.managerLaunchIntent(context);
                if (intent == null) break;
                try {
                    startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    Logger.logError(LOG_TAG, "Could not open Shizuku: " + e);
                }
                break;
        }
    }

    private void refreshShizuku() {
        Context context = getContext();
        Preference shizuku = findPreference("update_shizuku");
        if (context == null || shizuku == null) return;
        shizuku.setSummary(shizukuSummary(ShizukuInstaller.state(context)));
        render(controller.getState());
    }

    private int shizukuSummary(ShizukuState state) {
        switch (state) {
            case UNSUPPORTED: return R.string.termux_update_shizuku_unsupported;
            case NOT_RUNNING: return R.string.termux_update_shizuku_not_running;
            case PERMISSION_NEEDED: return R.string.termux_update_shizuku_permission_needed;
            case PERMISSION_DENIED: return R.string.termux_update_shizuku_permission_denied;
            case READY: return R.string.termux_update_shizuku_ready;
            case UNAVAILABLE:
            default: return R.string.termux_update_shizuku_unavailable;
        }
    }

    private void render(@NonNull UpdateState state) {
        Context context = getContext();
        if (context == null) return;

        Preference status = findPreference("update_status");
        if (status != null) status.setSummary(statusText(context, state));

        Preference check = findPreference("update_check");
        if (check != null) {
            boolean busy = state.phase.isBusy();
            boolean cancellable = state.phase == UpdateState.Phase.CHECKING
                    || state.phase == UpdateState.Phase.DOWNLOADING || state.phase == UpdateState.Phase.VERIFYING;
            check.setTitle(busy ? R.string.termux_update_cancel : R.string.termux_update_check);
            check.setEnabled(!busy || cancellable);
        }

        Preference install = findPreference("update_install");
        if (install != null) {
            install.setEnabled(state.phase == UpdateState.Phase.AVAILABLE
                    && ShizukuInstaller.state(context) == ShizukuState.READY);
        }

        ListPreference channel = findPreference("update_channel");
        if (channel != null) channel.setEnabled(!state.phase.isBusy());
    }

    @NonNull
    private String statusText(@NonNull Context context, @NonNull UpdateState state) {
        switch (state.phase) {
            case CHECKING:
                return getString(R.string.termux_update_status_checking);
            case UP_TO_DATE:
                return getString(R.string.termux_update_status_up_to_date);
            case AVAILABLE:
                return state.offer == null ? "" : getString(R.string.termux_update_status_available,
                        state.offer.shortCommit(), Formatter.formatShortFileSize(context, state.offer.apk.size));
            case NOT_ELIGIBLE:
                return getString(R.string.termux_update_status_not_eligible);
            case CHECK_FAILED:
                return getString(R.string.termux_update_status_check_failed, state.detail == null ? "?" : state.detail);
            case DOWNLOADING:
                return state.total > 0
                        ? getString(R.string.termux_update_status_downloading,
                                Formatter.formatShortFileSize(context, state.done),
                                Formatter.formatShortFileSize(context, state.total))
                        : getString(R.string.termux_update_status_downloading_unknown,
                                Formatter.formatShortFileSize(context, state.done));
            case VERIFYING:
                return getString(R.string.termux_update_status_verifying);
            case INSTALLING:
                return getString(R.string.termux_update_status_installing);
            case FAILED:
                return failureText(state);
            case IDLE:
            default:
                return getString(R.string.termux_update_status_idle);
        }
    }

    @NonNull
    private String failureText(@NonNull UpdateState state) {
        String reason = getString(failureString(state.failure));
        if (state.failure == UpdateFailure.CANCELLED) return reason;
        String text = state.detail == null ? reason
                : getString(R.string.termux_update_status_failed_detail, reason, state.detail);
        return getString(R.string.termux_update_status_failed, text);
    }

    private static int failureString(@Nullable UpdateFailure failure) {
        if (failure == null) return R.string.termux_update_failure_install_failed;
        switch (failure) {
            case NO_APK: return R.string.termux_update_failure_no_apk;
            case NOT_TRUSTED: return R.string.termux_update_failure_not_trusted;
            case SHIZUKU_NOT_READY: return R.string.termux_update_failure_shizuku_not_ready;
            case DOWNLOAD_FAILED: return R.string.termux_update_failure_download_failed;
            case INSUFFICIENT_STORAGE: return R.string.termux_update_failure_insufficient_storage;
            case CHECKSUM_UNAVAILABLE: return R.string.termux_update_failure_checksum_unavailable;
            case CHECKSUM_MISMATCH: return R.string.termux_update_failure_checksum_mismatch;
            case UNREADABLE_APK: return R.string.termux_update_failure_unreadable_apk;
            case WRONG_PACKAGE: return R.string.termux_update_failure_wrong_package;
            case SIGNATURE_MISMATCH: return R.string.termux_update_failure_signature_mismatch;
            case DOWNGRADE: return R.string.termux_update_failure_downgrade;
            case CANCELLED: return R.string.termux_update_failure_cancelled;
            case INSTALL_FAILED:
            default: return R.string.termux_update_failure_install_failed;
        }
    }

    @NonNull
    private String currentBuildSummary(@NonNull Context context) {
        InstalledBuild build = InstalledBuild.parse(BuildConfig.GIT_REVISION);
        if (!build.isComparable() || build.commit == null) {
            return context.getString(R.string.termux_update_current_unknown, BuildConfig.TERMUX_PACKAGE_VARIANT);
        }
        String shortCommit = build.commit.length() > 7 ? build.commit.substring(0, 7) : build.commit;
        return context.getString(R.string.termux_update_current_summary, shortCommit, BuildConfig.TERMUX_PACKAGE_VARIANT);
    }

}
