package com.termux.app.activities;

import android.content.Context;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;

import com.termux.R;
import com.termux.app.fragments.settings.termux.PropertiesPreferencesFragment;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import dev.pschmitt.termux.shared.interact.ShareUtils;
import dev.pschmitt.termux.shared.android.PackageUtils;
import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.TermuxUtils;
import dev.pschmitt.termux.shared.activity.media.AppCompatActivityUtils;
import dev.pschmitt.termux.shared.theme.NightMode;

public class SettingsActivity extends AppCompatActivity
        implements PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    private final Map<Preference, Boolean> searchVisibility = new LinkedHashMap<>();
    private PreferenceGroup searchScreen;
    private Preference emptyResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);

        setContentView(R.layout.activity_settings);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings, new RootPreferencesFragment())
                .runOnCommit(this::updateNavigation)
                .commit();
        }

        AppCompatActivityUtils.setToolbar(this, dev.pschmitt.termux.shared.R.id.toolbar);
        AppCompatActivityUtils.setShowBackButtonInActionBar(this, true);
        getSupportFragmentManager().addOnBackStackChangedListener(this::updateNavigation);
        updateNavigation();
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    public boolean onPreferenceStartFragment(@NonNull PreferenceFragmentCompat caller,
                                             @NonNull Preference preference) {
        String fragmentClass = preference.getFragment();
        if (fragmentClass == null) return false;
        Fragment destination = getSupportFragmentManager().getFragmentFactory()
                .instantiate(getClassLoader(), fragmentClass);
        destination.setArguments(preference.getExtras());
        // Preserve the caller relationship for preference fragments expecting a target fragment.
        destination.setTargetFragment(caller, 0);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.settings, destination, preference.getTitle().toString())
                .addToBackStack(null)
                .commit();
        return true;
    }

    private void updateNavigation() {
        clearSearch();
        Fragment screen = getSupportFragmentManager().findFragmentById(R.id.settings);
        String title = screen == null ? null : screen.getTag();
        setTitle(title == null ? getString(R.string.title_activity_termux_settings) : title);
        invalidateOptionsMenu();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        Fragment screen = getSupportFragmentManager().findFragmentById(R.id.settings);
        if (!(screen instanceof RootPreferencesFragment) && !(screen instanceof PropertiesPreferencesFragment))
            return super.onCreateOptionsMenu(menu);

        int label = screen instanceof PropertiesPreferencesFragment
                ? R.string.settings_search_properties : R.string.settings_search;
        SearchView search = new SearchView(this);
        search.setQueryHint(getString(label));
        MenuItem item = menu.add(label).setIcon(R.drawable.ui_ic_search).setActionView(search);
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
        item.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override public boolean onMenuItemActionExpand(MenuItem action) {
                searchScreen = ((PreferenceFragmentCompat) screen).getPreferenceScreen();
                rememberVisibility(searchScreen);
                return true;
            }

            @Override public boolean onMenuItemActionCollapse(MenuItem action) {
                clearSearch();
                return true;
            }
        });
        search.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override public boolean onQueryTextSubmit(String query) {
                search.clearFocus();
                return true;
            }

            @Override public boolean onQueryTextChange(String query) {
                filterSearch(query);
                return true;
            }
        });
        return true;
    }

    private void rememberVisibility(PreferenceGroup group) {
        if (group == null) return;
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference preference = group.getPreference(i);
            searchVisibility.put(preference, preference.isVisible());
            if (preference instanceof PreferenceGroup) rememberVisibility((PreferenceGroup) preference);
        }
    }

    private void filterSearch(String query) {
        if (searchScreen == null) return;
        removeEmptyResult();
        String terms = normalizeQuery(query);
        if (!filterGroup(searchScreen, terms) && !terms.isEmpty()) {
            emptyResult = new Preference(this);
            emptyResult.setTitle(R.string.settings_search_empty);
            emptyResult.setSelectable(false);
            searchScreen.addPreference(emptyResult);
        }
    }

    private boolean filterGroup(PreferenceGroup group, String query) {
        boolean anyVisible = false;
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference preference = group.getPreference(i);
            boolean matches = preference instanceof PreferenceGroup
                    ? filterGroup((PreferenceGroup) preference, query)
                    : matchesSearch(preference, query);
            boolean visible = Boolean.TRUE.equals(searchVisibility.get(preference))
                    && (query.isEmpty() || matches);
            preference.setVisible(visible);
            anyVisible |= visible;
        }
        return anyVisible;
    }

    private static boolean matchesSearch(Preference preference, String query) {
        String text = normalizeQuery(preference.getTitle() + " " + preference.getKey() + " "
                + (preference.getFragment() == null ? "" : preference.getSummary()));
        for (String term : query.split(" ")) {
            if (!text.contains(term)) return false;
        }
        return true;
    }

    private static String normalizeQuery(String text) {
        return text.toLowerCase(Locale.ROOT).replace('-', ' ').replace('_', ' ').trim();
    }

    private void removeEmptyResult() {
        if (emptyResult != null) {
            searchScreen.removePreference(emptyResult);
            emptyResult = null;
        }
    }

    private void clearSearch() {
        removeEmptyResult();
        for (Map.Entry<Preference, Boolean> entry : searchVisibility.entrySet())
            entry.getKey().setVisible(entry.getValue());
        searchVisibility.clear();
        searchScreen = null;
    }

    public static class RootPreferencesFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            Context context = getContext();
            if (context == null) return;

            setPreferencesFromResource(R.xml.root_preferences, rootKey);

            configureDonatePreference(context);
        }

        private void configureDonatePreference(@NonNull Context context) {
            Preference donatePreference = findPreference("donate");
            if (donatePreference != null) {
                String signingCertificateSHA256Digest = PackageUtils.getSigningCertificateSHA256DigestForPackage(context);
                if (signingCertificateSHA256Digest != null) {
                    // If APK is a Google Playstore release, then do not show the donation link
                    // since Termux isn't exempted from the playstore policy donation links restriction
                    // Check Fund solicitations: https://pay.google.com/intl/en_in/about/policy/
                    String apkRelease = TermuxUtils.getAPKRelease(signingCertificateSHA256Digest);
                    if (apkRelease == null || apkRelease.equals(TermuxConstants.APK_RELEASE_GOOGLE_PLAYSTORE_SIGNING_CERTIFICATE_SHA256_DIGEST)) {
                        donatePreference.setVisible(false);
                        return;
                    } else {
                        donatePreference.setVisible(true);
                    }
                }

                donatePreference.setOnPreferenceClickListener(preference -> {
                    ShareUtils.openUrl(context, TermuxConstants.TERMUX_DONATE_URL);
                    return true;
                });
            }
        }
    }

}
