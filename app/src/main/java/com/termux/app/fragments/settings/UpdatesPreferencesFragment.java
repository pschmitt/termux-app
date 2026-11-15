package com.termux.app.fragments.settings;

import android.app.Activity;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.termux.BuildConfig;
import com.termux.R;
import com.termux.app.update.ApkInspector;
import com.termux.app.update.InstalledBuild;
import com.termux.app.update.ShizukuInstaller;
import com.termux.app.update.ShizukuState;
import com.termux.app.update.UpdateChannel;
import com.termux.app.update.UpdateController;
import com.termux.app.update.UpdateFailure;
import com.termux.app.update.UpdateState;
import com.termux.app.update.UpdateTarget;
import com.termux.shared.interact.DialogUtils;
import com.termux.shared.logger.Logger;

import rikka.shizuku.Shizuku;

/**
 * Settings &gt; Updates: one screen for TermuxPP, Termux:API and Termux:Tasker. Everything here
 * happens on a tap: checking, the confirmation and each install. Opening the screen only reads local
 * state (what is installed), it never contacts the network.
 */
@Keep
public class UpdatesPreferencesFragment extends PreferenceFragmentCompat implements UpdateController.Listener {

    private static final String LOG_TAG = "UpdatesPreferencesFragment";
    private static final String KEY_APP_PREFIX = "update_app_";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Shizuku.OnBinderReceivedListener binderReceived = () -> mainHandler.post(this::refreshShizuku);
    private final Shizuku.OnBinderDeadListener binderDead = () -> mainHandler.post(this::refreshShizuku);
    private final Shizuku.OnRequestPermissionResultListener permissionResult = (requestCode, grantResult) -> {
        ShizukuInstaller.onPermissionResult(grantResult);
        mainHandler.post(this::refreshShizuku);
    };

    private UpdateController controller;
    @Nullable private SwipeRefreshLayout swipeRefreshLayout;
    private boolean shizukuListening;
    @Nullable private CharSequence previousTitle;
    /** Whether the icon currently set on each app row is the installed app's own, to set it only when that changes. */
    @Nullable private Boolean shizukuIconInstalled;
    private final Boolean[] appIconInstalled = new Boolean[UpdateTarget.values().length];

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        setPreferencesFromResource(R.xml.updates_preferences, rootKey);
        controller = UpdateController.getInstance(context);

        Preference check = findPreference("update_check");
        if (check != null) {
            check.setOnPreferenceClickListener(p -> {
                if (controller.isBusy()) controller.cancel();
                else controller.checkAll(selectedChannel());
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

        for (UpdateTarget target : UpdateTarget.values()) {
            Preference app = findPreference(KEY_APP_PREFIX + target.id);
            if (app == null) continue;
            app.setEnabled(target.isUpdatable());
            app.setOnPreferenceClickListener(p -> {
                if (target.isUpdatable()) onAppClicked(target);
                return true;
            });
        }
    }

    @Override
    public void onViewCreated(@NonNull android.view.View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Context context = getContext();
        if (context == null || !(view.getParent() instanceof android.view.ViewGroup)) return;

        SwipeRefreshLayout refresh = new SwipeRefreshLayout(context);
        refresh.setColorSchemeColors(ContextCompat.getColor(context, R.color.ui_primary));
        refresh.setOnRefreshListener(() -> {
            if (controller == null || controller.isBusy()) {
                refresh.setRefreshing(false);
                return;
            }
            controller.checkAll(selectedChannel());
            renderAll();
        });
        android.view.ViewGroup parent = (android.view.ViewGroup) view.getParent();
        int index = parent.indexOfChild(view);
        android.view.ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        parent.removeView(view);
        parent.addView(refresh, index, layoutParams);
        refresh.addView(view);
        android.view.View list = getListView();
        refresh.setOnChildScrollUpCallback((layout, child) -> list.canScrollVertically(-1));
        swipeRefreshLayout = refresh;
    }

    @Override
    public void onStart() {
        super.onStart();
        Activity activity = getActivity();
        if (activity != null) {
            previousTitle = activity.getTitle();
            activity.setTitle(R.string.termux_updates_title);
        }
        java.util.Arrays.fill(appIconInstalled, null);
        shizukuIconInstalled = null;
        controller.addListener(this);
        try {
            Shizuku.addBinderReceivedListener(binderReceived);
            Shizuku.addBinderDeadListener(binderDead);
            Shizuku.addRequestPermissionResultListener(permissionResult);
            shizukuListening = true;
        } catch (Throwable t) {
            Logger.logError(LOG_TAG, "Could not listen to Shizuku: " + t);
        }
        controller.refreshInstalled();
        refreshShizuku();
    }

    @Override
    public void onDestroyView() {
        SwipeRefreshLayout refresh = swipeRefreshLayout;
        if (refresh != null) {
            refresh.setOnRefreshListener(null);
            refresh.setOnChildScrollUpCallback(null);
            if (refresh.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) refresh.getParent()).removeView(refresh);
            }
            swipeRefreshLayout = null;
        }
        super.onDestroyView();
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
        Activity activity = getActivity();
        if (activity != null && previousTitle != null) activity.setTitle(previousTitle);
        super.onStop();
    }

