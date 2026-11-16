package com.termux.app.settings;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.preference.EditTextPreference;

import com.termux.R;
import com.termux.app.activities.SettingsActivity;
import com.termux.app.fragments.settings.termux.ExtraKeysEditorDialog;
import com.termux.app.fragments.settings.termux.PropertiesPreferencesFragment;
import com.termux.app.fragments.settings.termux.PropertyTextDialog;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PropertyDialogsTest {
    private SettingsActivity activity() {
        return Robolectric.buildActivity(SettingsActivity.class).setup().get();
    }

    private static <T extends View> T view(View root, Class<T> type, String text) {
        if (type.isInstance(root) && (text == null || root instanceof android.widget.TextView
            && text.equals(((android.widget.TextView) root).getText().toString()))) return type.cast(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = view(group.getChildAt(i), type, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test public void visualEditorAddsRowsAndKeysWithoutSavingAndKeepsDraftState() {
        SettingsActivity activity = activity();
        ExtraKeysEditorDialog dialog = new ExtraKeysEditorDialog();
        Bundle args = new Bundle();
        args.putString("value", "[['ESC']]");
        dialog.setArguments(args);
        dialog.showNow(activity.getSupportFragmentManager(), "layout");
        view(dialog.requireDialog().getWindow().getDecorView(), Button.class, "Add row").performClick();
        Bundle state = new Bundle();
        dialog.onSaveInstanceState(state);
        assertEquals("[[\"ESC\"],[]]", state.getString("value"));
        assertFalse(state.getBoolean("default"));
        view(dialog.requireDialog().getWindow().getDecorView(), Button.class, "ESC").performClick();
        activity.getSupportFragmentManager().executePendingTransactions();
        DialogFragment keyDialog = (DialogFragment) activity.getSupportFragmentManager().findFragmentByTag("extra_key");
        EditText key = view(keyDialog.requireDialog().getWindow().getDecorView(), EditText.class, null);
        key.setText("");
        ((AlertDialog) keyDialog.requireDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertNotNull(key.getError());
        key.setText("TAB");
        ((AlertDialog) keyDialog.requireDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        activity.getSupportFragmentManager().executePendingTransactions();
        Bundle edited = new Bundle();
        dialog.onSaveInstanceState(edited);
        assertEquals("[[{\"key\":\"TAB\"}],[]]", edited.getString("value"));
        dialog.dismiss();
        activity.getSupportFragmentManager().executePendingTransactions();
    }

    @Test public void invalidRawLayoutRemainsOpenForCorrection() {
        SettingsActivity activity = activity();
        ExtraKeysEditorDialog.RawDialog dialog = new ExtraKeysEditorDialog.RawDialog();
        Bundle args = new Bundle();
        args.putString("value", "[['ESC']]");
        dialog.setArguments(args);
        dialog.showNow(activity.getSupportFragmentManager(), "raw");
        EditText text = view(dialog.requireDialog().getWindow().getDecorView(), EditText.class, null);
        text.setText("not json");
        ((AlertDialog) dialog.requireDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertNotNull(text.getError());
        assertTrue(dialog.requireDialog().isShowing());
        assertEquals("not json", text.getText().toString());
        dialog.dismiss();
        activity.getSupportFragmentManager().executePendingTransactions();
    }

    @Test public void numericDialogValidatesWithoutClosingAndDefaultIsOnlyADraft() {
        SettingsActivity activity = activity();
        PropertiesPreferencesFragment parent = new PropertiesPreferencesFragment();
        activity.getSupportFragmentManager().beginTransaction().replace(R.id.settings, parent).commitNow();
        EditTextPreference preference = parent.findPreference("terminal-cursor-blink-rate");
        parent.onDisplayPreferenceDialog(preference);
        activity.getSupportFragmentManager().executePendingTransactions();
        DialogFragment dialog = (DialogFragment) activity.getSupportFragmentManager().findFragmentByTag("property_text");
        assertTrue(dialog instanceof PropertyTextDialog);
        EditText text = view(dialog.requireDialog().getWindow().getDecorView(), EditText.class, null);
        text.setText("99");
        ((AlertDialog) dialog.requireDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertNotNull(text.getError());
        assertTrue(dialog.requireDialog().isShowing());
        ((AlertDialog) dialog.requireDialog()).getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        assertEquals("", text.getText().toString());
        assertNull(text.getError());
        dialog.dismiss();
        activity.getSupportFragmentManager().executePendingTransactions();
    }
}
