package com.termux.app.activities;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.Observer;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;

import com.termux.R;
import com.termux.app.settings.SettingsSearchIndex;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import dev.pschmitt.termux.shared.activity.media.AppCompatActivityUtils;
import dev.pschmitt.termux.shared.theme.NightMode;

public class SettingsActivity extends AppCompatActivity
        implements PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    private final Map<Preference, Boolean> searchVisibility = new LinkedHashMap<>();
    private PreferenceGroup searchScreen;
    private PreferenceCategory searchResults;
    private List<SettingsSearchIndex.Result> searchIndex;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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
        CharSequence title = preference.getTitle();
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.settings, destination, title == null ? null : title.toString())
                .addToBackStack(null)
                .commit();
        return true;
    }

    private void updateNavigation() {
        clearSearch();
        Fragment screen = getSupportFragmentManager().findFragmentById(R.id.settings);
        CharSequence title = null;
        if (screen instanceof PreferenceFragmentCompat) {
            PreferenceGroup preferences = ((PreferenceFragmentCompat) screen).getPreferenceScreen();
            if (preferences != null) title = preferences.getTitle();
        }
        if (title == null && screen != null) title = screen.getTag();
        setTitle(title == null ? getString(R.string.title_activity_termux_settings) : title);
        invalidateOptionsMenu();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        Fragment screen = getSupportFragmentManager().findFragmentById(R.id.settings);
        if (!(screen instanceof PreferenceFragmentCompat))
            return super.onCreateOptionsMenu(menu);

        int label = R.string.settings_search;
        SearchView search = new SearchView(this);
        search.setQueryHint(getString(label));
        MenuItem item = menu.add(label).setIcon(R.drawable.ui_ic_search).setActionView(search);
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
        item.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override public boolean onMenuItemActionExpand(MenuItem action) {
                startSearch((PreferenceFragmentCompat) screen);
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

    void startSearch(@NonNull PreferenceFragmentCompat fragment) {
        searchScreen = fragment.getPreferenceScreen();
        rememberVisibility(searchScreen);
        if (searchIndex == null) searchIndex = SettingsSearchIndex.build(this);
    }

    void filterSearch(String query) {
        if (searchScreen == null) return;
        String normalized = query == null ? "" : query.trim();
        if (normalized.isEmpty()) {
            removeSearchResults();
            restoreVisibility(false);
            return;
        }

        for (Map.Entry<Preference, Boolean> entry : searchVisibility.entrySet())
            entry.getKey().setVisible(false);
        if (searchResults == null) {
            searchResults = new PreferenceCategory(this);
            searchResults.setTitle(R.string.settings_search_results);
            searchScreen.addPreference(searchResults);
        }
        searchResults.removeAll();
        int resultNumber = 0;
        for (SettingsSearchIndex.Result result : searchIndex) {
            if (!result.matches(normalized)) continue;
            Preference item = new Preference(this);
            item.setTitle(result.title);
            item.setSummary(result.breadcrumb);
            item.setKey("settings-search-result-" + resultNumber++);
            item.setOnPreferenceClickListener(clicked -> {
                navigateToSearchResult(result);
                return true;
            });
            searchResults.addPreference(item);
        }
        if (resultNumber == 0) {
            Preference empty = new Preference(this);
            empty.setTitle(R.string.settings_search_empty);
            empty.setSelectable(false);
            searchResults.addPreference(empty);
        }
    }

    private void clearSearch() {
        removeSearchResults();
        restoreVisibility(true);
        searchScreen = null;
    }

    private void removeSearchResults() {
        if (searchResults != null && searchScreen != null) searchScreen.removePreference(searchResults);
        searchResults = null;
    }

    private void restoreVisibility(boolean clearSnapshot) {
        for (Map.Entry<Preference, Boolean> entry : searchVisibility.entrySet())
            entry.getKey().setVisible(entry.getValue());
        if (clearSnapshot) searchVisibility.clear();
    }

    private void navigateToSearchResult(@NonNull SettingsSearchIndex.Result result) {
        Fragment caller = getSupportFragmentManager().findFragmentById(R.id.settings);
        if (caller == null) return;
        Fragment destination = caller;
        for (int i = 0; i < result.fragmentPath.size(); i++) {
            String fragmentClass = result.fragmentPath.get(i);
            destination = getSupportFragmentManager().getFragmentFactory()
                .instantiate(getClassLoader(), fragmentClass);
            destination.setTargetFragment(caller, 0);
            Bundle arguments = new Bundle();
            if (i == result.fragmentPath.size() - 1 && result.preferenceKey != null)
                arguments.putString(SEARCH_FOCUS_KEY_ARGUMENT, result.preferenceKey);
            destination.setArguments(arguments);
            getSupportFragmentManager().beginTransaction()
                .replace(R.id.settings, destination, result.title)
                .addToBackStack(null)
                .commit();
            getSupportFragmentManager().executePendingTransactions();
            caller = destination;
        }
        if (result.preferenceKey != null && destination instanceof PreferenceFragmentCompat)
            focusPreference((PreferenceFragmentCompat) destination, result.preferenceKey);
        clearSearch();
        invalidateOptionsMenu();
    }

    private static final String SEARCH_FOCUS_KEY_ARGUMENT = "termuxpp.settings.search.focus-key";

    private void focusPreference(@NonNull PreferenceFragmentCompat fragment, @NonNull String key) {
        Observer<LifecycleOwner> observer = new Observer<LifecycleOwner>() {
            @Override public void onChanged(LifecycleOwner owner) {
                if (owner == null) return;
                fragment.getViewLifecycleOwnerLiveData().removeObserver(this);
                fragment.getListView().post(() -> {
                    Preference target = fragment.findPreference(key);
                    if (target == null) return;
                    fragment.scrollToPreference(key);
                    fragment.getListView().postDelayed(() -> highlightPreference(fragment, target), 180);
                });
            }
        };
        fragment.getViewLifecycleOwnerLiveData().observe(this, observer);
    }

    private void highlightPreference(@NonNull PreferenceFragmentCompat fragment, @NonNull Preference target) {
        if (!fragment.isAdded()) return;
        Drawable originalIcon = target.getIcon();
        target.setIcon(R.drawable.ui_ic_search);
        mainHandler.postDelayed(() -> {
            if (fragment.isAdded()) target.setIcon(originalIcon);
        }, 1200);
    }

    public static class RootPreferencesFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            Context context = getContext();
            if (context == null) return;

            setPreferencesFromResource(R.xml.root_preferences, rootKey);
        }
    }

}
