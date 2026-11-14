package com.termux.app.terminal;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;

import com.termux.shared.termux.TermuxConstants;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

@RunWith(RobolectricTestRunner.class)
public class StylingAppTest {

    private static final String PACKAGE = TermuxConstants.TERMUX_STYLING_PACKAGE_NAME;
    private static final String ACTIVITY = TermuxConstants.TERMUX_STYLING_APP.TERMUX_STYLING_ACTIVITY_NAME;

    private static PackageManager packageManager() {
        return RuntimeEnvironment.getApplication().getPackageManager();
    }

    @Test
    public void intentTargetsTheExactStylingComponent() {
        Intent intent = StylingApp.createIntent();
        Assert.assertEquals(new ComponentName(PACKAGE, ACTIVITY), intent.getComponent());
        // Explicit only, so no other app can answer it.
        Assert.assertNull(intent.getAction());
    }

    @Test
    public void unavailableWhenStylingIsNotInstalled() {
        Assert.assertFalse(StylingApp.isAvailable(packageManager()));
    }

    @Test
    public void availableWhenStylingIsInstalled() {
        Shadows.shadowOf(packageManager()).addActivityIfNotPresent(new ComponentName(PACKAGE, ACTIVITY));
        Assert.assertTrue(StylingApp.isAvailable(packageManager()));
    }

    @Test
    public void unrelatedAppsDoNotMakeItAvailable() {
        // Another app with an activity of the same class name, and the right package with another activity.
        Shadows.shadowOf(packageManager()).addActivityIfNotPresent(new ComponentName("com.example.other", ACTIVITY));
        Shadows.shadowOf(packageManager()).addActivityIfNotPresent(new ComponentName(PACKAGE, ".SomethingElse"));
        Assert.assertFalse(StylingApp.isAvailable(packageManager()));
    }
}