    @Override
    public void onUpdateStateChanged(@NonNull UpdateTarget target, @NonNull UpdateState state) {
        renderAll();
    }

    @NonNull
    private UpdateChannel selectedChannel() {
        ListPreference channel = findPreference("update_channel");
        return UpdateChannel.fromValue(channel == null ? null : channel.getValue());
    }

    private void onAppClicked(@NonNull UpdateTarget target) {
        Context context = getContext();
        UpdateState state = controller.getState(target);
        if (context == null || controller.isBusy()) return;
        if (state.phase == UpdateState.Phase.NOT_INSTALLED) {
            controller.checkOne(target, selectedChannel());
            return;
        }
        if (state.phase != UpdateState.Phase.AVAILABLE || state.offer == null) return;
        if (ShizukuInstaller.state(context) != ShizukuState.READY) {
            Toast.makeText(context, R.string.termux_update_app_shizuku_needed, Toast.LENGTH_LONG).show();
            return;
        }
        String name = getString(appTitle(target));
        boolean firstInstall = !ApkInspector.isInstalled(context, target);
        DialogUtils.builder(context, R.drawable.ui_ic_system_update, false)
                .setTitle(getString(firstInstall ? R.string.termux_update_install_confirm_title
                        : R.string.termux_update_confirm_title, name))
                .setMessage(getString(R.string.termux_update_confirm_message, name, state.offer.shortCommit()))
                .setPositiveButton(firstInstall ? R.string.termux_update_install_confirm_positive
                        : R.string.termux_update_confirm_positive,
                        (dialog, which) -> controller.startUpdate(target))
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
        updateShizukuIcon(context, shizuku);
        renderAll();
    }

