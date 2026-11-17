package com.termux.app.fragments.settings.termux;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;

import com.termux.R;
import com.termux.app.AdbShellAuthorizations;
import com.termux.app.AdbShellService;

import java.util.List;

/**
 * Access information and the list of authorized ADB shell clients. Enrollment approval stays in
 * {@link com.termux.app.AdbShellApprovalActivity}; this screen never shows the enrollment token.
 */
@Keep
public class AdbShellAuthorizationsPreferencesFragment extends AdbShellSettingsFragment {
    public static final String KEY_STATUS = "adb_shell_status";
    public static final String KEY_CONNECTION = "adb_shell_connection";
    public static final String KEY_CLIENTS = "adb_shell_clients";
    public static final String KEY_NO_CLIENTS = "adb_shell_no_clients";
    public static final String ARG_FINGERPRINT = "fingerprint";

    @Override
    int titleResource() {
        return R.string.adb_shell_authorizations_title;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.adb_shell_authorizations_preferences, rootKey);
    }

    @Override
    public void onStart() {
        super.onStart();
        // The list changes while the screen is stopped (revocation from the detail screen).
        refresh(requireContext());
    }

    private void refresh(@NonNull Context context) {
        boolean enabled = context.getSharedPreferences(AdbShellService.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(AdbShellService.PREF_ENABLED, false);
        Preference status = findPreference(KEY_STATUS);
        if (status != null) {
            status.setSummary(enabled ? R.string.adb_shell_status_enabled : R.string.adb_shell_status_disabled);
        }
        Preference connection = findPreference(KEY_CONNECTION);
        if (connection != null) {
            connection.setSummary(getString(R.string.adb_shell_connection_summary,
                AdbShellService.PORT, AdbShellService.TLS_PORT));
        }

        PreferenceCategory clients = findPreference(KEY_CLIENTS);
        if (clients == null) return;
        clients.removeAll();
        List<AdbShellAuthorizations.Entry> entries = new AdbShellAuthorizations(context).list();
        if (entries.isEmpty()) {
            Preference none = new Preference(context);
            none.setKey(KEY_NO_CLIENTS);
            none.setIcon(R.drawable.ui_ic_terminal);
            none.setPersistent(false);
            none.setSelectable(false);
            none.setTitle(R.string.adb_shell_no_clients);
            clients.addPreference(none);
            return;
        }
        for (AdbShellAuthorizations.Entry entry : entries) {
            Preference client = new Preference(context);
            client.setKey(entry.fingerprint);
            client.setIcon(R.drawable.ui_ic_terminal);
            client.setPersistent(false);
            client.setTitle(entry.name);
            client.setSummary(entry.fingerprint);
            client.setFragment(AdbShellAuthorizationPreferencesFragment.class.getName());
            client.getExtras().putString(ARG_FINGERPRINT, entry.fingerprint);
            clients.addPreference(client);
        }
    }
}
