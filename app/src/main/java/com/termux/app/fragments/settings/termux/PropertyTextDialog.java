package com.termux.app.fragments.settings.termux;

import dev.pschmitt.termux.shared.interact.DialogUtils;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.preference.EditTextPreference;

import com.termux.R;
import com.termux.app.settings.PropertyInputSpec;

public class PropertyTextDialog extends DialogFragment {
    private EditText text;

    static void show(PropertiesPreferencesFragment parent, EditTextPreference preference) {
        PropertyTextDialog dialog = new PropertyTextDialog();
        Bundle args = new Bundle();
        args.putString("key", preference.getKey());
        args.putString("value", preference.getText());
        dialog.setArguments(args);
        dialog.setTargetFragment(parent, 0);
        dialog.show(parent.getParentFragmentManager(), "property_text");
    }

    @NonNull @Override public android.app.Dialog onCreateDialog(Bundle state) {
        String key = requireArguments().getString("key");
        text = new EditText(requireContext());
        text.setInputType(PropertyInputSpec.inputType(key));
        text.setText(state == null ? requireArguments().getString("value", "") : state.getString("value"));
        ScrollView scroll = new ScrollView(requireContext());
        scroll.addView(text);
        return DialogUtils.builder(requireContext()).setTitle(key.replace('-', ' '))
            .setMessage(PropertyInputSpec.help(key)).setView(scroll)
            .setPositiveButton(R.string.extra_save, null)
            .setNeutralButton(R.string.properties_default, null)
            .setNegativeButton(android.R.string.cancel, null).create();
    }

    @Override public void onStart() {
        super.onStart();
        AlertDialog dialog = (AlertDialog) requireDialog();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            text.setText("");
            text.setError(null);
        });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String key = requireArguments().getString("key");
            String value = text.getText().toString();
            String error = PropertyInputSpec.validationError(key, value);
            if (error != null) { text.setError(error); return; }
            PropertiesPreferencesFragment parent = (PropertiesPreferencesFragment) getTargetFragment();
            EditTextPreference preference = parent == null ? null : parent.findPreference(key);
            if (preference != null && preference.callChangeListener(value)) {
                preference.setText(value);
                dismiss();
            }
        });
    }

    @Override public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("value", text.getText().toString());
    }
}
