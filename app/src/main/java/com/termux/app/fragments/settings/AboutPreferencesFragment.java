package com.termux.app.fragments.settings;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.models.UserAction;
import com.termux.app.terminal.ReportIssueRequest;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.android.AndroidUtils;
import com.termux.shared.file.FileUtils;
import com.termux.shared.models.ReportInfo;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;

@Keep
public class AboutPreferencesFragment extends PreferenceFragmentCompat {

    @Nullable private CharSequence previousTitle;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        setPreferencesFromResource(R.xml.about_preferences, rootKey);
        Preference reportIssue = findPreference("report_issue");
        if (reportIssue != null) {
            reportIssue.setOnPreferenceClickListener(preference -> {
                ReportIssueRequest.request();
                Intent intent = TermuxActivity.newInstance(context);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                context.startActivity(intent);
                return true;
            });
        }

        Preference aboutInformation = findPreference("about_information");
        if (aboutInformation != null) {
            aboutInformation.setOnPreferenceClickListener(preference -> {
                showAboutInformation(context);
                return true;
            });
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        Activity activity = getActivity();
        if (activity != null) {
            previousTitle = activity.getTitle();
            activity.setTitle(R.string.about_preference_title);
        }
    }

    @Override
    public void onStop() {
        Activity activity = getActivity();
        if (activity != null && previousTitle != null) activity.setTitle(previousTitle);
        super.onStop();
    }

    private void showAboutInformation(Context context) {
        new Thread(() -> {
            StringBuilder aboutString = new StringBuilder();
            aboutString.append(TermuxUtils.getAppInfoMarkdownString(context, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES));
            aboutString.append("\n\n").append(AndroidUtils.getDeviceInfoMarkdownString(context, true));
            aboutString.append("\n\n").append(TermuxUtils.getImportantLinksMarkdownString(context));

            String userActionName = UserAction.ABOUT.getName();
            ReportInfo reportInfo = new ReportInfo(userActionName,
                TermuxConstants.TERMUX_APP.TERMUX_SETTINGS_ACTIVITY_NAME,
                context.getString(R.string.about_preference_title));
            reportInfo.setReportString(aboutString.toString());
            reportInfo.setReportSaveFileLabelAndPath(userActionName,
                Environment.getExternalStorageDirectory() + "/" +
                    FileUtils.sanitizeFileName(TermuxConstants.TERMUX_APP_NAME + "-" + userActionName + ".log", true, true));
            ReportActivity.startReportActivity(context, reportInfo);
        }).start();
    }
}
