package com.termux.app.fragments.settings.termux;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;

import com.termux.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.nix.TermuxNix;
import dev.pschmitt.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

/** Controls services supervised by the managed Nix guest. */
@Keep
public class ServicesPreferencesFragment extends PreferenceFragmentCompat {

    private static final int OUTPUT_LIMIT = 64 * 1024;
    private static final String SERVICE_NAME_PATTERN = "[A-Za-z0-9_.@-]+";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Preference statusPreference;
    private PreferenceCategory servicesCategory;
    private Preference refreshPreference;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.termux_services_preferences, rootKey);
        statusPreference = findPreference("services_status");
        servicesCategory = findPreference("services_list");
        refreshPreference = findPreference("services_refresh");
        if (refreshPreference != null) {
            refreshPreference.setOnPreferenceClickListener(preference -> {
                refreshServices();
                return true;
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshServices();
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void refreshServices() {
        if (statusPreference == null || servicesCategory == null) return;
        servicesCategory.removeAll();
        if (!TermuxNix.isProvisioned()) {
            setStatus(R.string.nix_services_not_available);
            return;
        }
        if (!TermuxNix.isReady()) {
            setStatus(R.string.nix_services_guest_not_ready);
            return;
        }
        setStatus(R.string.nix_services_loading);
        executor.execute(() -> {
            CommandResult result = runGuestCommand("svc", "--json", "list");
            List<Service> services = null;
            String error = result.output;
            if (result.exitCode == 0) {
                try {
                    services = parseServices(result.output);
                } catch (JSONException e) {
                    error = e.getMessage();
                }
            }
            List<Service> loaded = services;
            String failure = error;
            mainHandler.post(() -> {
                if (!isAdded() || servicesCategory == null) return;
                if (loaded == null) {
                    setStatus(getString(R.string.nix_services_load_error, concise(failure)));
                    return;
                }
                setStatus(getResources().getQuantityString(R.plurals.nix_services_count,
                    loaded.size(), loaded.size()));
                for (Service service : loaded) addServicePreference(service);
            });
        });
    }

    private void addServicePreference(@NonNull Service service) {
        Preference preference = new Preference(requireContext());
        preference.setTitle(service.name);
        preference.setSummary(getString(R.string.nix_services_service_summary,
            service.status, service.enabled ? getString(R.string.nix_services_enabled)
                : getString(R.string.nix_services_disabled)));
        preference.setIcon(R.drawable.ui_ic_settings);
        preference.setOnPreferenceClickListener(clicked -> {
            showServiceActions(service);
            return true;
        });
        servicesCategory.addPreference(preference);
    }

    private void showServiceActions(@NonNull Service service) {
        Context context = requireContext();
        String[] actions = {
            getString(R.string.nix_services_start),
            getString(R.string.nix_services_stop),
            getString(R.string.nix_services_restart),
            getString(R.string.nix_services_logs)
        };
        new AlertDialog.Builder(context)
            .setTitle(service.name)
            .setItems(actions, (dialog, which) -> {
                String action = new String[]{"start", "stop", "restart", "logs"}[which];
                runServiceAction(service, action);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void runServiceAction(@NonNull Service service, @NonNull String action) {
        setStatus(getString(R.string.nix_services_action_running, action, service.name));
        executor.execute(() -> {
            CommandResult result = runGuestCommand("svc", action, service.name);
            mainHandler.post(() -> {
                if (!isAdded()) return;
                if (result.exitCode != 0) {
                    showTextDialog(getString(R.string.nix_services_action_failed, action),
                        concise(result.output));
                } else if ("logs".equals(action)) {
                    showTextDialog(getString(R.string.nix_services_logs_title, service.name),
                        result.output);
                } else {
                    Toast.makeText(requireContext(),
                        getString(R.string.nix_services_action_done, action, service.name),
                        Toast.LENGTH_SHORT).show();
                }
                refreshServices();
            });
        });
    }

    @NonNull
    private CommandResult runGuestCommand(@NonNull String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(TermuxNix.NIXPP_PATH);
        command.add("enter");
        command.add("--");
        for (String argument : arguments) command.add(argument);
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().clear();
            Context context = getContext();
            if (context == null) return new CommandResult(1, "");
            builder.environment().putAll(new TermuxShellEnvironment().getEnvironment(context, false));
            builder.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
            builder.redirectErrorStream(true);
            process = builder.start();
            process.getOutputStream().close();
            String output = readBounded(process.getInputStream(), OUTPUT_LIMIT);
            int exit = process.waitFor();
            return new CommandResult(exit, output);
        } catch (IOException e) {
            return new CommandResult(1, e.getMessage());
        } catch (InterruptedException e) {
            if (process != null) process.destroyForcibly();
            Thread.currentThread().interrupt();
            return new CommandResult(1, "Service command was interrupted.");
        }
    }

    @NonNull
    static List<Service> parseServices(@NonNull String json) throws JSONException {
        JSONArray array = new JSONArray(json);
        List<Service> services = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            String name = item.optString("name", "");
            if (!name.matches(SERVICE_NAME_PATTERN)) {
                throw new JSONException("Invalid service name in guest response");
            }
            services.add(new Service(name,
                item.optString("status", "unknown"),
                item.optBoolean("enabled", false)));
        }
        return services;
    }

    @NonNull
    private static String readBounded(@NonNull InputStream input, int maximum) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[2048];
            int count;
            boolean truncated = false;
            while ((count = reader.read(buffer)) != -1) {
                int remaining = maximum - output.length();
                if (remaining > 0) output.append(buffer, 0, Math.min(count, remaining));
                if (count > remaining) truncated = true;
            }
            if (truncated) output.append("\n").append("…");
        }
        return output.toString();
    }

    private void showTextDialog(@NonNull String title, @NonNull String text) {
        TextView view = new TextView(requireContext());
        view.setText(TextUtils.isEmpty(text) ? getString(R.string.nix_services_no_output) : text);
        view.setTextIsSelectable(true);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        view.setPadding(padding, padding, padding, padding);
        ScrollView scrollView = new ScrollView(requireContext());
        scrollView.addView(view);
        new AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private void setStatus(@NonNull CharSequence summary) {
        if (statusPreference != null) statusPreference.setSummary(summary);
    }

    private void setStatus(int summary) {
        if (statusPreference != null) statusPreference.setSummary(summary);
    }

    @NonNull
    private static String concise(@Nullable String text) {
        if (TextUtils.isEmpty(text)) return "Unknown error";
        String trimmed = text.trim();
        int lineEnd = trimmed.indexOf('\n');
        if (lineEnd >= 0) trimmed = trimmed.substring(0, lineEnd);
        return trimmed.length() > 180 ? trimmed.substring(0, 177) + "…" : trimmed;
    }

    static final class Service {
        final String name;
        final String status;
        final boolean enabled;

        Service(@NonNull String name, @NonNull String status, boolean enabled) {
            this.name = name;
            this.status = status;
            this.enabled = enabled;
        }
    }

    private static final class CommandResult {
        final int exitCode;
        @Nullable final String output;

        CommandResult(int exitCode, @Nullable String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }
}
