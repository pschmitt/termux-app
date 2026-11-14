package com.termux.app.activities;

import android.content.Intent;

import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminal.ReportIssueRequest;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

@RunWith(RobolectricTestRunner.class)
public class SettingsReportIssueTest {

    private static PreferenceFragmentCompat rootFragment(SettingsActivity activity) {
        Fragment fragment = activity.getSupportFragmentManager().findFragmentById(R.id.settings);
        Assert.assertTrue(fragment instanceof PreferenceFragmentCompat);
        return (PreferenceFragmentCompat) fragment;
    }

    @Test
    public void reportIssueIsInSettingsOnceNextToAbout() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        PreferenceFragmentCompat fragment = rootFragment(activity);

        Preference reportIssue = fragment.findPreference("report_issue");
        Assert.assertNotNull(reportIssue);
        Assert.assertTrue(reportIssue.isVisible());
        Assert.assertEquals(activity.getString(R.string.action_report_issue), reportIssue.getTitle().toString());
        Assert.assertNotNull("Updates is still there", fragment.findPreference("updates"));
        Assert.assertNotNull(fragment.findPreference("about"));

        int found = 0;
        for (int i = 0; i < fragment.getPreferenceScreen().getPreferenceCount(); i++) {
            CharSequence title = fragment.getPreferenceScreen().getPreference(i).getTitle();
            if (title != null && title.toString().equals(activity.getString(R.string.action_report_issue))) found++;
        }
        Assert.assertEquals("No duplicate entries", 1, found);
    }

    @Test
    public void clickingReportIssueAsksTheTerminalToRunTheExistingFlow() {
        ReportIssueRequest.consume();
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        Preference reportIssue = rootFragment(activity).findPreference("report_issue");
        Assert.assertNotNull(reportIssue);

        Assert.assertTrue(reportIssue.getOnPreferenceClickListener().onPreferenceClick(reportIssue));

        Intent started = Shadows.shadowOf(activity).getNextStartedActivity();
        Assert.assertNotNull(started);
        Assert.assertEquals(TermuxActivity.class.getName(), started.getComponent().getClassName());
        Assert.assertNotEquals(0, started.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // The terminal gets exactly one request, and it is the one that runs the consent dialog first.
        Assert.assertTrue(ReportIssueRequest.consume());
        Assert.assertFalse(ReportIssueRequest.consume());
    }

}