    private static int shizukuSummary(ShizukuState state) {
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

    private void renderAll() {
        Context context = getContext();
        if (context == null || controller == null) return;

        boolean busy = controller.isBusy();
        Preference check = findPreference("update_check");
        if (check != null) {
            check.setTitle(busy ? R.string.termux_update_cancel : R.string.termux_update_check);
            check.setEnabled(!busy || controller.isCancellable());
        }
        SwipeRefreshLayout refresh = swipeRefreshLayout;
        if (refresh != null) {
            boolean refreshing = false;
            for (UpdateTarget target : UpdateTarget.values()) {
                if (controller.getState(target).phase == UpdateState.Phase.CHECKING) {
                    refreshing = true;
                    break;
                }
            }
            refresh.setEnabled(!busy || controller.isCancellable());
            refresh.setRefreshing(refreshing);
            refresh.setContentDescription(getString(busy
                    ? R.string.termux_update_status_checking : R.string.termux_update_check));
        }
        ListPreference channel = findPreference("update_channel");
        if (channel != null) channel.setEnabled(!busy);

        for (UpdateTarget target : UpdateTarget.values()) {
            Preference app = findPreference(KEY_APP_PREFIX + target.id);
            if (app == null) continue;
            if (!target.hasReleaseSource()) {
                app.setSummary(R.string.termux_update_addon_unavailable);
                app.setEnabled(false);
                updateAppIcon(context, app, target, isInstalled(context, target.packageName));
                continue;
            }
            UpdateState state = controller.getState(target);
            app.setSummary(appSummary(context, target, state));
            app.setEnabled(target.isUpdatable());
            updateAppIcon(context, app, target, state.phase != UpdateState.Phase.NOT_INSTALLED);
            if (app instanceof UpdateAppPreference) ((UpdateAppPreference) app).setUpdateState(state);
        }
    }

    /**
     * The row shows the application's own launcher icon (untinted), and a bundled stand-in with the same
     * identity while that application is not installed.
     */
    private void updateAppIcon(@NonNull Context context, @NonNull Preference app, @NonNull UpdateTarget target,
                               boolean installed) {
        if (appIconInstalled[target.ordinal()] != null && appIconInstalled[target.ordinal()] == installed) return;
        appIconInstalled[target.ordinal()] = installed;
        app.setIcon(appIcon(context, target, installed));
    }

    /** The Shizuku row shows the Shizuku manager's own icon when it is installed. */
    private void updateShizukuIcon(@NonNull Context context, @NonNull Preference shizuku) {
        boolean installed = isInstalled(context, ShizukuInstaller.MANAGER_PACKAGE);
        if (shizukuIconInstalled != null && shizukuIconInstalled == installed) return;
        shizukuIconInstalled = installed;
        shizuku.setIcon(installedIconOrStandIn(context, ShizukuInstaller.MANAGER_PACKAGE, installed,
            R.drawable.ui_ic_power));
    }

    private static boolean isInstalled(@NonNull Context context, @NonNull String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

    @NonNull
    static Drawable appIcon(@NonNull Context context, @NonNull UpdateTarget target, boolean installed) {
        return installedIconOrStandIn(context, target.packageName, installed, fallbackGlyph(target));
    }

    @NonNull
    static Drawable installedIconOrStandIn(@NonNull Context context, @NonNull String packageName,
                                           boolean installed, @DrawableRes int glyph) {
        if (installed) {
            try {
                Drawable icon = context.getPackageManager().getApplicationIcon(packageName);
                if (icon != null) return icon;
            } catch (PackageManager.NameNotFoundException ignored) {
                // Fall through to the stand-in.
            }
        }
        return standInIcon(context, glyph);
    }

    @NonNull
    private static Drawable standInIcon(@NonNull Context context, @DrawableRes int glyphRes) {
        Drawable container = ContextCompat.getDrawable(context, R.drawable.ui_icon_container);
        Drawable glyph = ContextCompat.getDrawable(context, glyphRes);
        if (container == null || glyph == null) throw new IllegalStateException("Missing bundled icon");
        glyph = DrawableCompat.wrap(glyph.mutate());
        DrawableCompat.setTint(glyph, ContextCompat.getColor(context, R.color.ui_on_container));
        LayerDrawable layers = new LayerDrawable(new Drawable[]{container.mutate(), glyph});
        int inset = Math.round(8 * context.getResources().getDisplayMetrics().density);
        layers.setLayerInset(1, inset, inset, inset, inset);
        // Dimmed: the app is missing.
        layers.setAlpha(128);
        return layers;
    }

    @DrawableRes
    private static int fallbackGlyph(@NonNull UpdateTarget target) {
        switch (target) {
            case API: return R.drawable.ui_ic_extension;
            case TASKER: return R.drawable.ui_ic_bolt;
            case FLOAT: return R.drawable.ui_ic_float;
            case BOOT: return R.drawable.ui_ic_power;
            case STYLING: return R.drawable.ui_ic_palette;
            case WIDGET: return R.drawable.ui_ic_extension;
            case CORE:
            default: return R.drawable.ui_ic_terminal;
        }
    }

    @StringRes
    private static int appTitle(@NonNull UpdateTarget target) {
        switch (target) {
            case API: return R.string.termux_update_app_api;
            case TASKER: return R.string.termux_update_app_tasker;
            case FLOAT: return R.string.termux_update_app_float;
            case BOOT: return R.string.termux_update_app_boot;
            case STYLING: return R.string.termux_update_app_styling;
            case WIDGET: return R.string.termux_update_app_widget;
            case CORE:
            default: return R.string.termux_update_app_core;
        }
    }

    @NonNull
    private String appSummary(@NonNull Context context, @NonNull UpdateTarget target, @NonNull UpdateState state) {
        if (state.phase == UpdateState.Phase.NOT_INSTALLED) {
            return getString(target.versionedApkPrefix == null ? R.string.termux_update_app_not_installed
                    : R.string.termux_update_addon_not_installed);
        }

        String installed = installedSummary(context, target);
        switch (state.phase) {
            case IDLE:
                return installed;
            case AVAILABLE:
                boolean ready = ShizukuInstaller.state(context) == ShizukuState.READY;
                boolean isInstalled = isInstalled(context, target.packageName);
                return installed + "\n" + statusText(context, target, state) + " "
                        + getString(ready ? (isInstalled ? R.string.termux_update_app_tap_to_update
                                : R.string.termux_update_app_tap_to_install)
                                : R.string.termux_update_app_shizuku_needed);
            default:
                return installed + "\n" + statusText(context, target, state);
        }
    }

    @NonNull
    private String installedSummary(@NonNull Context context, @NonNull UpdateTarget target) {
        if (target.versionedApkPrefix != null) {
            try {
                String versionName = context.getPackageManager().getPackageInfo(target.packageName, 0).versionName;
                return versionName == null || versionName.isEmpty()
                        ? getString(R.string.termux_update_addon_installed_unknown)
                        : getString(R.string.termux_update_addon_installed, versionName);
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                return getString(R.string.termux_update_addon_installed_unknown);
            }
        }
        InstalledBuild build = target == UpdateTarget.CORE
                ? InstalledBuild.parse(BuildConfig.GIT_REVISION)
                : ApkInspector.declaredBuild(context, target);
        if (!build.isComparable() || build.commit == null) {
            return getString(R.string.termux_update_app_installed_unknown);
        }
        String shortCommit = build.commit.length() > 7 ? build.commit.substring(0, 7) : build.commit;
        return getString(R.string.termux_update_app_installed, shortCommit);
    }

    @NonNull
    private String statusText(@NonNull Context context, @NonNull UpdateTarget target, @NonNull UpdateState state) {
        switch (state.phase) {
            case CHECKING:
                return getString(R.string.termux_update_status_checking);
            case UP_TO_DATE:
                return getString(R.string.termux_update_status_up_to_date);
            case UNAVAILABLE:
                return getString(target.versionedApkPrefix != null
                        ? R.string.termux_update_addon_unavailable_status
                        : R.string.termux_update_status_unavailable);
            case AVAILABLE:
                return state.offer == null ? "" : getString(R.string.termux_update_status_available,
                        state.offer.shortCommit(), Formatter.formatShortFileSize(context, state.offer.apk.size));
            case NOT_ELIGIBLE:
                return getString(target.versionedApkPrefix != null
                        ? R.string.termux_update_addon_update_unavailable
                        : R.string.termux_update_status_not_eligible);
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
            case SHARED_USER_MISMATCH: return R.string.termux_update_failure_shared_user_mismatch;
            case SIGNATURE_MISMATCH: return R.string.termux_update_failure_signature_mismatch;
            case DOWNGRADE: return R.string.termux_update_failure_downgrade;
            case CANCELLED: return R.string.termux_update_failure_cancelled;
            case INSTALL_FAILED:
            default: return R.string.termux_update_failure_install_failed;
        }
    }

}
