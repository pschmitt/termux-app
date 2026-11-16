package com.termux.app.fragments.settings.termux;

import dev.pschmitt.termux.shared.interact.DialogUtils;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.termux.R;
import com.termux.app.settings.ExtraKeysLayout;

import org.json.JSONException;
import org.json.JSONObject;

public class ExtraKeysEditorDialog extends DialogFragment {
    private ExtraKeysLayout layout;
    private String source;
    private boolean useDefault;
    private LinearLayout content;

    static void show(PropertiesPreferencesFragment parent, String value) {
        ExtraKeysEditorDialog dialog = new ExtraKeysEditorDialog();
        Bundle args = new Bundle();
        args.putString("value", value);
        dialog.setArguments(args);
        dialog.setTargetFragment(parent, 0);
        dialog.show(parent.getParentFragmentManager(), "extra_keys_editor");
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        source = state == null ? requireArguments().getString("value", "") : state.getString("value", "");
        useDefault = state == null ? source.isEmpty() : state.getBoolean("default");
        try {
            layout = source.isEmpty() ? ExtraKeysLayout.defaults() : new ExtraKeysLayout(source);
        } catch (JSONException e) {
            layout = null;
        }
    }

    @Override public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("value", layout == null ? source : layout.toString());
        out.putBoolean("default", useDefault);
    }

    @NonNull @Override public android.app.Dialog onCreateDialog(Bundle state) {
        content = column();
        ScrollView scroll = new ScrollView(requireContext());
        scroll.addView(content);
        render();
        return DialogUtils.builder(requireContext()).setTitle(R.string.extra_editor_title)
            .setView(scroll).setPositiveButton(R.string.extra_save, null)
            .setNegativeButton(android.R.string.cancel, null).create();
    }

    @Override public void onStart() {
        super.onStart();
        ((AlertDialog) requireDialog()).getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (layout == null) {
                Toast.makeText(requireContext(), R.string.extra_invalid, Toast.LENGTH_LONG).show();
                return;
            }
            PropertiesPreferencesFragment parent = (PropertiesPreferencesFragment) getTargetFragment();
            if (parent != null && parent.saveExtraKeys(useDefault ? "" : layout.toString())) dismiss();
        });
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(requireContext());
        view.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        view.setPadding(padding, padding / 2, padding, padding / 2);
        return view;
    }

    private void button(LinearLayout parent, String title, View.OnClickListener action) {
        Button button = new Button(requireContext());
        button.setText(title);
        button.setOnClickListener(action);
        parent.addView(button);
    }

    private void render() {
        content.removeAllViews();
        TextView help = new TextView(requireContext());
        help.setText(layout == null ? R.string.extra_invalid_help : R.string.extra_editor_help);
        content.addView(help);
        if (layout != null) {
            if (layout.rowCount() == 0) {
                TextView empty = new TextView(requireContext());
                empty.setText(R.string.extra_empty);
                content.addView(empty);
            }
            for (int r = 0; r < layout.rowCount(); r++) {
                final int row = r;
                TextView heading = new TextView(requireContext());
                heading.setText(getString(R.string.extra_row, row + 1));
                content.addView(heading);
                HorizontalScrollView scroll = new HorizontalScrollView(requireContext());
                LinearLayout keys = new LinearLayout(requireContext());
                scroll.addView(keys);
                content.addView(scroll);
                for (int k = 0; k < layout.keyCount(row); k++) {
                    final int key = k;
                    try {
                        button(keys, layout.label(row, key), v -> editKey(row, key));
                    } catch (JSONException e) {
                        throw new IllegalStateException(e);
                    }
                }
                button(content, getString(R.string.extra_add_key), v -> editKey(row, layout.keyCount(row)));
                LinearLayout actions = new LinearLayout(requireContext());
                actions.setOrientation(LinearLayout.VERTICAL);
                content.addView(actions);
                if (row > 0) button(actions, getString(R.string.extra_row_up), v -> {
                    layout.moveRow(row, -1); changed();
                });
                if (row + 1 < layout.rowCount()) button(actions, getString(R.string.extra_row_down), v -> {
                    layout.moveRow(row, 1); changed();
                });
                button(actions, getString(R.string.extra_remove_row), v -> DialogUtils.builder(requireContext())
                    .setMessage(R.string.extra_remove_row_confirm)
                    .setPositiveButton(R.string.extra_remove_row, (d, w) -> { layout.removeRow(row); changed(); })
                    .setNegativeButton(android.R.string.cancel, null).show());
            }
            button(content, getString(R.string.extra_add_row), v -> { layout.addRow(); changed(); });
        }
        button(content, getString(R.string.extra_reset), v -> DialogUtils.builder(requireContext())
            .setMessage(R.string.extra_reset_confirm)
            .setPositiveButton(R.string.extra_reset, (d, w) -> {
                try { layout = ExtraKeysLayout.defaults(); useDefault = true; render(); }
                catch (JSONException e) { throw new IllegalStateException(e); }
            }).setNegativeButton(android.R.string.cancel, null).show());
        button(content, getString(R.string.extra_raw), v -> {
            RawDialog raw = new RawDialog();
            Bundle args = new Bundle();
            args.putString("value", layout == null ? source : layout.toString());
            raw.setArguments(args);
            raw.setTargetFragment(this, 0);
            raw.show(getParentFragmentManager(), "extra_keys_raw");
        });
    }

    private void changed() { useDefault = false; render(); }

    private void editKey(int row, int key) {
        try {
            KeyDialog dialog = new KeyDialog();
            Bundle args = new Bundle();
            args.putInt("row", row);
            args.putInt("key", key);
            args.putString("value", key < layout.keyCount(row) ? layout.key(row, key).toString() : "{}");
            dialog.setArguments(args);
            dialog.setTargetFragment(this, 0);
            dialog.show(getParentFragmentManager(), "extra_key");
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }

    public static class RawDialog extends DialogFragment {
        private EditText text;
        @NonNull @Override public android.app.Dialog onCreateDialog(Bundle state) {
            text = new EditText(requireContext());
            text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            text.setMinLines(5);
            text.setText(state == null ? requireArguments().getString("value") : state.getString("value"));
            ScrollView scroll = new ScrollView(requireContext());
            scroll.addView(text);
            return DialogUtils.builder(requireContext()).setTitle(R.string.extra_raw)
                .setMessage(R.string.extra_raw_help).setView(scroll)
                .setPositiveButton(R.string.extra_apply, null).setNegativeButton(android.R.string.cancel, null).create();
        }
        @Override public void onStart() {
            super.onStart();
            ((AlertDialog) requireDialog()).getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    ExtraKeysLayout value = new ExtraKeysLayout(text.getText().toString());
                    ExtraKeysEditorDialog parent = (ExtraKeysEditorDialog) getTargetFragment();
                    if (parent == null) return;
                    parent.layout = value;
                    parent.changed();
                    dismiss();
                } catch (JSONException e) { text.setError(getString(R.string.extra_invalid)); }
            });
        }
        @Override public void onSaveInstanceState(@NonNull Bundle out) {
            super.onSaveInstanceState(out);
            out.putString("value", text.getText().toString());
        }
    }

    public static class KeyDialog extends DialogFragment {
        private JSONObject original;
        private AutoCompleteTextView key;
        private EditText label;
        private AutoCompleteTextView popup;
        private EditText popupLabel;
        private CheckBox macro;
        private CheckBox popupMacro;
        private int row;
        private int index;

        private AutoCompleteTextView input(LinearLayout form, int title, String value) {
            TextView heading = new TextView(requireContext());
            heading.setText(title);
            form.addView(heading);
            AutoCompleteTextView input = new AutoCompleteTextView(requireContext());
            input.setHint(title);
            heading.setLabelFor(View.generateViewId());
            input.setId(heading.getLabelFor());
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            input.setText(value);
            input.setThreshold(0);
            input.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_dropdown_item_1line,
                new String[]{"ESC", "TAB", "CTRL", "ALT", "SHIFT", "FN", "ENTER", "BKSP", "DEL", "UP", "DOWN", "LEFT", "RIGHT", "HOME", "END", "PGUP", "PGDN", "KEYBOARD", "DRAWER", "F1", "F2", "F3", "F4", "/", "-", "|"}));
            input.setOnClickListener(v -> input.showDropDown());
            form.addView(input);
            return input;
        }

        @NonNull @Override public android.app.Dialog onCreateDialog(Bundle state) {
            row = requireArguments().getInt("row");
            index = requireArguments().getInt("key");
            try { original = new JSONObject(requireArguments().getString("value")); }
            catch (JSONException e) { throw new IllegalStateException(e); }
            LinearLayout form = new LinearLayout(requireContext());
            form.setOrientation(LinearLayout.VERTICAL);
            int padding = Math.round(16 * getResources().getDisplayMetrics().density);
            form.setPadding(padding, padding, padding, padding);
            TextView help = new TextView(requireContext());
            help.setText(R.string.extra_key_help);
            form.addView(help);
            key = input(form, R.string.extra_key_value, original.optString("macro", original.optString("key")));
            macro = new CheckBox(requireContext());
            macro.setText(R.string.extra_macro);
            macro.setChecked(original.has("macro"));
            form.addView(macro);
            label = input(form, R.string.extra_key_label, original.optString("display"));
            Object popupValue = original.opt("popup");
            JSONObject popupObject = popupValue instanceof JSONObject ? (JSONObject) popupValue : new JSONObject();
            popup = input(form, R.string.extra_popup, popupValue instanceof String ? (String) popupValue
                : popupObject.optString("macro", popupObject.optString("key")));
            popupMacro = new CheckBox(requireContext());
            popupMacro.setText(R.string.extra_macro);
            popupMacro.setChecked(popupObject.has("macro"));
            form.addView(popupMacro);
            popupLabel = input(form, R.string.extra_popup_label, popupObject.optString("display"));
            if (state != null) {
                key.setText(state.getString("key"));
                label.setText(state.getString("label"));
                popup.setText(state.getString("popup"));
                popupLabel.setText(state.getString("popupLabel"));
                macro.setChecked(state.getBoolean("macro"));
                popupMacro.setChecked(state.getBoolean("popupMacro"));
            }
            ExtraKeysEditorDialog parent = (ExtraKeysEditorDialog) getTargetFragment();
            if (parent != null && index < parent.layout.keyCount(row)) {
                Button remove = new Button(requireContext());
                remove.setText(R.string.extra_remove_key);
                remove.setOnClickListener(v -> { parent.layout.removeKey(row, index); parent.changed(); dismiss(); });
                form.addView(remove);
                LinearLayout moves = new LinearLayout(requireContext());
                form.addView(moves);
                if (index > 0) parent.button(moves, getString(R.string.extra_key_left), v -> {
                    parent.layout.moveKey(row, index, -1); parent.changed(); dismiss();
                });
                if (index + 1 < parent.layout.keyCount(row)) parent.button(moves, getString(R.string.extra_key_right), v -> {
                    parent.layout.moveKey(row, index, 1); parent.changed(); dismiss();
                });
            }
            ScrollView scroll = new ScrollView(requireContext());
            scroll.addView(form);
            return DialogUtils.builder(requireContext()).setTitle(R.string.extra_edit_key).setView(scroll)
                .setPositiveButton(R.string.extra_apply, null).setNegativeButton(android.R.string.cancel, null).create();
        }

        @Override public void onStart() {
            super.onStart();
            ((AlertDialog) requireDialog()).getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (key.getText().length() == 0) { key.setError(getString(R.string.extra_required)); return; }
                try {
                    JSONObject edited = new JSONObject(original.toString());
                    edited.remove("key"); edited.remove("macro");
                    edited.put(macro.isChecked() ? "macro" : "key", key.getText().toString());
                    if (label.getText().length() == 0) edited.remove("display");
                    else edited.put("display", label.getText().toString());
                    if (popup.getText().length() == 0) edited.remove("popup");
                    else {
                        Object previous = original.opt("popup");
                        JSONObject p = previous instanceof JSONObject ? new JSONObject(previous.toString()) : new JSONObject();
                        p.remove("key"); p.remove("macro");
                        p.put(popupMacro.isChecked() ? "macro" : "key", popup.getText().toString());
                        if (popupLabel.getText().length() == 0) p.remove("display");
                        else p.put("display", popupLabel.getText().toString());
                        edited.put("popup", p);
                    }
                    ExtraKeysEditorDialog parent = (ExtraKeysEditorDialog) getTargetFragment();
                    if (parent == null) return;
                    parent.layout.putKey(row, index, edited);
                    parent.changed();
                    dismiss();
                } catch (JSONException e) { key.setError(getString(R.string.extra_invalid)); }
            });
        }

        @Override public void onSaveInstanceState(@NonNull Bundle out) {
            super.onSaveInstanceState(out);
            out.putString("key", key.getText().toString());
            out.putString("label", label.getText().toString());
            out.putString("popup", popup.getText().toString());
            out.putString("popupLabel", popupLabel.getText().toString());
            out.putBoolean("macro", macro.isChecked());
            out.putBoolean("popupMacro", popupMacro.isChecked());
        }
    }
}
