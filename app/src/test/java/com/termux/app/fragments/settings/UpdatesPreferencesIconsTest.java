package com.termux.app.fragments.settings;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.LayoutInflater;
import android.view.View;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.google.android.material.button.MaterialButton;

import com.termux.R;
import com.termux.app.activities.SettingsActivity;
import com.termux.app.update.UpdateTarget;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

@RunWith(RobolectricTestRunner.class)
public class UpdatesPreferencesIconsTest {

    private static UpdatesPreferencesFragment open(SettingsActivity activity) {
        UpdatesPreferencesFragment fragment = new UpdatesPreferencesFragment();
        activity.getSupportFragmentManager().beginTransaction().replace(R.id.settings, fragment).commitNow();
        return fragment;
    }

    private static void install(SettingsActivity activity, UpdateTarget target) {
        PackageInfo info = new PackageInfo();
        info.packageName = target.packageName;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = target.packageName;
        Shadows.shadowOf(activity.getPackageManager()).installPackage(info);
        Shadows.shadowOf(activity.getPackageManager()).setApplicationIcon(target.packageName,
            new ColorDrawable(Color.MAGENTA));
    }

    @Test
    public void everyRowHasAnIcon() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        UpdatesPreferencesFragment fragment = open(activity);

        for (String key : new String[]{"update_shizuku", "update_app_core"}) {
            Preference preference = fragment.findPreference(key);
            Assert.assertNotNull(key, preference);
            Assert.assertNotNull(key + " has an icon", preference.getIcon());
        }
        Assert.assertNull(fragment.findPreference("updates_apps"));
        Assert.assertNull(fragment.findPreference("update_channel"));
    }

    @Test
    public void statusCardAndShizukuUseTheirOwnLayouts() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        UpdatesPreferencesFragment fragment = open(activity);

        Assert.assertEquals(R.layout.ui_preference_update_status,
            fragment.findPreference("update_app_core").getLayoutResource());
        Assert.assertEquals(R.layout.ui_preference_row_app,
            fragment.findPreference("update_shizuku").getLayoutResource());
    }

    @Test
    public void initialCardOffersACheckWithoutStartingOne() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        UpdatesPreferencesFragment fragment = open(activity);
        UpdateAppPreference card = fragment.findPreference("update_app_core");
        View view = LayoutInflater.from(fragment.requireContext())
            .inflate(R.layout.ui_preference_update_status, null);
        card.onBindViewHolder(PreferenceViewHolder.createInstanceForTests(view));

        MaterialButton button = view.findViewById(R.id.update_action);
        Assert.assertTrue(button.hasOnClickListeners());
        Assert.assertEquals(activity.getString(R.string.termux_update_check), button.getText());
        Assert.assertEquals(View.GONE, view.findViewById(R.id.update_progress).getVisibility());
    }

    @Test
    public void installedCoreShowsItsOwnIconAndMissingOneAStandIn() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        install(activity, UpdateTarget.CORE);

        Drawable installed = UpdatesPreferencesFragment.appIcon(activity, UpdateTarget.CORE, true);
        Assert.assertTrue("the application's own icon", installed instanceof ColorDrawable);

        Assert.assertTrue(UpdatesPreferencesFragment.appIcon(activity, UpdateTarget.CORE, false)
            instanceof LayerDrawable);
    }

    @Test
    public void shizukuRowShowsTheManagersOwnIconWhenInstalledAndAStandInOtherwise() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        String manager = com.termux.app.update.ShizukuInstaller.MANAGER_PACKAGE;

        UpdatesPreferencesFragment fragment = open(activity);
        Assert.assertTrue("a stand-in while Shizuku is missing",
            fragment.findPreference("update_shizuku").getIcon() instanceof LayerDrawable);

        PackageInfo info = new PackageInfo();
        info.packageName = manager;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = manager;
        Shadows.shadowOf(activity.getPackageManager()).installPackage(info);
        Shadows.shadowOf(activity.getPackageManager()).setApplicationIcon(manager, new ColorDrawable(Color.CYAN));

        Drawable icon = UpdatesPreferencesFragment.installedIconOrStandIn(activity, manager, true,
            R.drawable.ui_ic_power);
        Assert.assertTrue("the Shizuku manager's own icon", icon instanceof ColorDrawable);
    }

}
