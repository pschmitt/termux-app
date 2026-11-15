package com.termux.app.fragments.settings;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.termux.R;
import com.termux.app.terminal.StylingApp;
import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.settings.preferences.TermuxAPIAppSharedPreferences;
import dev.pschmitt.termux.shared.termux.settings.preferences.TermuxFloatAppSharedPreferences;
import dev.pschmitt.termux.shared.termux.settings.preferences.TermuxTaskerAppSharedPreferences;
import dev.pschmitt.termux.shared.termux.settings.preferences.TermuxWidgetAppSharedPreferences;
import dev.pschmitt.termux.shared.interact.DialogUtils;

@Keep
public class CompanionAppsPreferencesFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        setPreferencesFromResource(R.xml.companion_apps_preferences, rootKey);
        configurePreference("termux_api", TermuxConstants.TERMUX_API_PACKAGE_NAME,
            TermuxAPIAppSharedPreferences.build(context, false) != null, R.drawable.ui_ic_extension);
        configurePreference("termux_float", TermuxConstants.TERMUX_FLOAT_PACKAGE_NAME,
            TermuxFloatAppSharedPreferences.build(context, false) != null, R.drawable.ui_ic_float);
        configurePreference("termux_tasker", TermuxConstants.TERMUX_TASKER_PACKAGE_NAME,
            TermuxTaskerAppSharedPreferences.build(context, false) != null, R.drawable.ui_ic_bolt);
        configurePreference("termux_widget", TermuxConstants.TERMUX_WIDGET_PACKAGE_NAME,
            TermuxWidgetAppSharedPreferences.build(context, false) != null, R.drawable.ui_ic_widgets);
        boolean bootAvailable = isActivityAvailable(context.getPackageManager(),
            TermuxConstants.TERMUX_BOOT_PACKAGE_NAME, TermuxConstants.TERMUX_BOOT_APP.TERMUX_BOOT_LAUNCHER_ACTIVITY_NAME);
        configurePreference("termux_boot", TermuxConstants.TERMUX_BOOT_PACKAGE_NAME, bootAvailable, R.drawable.ui_ic_power);
        Preference boot = findPreference("termux_boot");
        if (boot != null) {
            boot.setOnPreferenceClickListener(preference -> {
                if (bootAvailable) {
                    Intent intent = new Intent().setClassName(TermuxConstants.TERMUX_BOOT_PACKAGE_NAME,
                        TermuxConstants.TERMUX_BOOT_APP.TERMUX_BOOT_LAUNCHER_ACTIVITY_NAME);
                    try {
                        startActivity(intent);
                    } catch (RuntimeException e) {
                        showCompanionUnavailable(context);
                    }
                } else {
                    showCompanionUnavailable(context);
                }
                return true;
            });
        }

        Preference styling = findPreference("termux_styling");
        if (styling != null) {
            boolean stylingAvailable = StylingApp.isAvailable(context.getPackageManager());
            styling.setIcon(companionIcon(context, TermuxConstants.TERMUX_STYLING_PACKAGE_NAME,
                stylingAvailable, R.drawable.ui_ic_palette));
            String stylingVersion = stylingAvailable ? getInstalledVersion(TermuxConstants.TERMUX_STYLING_PACKAGE_NAME) : null;
            styling.setSummary(!stylingAvailable ? getString(R.string.termux_styling_unavailable_summary)
                : stylingVersion == null ? getString(R.string.termux_companion_installed_unknown_summary)
                : getString(R.string.termux_companion_installed_summary, stylingVersion));
            styling.setOnPreferenceClickListener(preference -> {
                if (StylingApp.isAvailable(context.getPackageManager())) {
                    try {
                        startActivity(StylingApp.createIntent());
                    } catch (RuntimeException e) {
                        showStylingUnavailable(context);
                    }
                } else {
                    showStylingUnavailable(context);
                }
                return true;
            });
        }
    }

    private void configurePreference(@NonNull String key, @NonNull String packageName, boolean available,
                                     @DrawableRes int fallbackIcon) {
        Preference preference = findPreference(key);
        if (preference == null) return;
        preference.setVisible(true);
        preference.setIcon(companionIcon(requireContext(), packageName, available, fallbackIcon));
        if (available) {
            String version = getInstalledVersion(packageName);
            preference.setSummary(version == null
                ? getString(R.string.termux_companion_installed_unknown_summary)
                : getString(R.string.termux_companion_installed_summary, version));
        } else {
            preference.setSummary(R.string.termux_companion_unavailable_summary);
        }
        if (!available) {
            preference.setOnPreferenceClickListener(ignored -> {
                showCompanionUnavailable(requireContext());
                return true;
            });
        }
    }

    @NonNull
    private Drawable companionIcon(@NonNull Context context, @NonNull String packageName, boolean installed,
                                  @DrawableRes int fallbackIcon) {
        if (installed) {
            try {
                Drawable appIcon = context.getPackageManager().getApplicationIcon(packageName);
                if (appIcon != null) return appIcon;
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                // Use the companion-specific fallback when the app icon is unavailable.
            }
        }

        Drawable container = ContextCompat.getDrawable(context, R.drawable.ui_icon_container);
        Drawable glyph = ContextCompat.getDrawable(context, fallbackIcon);
        if (container == null || glyph == null) throw new IllegalStateException("Missing companion fallback icon");
        glyph = DrawableCompat.wrap(glyph.mutate());
        DrawableCompat.setTint(glyph, ContextCompat.getColor(context, R.color.ui_on_container));
        LayerDrawable layers = new LayerDrawable(new Drawable[]{container.mutate(), glyph});
        int inset = Math.round(8 * context.getResources().getDisplayMetrics().density);
        layers.setLayerInset(1, inset, inset, inset, inset);
        layers.setAlpha(160);
        return layers;
    }

    private String getInstalledVersion(@NonNull String packageName) {
        try {
            String versionName = requireContext().getPackageManager().getPackageInfo(packageName, 0).versionName;
            return versionName == null || versionName.isEmpty() ? null : versionName;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    private void showCompanionUnavailable(@NonNull Context context) {
        DialogUtils.builder(context, R.drawable.ui_ic_extension, false)
            .setTitle(R.string.companion_apps_title)
            .setMessage(R.string.termux_companion_unavailable_message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private boolean isActivityAvailable(@NonNull PackageManager packageManager, @NonNull String packageName,
                                        @NonNull String activityName) {
        Intent intent = new Intent().setClassName(packageName, activityName);
        try {
            ResolveInfo info = packageManager.resolveActivity(intent, 0);
            return info != null && info.activityInfo != null
                && packageName.equals(info.activityInfo.packageName)
                && activityName.equals(info.activityInfo.name);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void showStylingUnavailable(@NonNull Context context) {
        DialogUtils.builder(context, R.drawable.ui_ic_palette, false)
            .setTitle(R.string.termux_styling_title)
            .setMessage(R.string.termux_styling_unavailable_message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }
}
