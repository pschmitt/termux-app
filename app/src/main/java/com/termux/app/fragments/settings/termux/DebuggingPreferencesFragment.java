package com.termux.app.fragments.settings.termux;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import android.widget.TextView;
import dev.pschmitt.termux.shared.interact.DialogUtils;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;

import com.termux.app.AdbShellService;
import com.termux.app.settings.TermuxPropertiesEditor;
import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxPropertyConstants;

import com.termux.R;
import dev.pschmitt.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import dev.pschmitt.termux.shared.logger.Logger;

@Keep
public class DebuggingPreferencesFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(DebuggingPreferencesDataStore.getInstance(context));

        setPreferencesFromResource(R.xml.termux_debugging_preferences, rootKey);

        configureLoggingPreferences(context);
        configureAdbShellPreference(context);
    }

    private void configureAdbShellPreference(@NonNull Context context) {
        SwitchPreferenceCompat adbShellPreference = findPreference("adb_shell_enabled");
        if (adbShellPreference == null) return;

        adbShellPreference.setOnPreferenceChangeListener((preference, value) -> {
            if (Boolean.TRUE.equals(value)) {
                String token = AdbShellService.createAccessToken(context);
                TextView tokenView = new TextView(context);
                tokenView.setText(token);
                tokenView.setTextIsSelectable(true);
                int padding = (int) (24 * context.getResources().getDisplayMetrics().density);
                tokenView.setPadding(padding, padding, padding, padding);

                androidx.appcompat.app.AlertDialog tokenDialog = DialogUtils.builder(context, R.drawable.ui_ic_terminal, false)
                    .setTitle(R.string.adb_shell_token_title)
                    .setMessage(R.string.adb_shell_token_warning)
                    .setView(tokenView)
                    .setPositiveButton(R.string.adb_shell_copy_token, (dialog, which) -> {
                        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                        clipboard.setPrimaryClip(ClipData.newPlainText("TermuxPP ADB shell token", token));
                    })
                    .setNegativeButton(android.R.string.ok, null)
                    .create();
                if (tokenDialog.getWindow() != null) {
                    tokenDialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
                }
                tokenDialog.show();
            }
            return true;
        });
    }

    private void configureLoggingPreferences(@NonNull Context context) {
        PreferenceCategory loggingCategory = findPreference("logging");
        if (loggingCategory == null) return;

        ListPreference logLevelListPreference = findPreference("log_level");
        if (logLevelListPreference != null) {
            TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(context, true);
            if (preferences == null) return;

            setLogLevelListPreferenceData(logLevelListPreference, context, preferences.getLogLevel());
            loggingCategory.addPreference(logLevelListPreference);
        }
    }

    public static ListPreference setLogLevelListPreferenceData(ListPreference logLevelListPreference, Context context, int logLevel) {
        if (logLevelListPreference == null)
            logLevelListPreference = new ListPreference(context);

        CharSequence[] logLevels = Logger.getLogLevelsArray();
        CharSequence[] logLevelLabels = Logger.getLogLevelLabelsArray(context, logLevels, true);

        logLevelListPreference.setEntryValues(logLevels);
        logLevelListPreference.setEntries(logLevelLabels);

        logLevelListPreference.setValue(String.valueOf(logLevel));
        logLevelListPreference.setDefaultValue(Logger.DEFAULT_LOG_LEVEL);

        return logLevelListPreference;
    }

}

class DebuggingPreferencesDataStore extends PreferenceDataStore {

    private final Context mContext;
    private final TermuxAppSharedPreferences mPreferences;

    private static DebuggingPreferencesDataStore mInstance;

    private DebuggingPreferencesDataStore(Context context) {
        mContext = context;
        mPreferences = TermuxAppSharedPreferences.build(context, true);
    }

    public static synchronized DebuggingPreferencesDataStore getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new DebuggingPreferencesDataStore(context);
        }
        return mInstance;
    }



    @Override
    @Nullable
    public String getString(String key, @Nullable String defValue) {
        if (mPreferences == null) return null;
        if (key == null) return null;

        switch (key) {
            case "log_level":
                return String.valueOf(mPreferences.getLogLevel());
            case "adb_shell_allowed_ssids":
                try {
                    return new TermuxPropertiesEditor().read().getProperty(
                        TermuxPropertyConstants.KEY_ADB_SHELL_ALLOWED_SSIDS, "");
                } catch (java.io.IOException e) {
                    return "";
                }
            default:
                return null;
        }
    }

    @Override
    public void putString(String key, @Nullable String value) {
        if (key == null) return;

        if ("adb_shell_allowed_ssids".equals(key)) {
            try {
                String list = value == null ? "" : value.trim();
                new TermuxPropertiesEditor().set(
                    TermuxPropertyConstants.KEY_ADB_SHELL_ALLOWED_SSIDS, list.isEmpty() ? null : list);
                Intent reload = new Intent(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
                reload.setPackage(mContext.getPackageName());
                mContext.sendBroadcast(reload);
            } catch (java.io.IOException | IllegalArgumentException e) {
                android.widget.Toast.makeText(mContext, R.string.properties_save_error, android.widget.Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (mPreferences == null) return;
        switch (key) {
            case "log_level":
                if (value != null) {
                    mPreferences.setLogLevel(mContext, Integer.parseInt(value));
                }
                break;
            default:
                break;
        }
    }



    @Override
    public void putBoolean(String key, boolean value) {
        if (key == null) return;

        if ("adb_shell_enabled".equals(key)) {
            android.content.SharedPreferences preferences = mContext.getSharedPreferences(AdbShellService.PREFS_NAME, Context.MODE_PRIVATE);
            android.content.SharedPreferences.Editor editor = preferences.edit().putBoolean(AdbShellService.PREF_ENABLED, value);
            if (!value) editor.remove(AdbShellService.PREF_TOKEN);
            editor.apply();

            Intent serviceIntent = new Intent(mContext, AdbShellService.class);
            if (!value) serviceIntent.setAction(AdbShellService.ACTION_STOP);
            mContext.startService(serviceIntent);
            return;
        }

        if (mPreferences == null) return;
        switch (key) {
            case "terminal_view_key_logging_enabled":
                    mPreferences.setTerminalViewKeyLoggingEnabled(value);
                break;
            case "plugin_error_notifications_enabled":
                mPreferences.setPluginErrorNotificationsEnabled(value);
                break;
            case "crash_report_notifications_enabled":
                mPreferences.setCrashReportNotificationsEnabled(value);
                break;
            default:
                break;
        }
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        if (key == null) return defValue;
        if ("adb_shell_enabled".equals(key)) {
            return mContext.getSharedPreferences(AdbShellService.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(AdbShellService.PREF_ENABLED, defValue);
        }
        if (mPreferences == null) return defValue;
        switch (key) {
            case "terminal_view_key_logging_enabled":
                return mPreferences.isTerminalViewKeyLoggingEnabled();
            case "plugin_error_notifications_enabled":
                return mPreferences.arePluginErrorNotificationsEnabled(false);
            case "crash_report_notifications_enabled":
                return mPreferences.areCrashReportNotificationsEnabled(false);
            default:
                return false;
        }
    }

}
