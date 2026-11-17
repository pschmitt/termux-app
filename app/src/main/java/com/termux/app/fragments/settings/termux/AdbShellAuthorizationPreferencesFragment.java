package com.termux.app.fragments.settings.termux;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;

import com.termux.R;
import com.termux.app.AdbShellAuthorizations;
import com.termux.app.AdbShellService;
import dev.pschmitt.termux.shared.interact.DialogUtils;

/** Details of one authorized ADB shell client, with revocation. */
@Keep
public class AdbShellAuthorizationPreferencesFragment extends AdbShellSettingsFragment {
    public static final String KEY_NAME = "adb_shell_client_name";
    public static final String KEY_FINGERPRINT = "adb_shell_client_fingerprint";
    public static final String KEY_REVOKE = "adb_shell_client_revoke";

    @Nullable
    private AdbShellAuthorizations.Entry entry;

    @Override
    int titleResource() {
        return R.string.adb_shell_client_title;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.adb_shell_authorization_preferences, rootKey);
        entry = find(requireContext(), getArguments() == null ? null
            : getArguments().getString(AdbShellAuthorizationsPreferencesFragment.ARG_FINGERPRINT));
        Preference name = findPreference(KEY_NAME);
        Preference fingerprint = findPreference(KEY_FINGERPRINT);
        Preference revoke = findPreference(KEY_REVOKE);
        if (entry == null) {
            // Already revoked (or never authorized): there is nothing to show, so do not offer an action.
            getPreferenceScreen().removeAll();
            return;
        }
        if (name != null) name.setSummary(entry.name);
        if (fingerprint != null) fingerprint.setSummary(entry.fingerprint);
        if (revoke != null) revoke.setOnPreferenceClickListener(preference -> {
            confirmRevoke(requireContext(), entry);
            return true;
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        if (entry == null) getParentFragmentManager().popBackStack();
    }

    @Nullable
    private static AdbShellAuthorizations.Entry find(@NonNull Context context, @Nullable String fingerprint) {
        if (fingerprint == null) return null;
        for (AdbShellAuthorizations.Entry candidate : new AdbShellAuthorizations(context).list()) {
            if (candidate.fingerprint.equals(fingerprint)) return candidate;
        }
        return null;
    }

    private void confirmRevoke(@NonNull Context context, @NonNull AdbShellAuthorizations.Entry client) {
        DialogUtils.builder(context, R.drawable.ui_ic_terminal, false)
            .setTitle(R.string.adb_shell_revoke_confirm_title)
            .setMessage(client.name + "\n" + client.fingerprint)
            .setPositiveButton(R.string.adb_shell_revoke_title, (dialog, which) -> revoke(context, client))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void revoke(@NonNull Context context, @NonNull AdbShellAuthorizations.Entry client) {
        if (!new AdbShellAuthorizations(context).revoke(client.fingerprint)) return;
        // Drop sessions already authenticated with the revoked certificate. A stopped service has none,
        // so do not start it just to be told so.
        if (context.getSharedPreferences(AdbShellService.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(AdbShellService.PREF_ENABLED, false)) {
            context.startService(new Intent(context, AdbShellService.class)
                .setAction(AdbShellService.ACTION_AUTHORIZATIONS_CHANGED));
        }
        if (isAdded()) getParentFragmentManager().popBackStack();
    }
}
