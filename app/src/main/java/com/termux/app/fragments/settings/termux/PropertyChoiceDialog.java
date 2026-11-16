package com.termux.app.fragments.settings.termux;

import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.ListPreference;
import androidx.preference.ListPreferenceDialogFragmentCompat;

public class PropertyChoiceDialog extends ListPreferenceDialogFragmentCompat {
    static void show(PropertiesPreferencesFragment parent, ListPreference preference) {
        PropertyChoiceDialog dialog = new PropertyChoiceDialog();
        Bundle args = new Bundle();
        args.putString("key", preference.getKey());
        dialog.setArguments(args);
        dialog.setTargetFragment(parent, 0);
        dialog.show(parent.getParentFragmentManager(), "property_choice");
    }

    @Override protected void onPrepareDialogBuilder(AlertDialog.Builder builder) {
        super.onPrepareDialogBuilder(builder);
        // AlertController shows a message instead of its choice list, not alongside it.
        builder.setMessage(null);
        TextView help = new TextView(requireContext());
        help.setText(getPreference().getDialogMessage());
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        help.setPadding(padding, padding / 2, padding, padding / 2);
        ScrollView scroll = new ScrollView(requireContext());
        scroll.addView(help);
        builder.setView(scroll);
    }
}
