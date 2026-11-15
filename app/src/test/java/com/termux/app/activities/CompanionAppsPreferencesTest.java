package com.termux.app.activities;

import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.termux.R;
import com.termux.app.fragments.settings.CompanionAppsPreferencesFragment;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class CompanionAppsPreferencesTest {

    @Test
    public void rootSettingsGroupsAllTermuxppCompanionsInOneSubmenu() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        Fragment rootFragment = activity.getSupportFragmentManager().findFragmentById(R.id.settings);
        Assert.assertTrue(rootFragment instanceof PreferenceFragmentCompat);
        PreferenceFragmentCompat root = (PreferenceFragmentCompat) rootFragment;

        Assert.assertNotNull(root.findPreference("companion_apps"));
        Assert.assertNull(root.findPreference("termux_api"));
        Assert.assertNull(root.findPreference("termux_tasker"));
        Assert.assertNull(root.findPreference("termux_float"));
        Assert.assertNull(root.findPreference("termux_boot"));
        Assert.assertNull(root.findPreference("termux_styling"));
        Assert.assertNull(root.findPreference("termux_widget"));
    }

    @Test
    public void companionScreenIncludesEverySupportedTermuxppApp() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        CompanionAppsPreferencesFragment fragment = new CompanionAppsPreferencesFragment();
        activity.getSupportFragmentManager().beginTransaction().replace(R.id.settings, fragment).commitNow();

        Assert.assertNotNull(fragment.findPreference("termux_api"));
        Assert.assertNotNull(fragment.findPreference("termux_tasker"));
        Assert.assertNotNull(fragment.findPreference("termux_float"));
        Assert.assertNotNull(fragment.findPreference("termux_boot"));
        Assert.assertNotNull(fragment.findPreference("termux_styling"));
        Assert.assertNotNull(fragment.findPreference("termux_widget"));

        Preference styling = fragment.findPreference("termux_styling");
        Assert.assertNotNull(styling);
        Assert.assertFalse(styling.getSummary().toString().isEmpty());

        Assert.assertEquals("com.termux.app.fragments.settings.termux_api.DebuggingPreferencesFragment",
            fragment.findPreference("termux_api").getFragment());
        Assert.assertEquals("com.termux.app.fragments.settings.termux_tasker.DebuggingPreferencesFragment",
            fragment.findPreference("termux_tasker").getFragment());
        Assert.assertEquals("com.termux.app.fragments.settings.termux_float.DebuggingPreferencesFragment",
            fragment.findPreference("termux_float").getFragment());
        Assert.assertEquals("com.termux.app.fragments.settings.termux_widget.DebuggingPreferencesFragment",
            fragment.findPreference("termux_widget").getFragment());

        for (String key : new String[]{"termux_api", "termux_tasker", "termux_float", "termux_widget",
            "termux_boot", "termux_styling"}) {
            Preference companion = fragment.findPreference(key);
            Assert.assertNotNull(companion.getIcon());
            Assert.assertTrue(companion.isVisible());
        }
    }
}
