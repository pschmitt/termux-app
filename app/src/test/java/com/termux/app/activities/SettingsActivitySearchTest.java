package com.termux.app.activities;

import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;


import com.termux.R;
import com.termux.app.fragments.settings.termux.PropertiesPreferencesFragment;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class SettingsActivitySearchTest {

    @Test
    public void rootSearchNavigatesToAndFocusesTheExactDynamicProperty() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        PreferenceFragmentCompat root = (PreferenceFragmentCompat)
            activity.getSupportFragmentManager().findFragmentById(R.id.settings);
        activity.startSearch(root);
        activity.filterSearch("triple tap action");
        Preference result = root.findPreference("settings-search-result-0");
        assertNotNull(result);
        assertEquals("Terminal triple tap action", result.getTitle());
        result.performClick();
        activity.getSupportFragmentManager().executePendingTransactions();

        Fragment current = activity.getSupportFragmentManager().findFragmentById(R.id.settings);
        assertTrue(current instanceof PropertiesPreferencesFragment);
        PropertiesPreferencesFragment properties = (PropertiesPreferencesFragment) current;
        assertNotNull(properties.findPreference("terminal-triple-tap-action"));
        assertEquals("terminal-triple-tap-action",
            properties.getArguments().getString("termuxpp.settings.search.focus-key"));
    }
}
