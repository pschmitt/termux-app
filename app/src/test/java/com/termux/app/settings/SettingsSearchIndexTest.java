package com.termux.app.settings;

import com.termux.app.activities.SettingsActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class SettingsSearchIndexTest {

    @Test
    public void indexesPreferencesInChildScreensAndDynamicProperties() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        List<SettingsSearchIndex.Result> results = SettingsSearchIndex.build(activity);

        SettingsSearchIndex.Result tripleTap = resultFor(results, "terminal-triple-tap-action");
        assertNotNull(tripleTap);
        assertTrue(tripleTap.title.toLowerCase().contains("triple tap action"));
        assertEquals("com.termux.app.fragments.settings.termux.PropertiesPreferencesFragment",
            tripleTap.fragmentPath.get(0));
        assertTrue(tripleTap.matches("triple"));
        assertTrue(tripleTap.matches("triple tap action"));

        SettingsSearchIndex.Result logLevel = resultFor(results, "log_level");
        assertNotNull(logLevel);
        assertTrue(logLevel.title + " :: " + logLevel.breadcrumb, logLevel.matches("api log level"));
    }

    @Test
    public void doesNotIndexConfiguredPropertyValues() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        for (SettingsSearchIndex.Result result : SettingsSearchIndex.build(activity)) {
            assertTrue(result.title, !result.title.contains("printf"));
            assertTrue(result.breadcrumb, !result.breadcrumb.contains("secret command"));
        }
    }

    private static SettingsSearchIndex.Result resultFor(List<SettingsSearchIndex.Result> results, String key) {
        for (SettingsSearchIndex.Result result : results) {
            if (key.equals(result.preferenceKey)) return result;
        }
        return null;
    }
}
