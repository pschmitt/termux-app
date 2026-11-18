package com.termux.app;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import dev.pschmitt.termux.shared.logger.Logger;
import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.nix.TermuxNix;
import dev.pschmitt.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

/**
 * Sets up the Nix runtime during the first-start bootstrap with {@code nixpp --json init} and turns
 * its JSON progress lines into updates of the bootstrap dialog. Not fatal: an unfinished setup is
 * resumed by {@code nixpp login} in the first session.
 */
final class TermuxNixSetup {

    private static final String LOG_TAG = "TermuxNixSetup";
    private static final int STDERR_TAIL_LINES = 20;

    /** One update of the dialog. {@code total == 0} means indeterminate progress. */
    static final class Status {
        enum Stage { EXTRACT, PREPARED, PACKAGE, CHECK, READY }

        final Stage stage;
        final int index;
        final int total;
        @Nullable final String name;
        @Nullable final String detail;

        Status(Stage stage, int index, int total, @Nullable String name, @Nullable String detail) {
            this.stage = stage;
            this.index = index;
            this.total = total;
            this.name = name;
            this.detail = detail;
        }
    }

    interface Listener {
        void onStatus(@NonNull Status status);
    }

    /** Folds nixpp's progress events into dialog updates; keeps the current package for "copy" events. */
    static final class Tracker {
        private Status current;

        /** The update for one line of nixpp's stdout, or {@code null} when the dialog stays as it is. */
        @Nullable
        Status accept(@NonNull String line) {
            JSONObject event;
            try {
                event = new JSONObject(line);
            } catch (JSONException e) {
                return null;
            }
            switch (event.optString("event")) {
                case "extract":
                    return current = new Status(Status.Stage.EXTRACT, 0, 0, null, null);
                case "prepared":
                    return current = new Status(Status.Stage.PREPARED, 0, 0, null, null);
                case "package":
                    return current = new Status(Status.Stage.PACKAGE, event.optInt("index"),
                        event.optInt("total"), event.optString("name", null), null);
                case "copy":
                    if (current == null || current.stage != Status.Stage.PACKAGE) return null;
                    return new Status(Status.Stage.PACKAGE, current.index, current.total, current.name,
                        event.optString("name", null));
                case "check":
                    return current = new Status(Status.Stage.CHECK, 0, 0, null, null);
                case "ready":
                    return current = new Status(Status.Stage.READY, 0, 0, null, null);
                default:
                    return null;
            }
        }
    }

    private TermuxNixSetup() {}

    /** Runs the setup and returns whether the runtime is ready. Never throws. */
    static boolean run(@NonNull Context context, @NonNull String user, @NonNull Listener listener) {
        List<String> command = new ArrayList<>();
        command.add(TermuxNix.NIXPP_PATH);
        for (String argument : TermuxNix.initArguments(user)) command.add(argument);
        Deque<String> stderrTail = new ArrayDeque<>();
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            // The same environment as a Termux shell: HOME, PREFIX and the pkg-redirect preload.
            builder.environment().clear();
            builder.environment().putAll(new TermuxShellEnvironment().getEnvironment(context, false));
            builder.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
            Logger.logInfo(LOG_TAG, "Setting up the Nix runtime for the guest user \"" + user + "\".");
            Process process = builder.start();
            process.getOutputStream().close();
            Thread stderr = new Thread(() -> drain(process.getErrorStream(), stderrTail), "termuxpp-nix-stderr");
            stderr.start();
            Tracker tracker = new Tracker();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Status status = tracker.accept(line);
                    if (status != null) listener.onStatus(status);
                    else Logger.logVerbose(LOG_TAG, line);
                }
            }
            int exit = process.waitFor();
            stderr.join();
            if (exit == 0 && TermuxNix.isReady()) {
                Logger.logInfo(LOG_TAG, "The Nix runtime is ready.");
                return true;
            }
            Logger.logError(LOG_TAG, "nixpp init exited with " + exit + "; the first session resumes the setup:\n"
                + String.join("\n", tail(stderrTail)));
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Logger.logError(LOG_TAG, "Could not run nixpp init; the first session resumes the setup: " + e);
        }
        return false;
    }

    /** The bootstrap's second stage (maintainer scripts), which locks itself so it runs at most once. */
    static final String SECOND_STAGE_PATH = TermuxConstants.TERMUX_PREFIX_DIR_PATH
        + "/etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh";

    /** Runs the bootstrap second stage when it never ran; logs its output. Never throws. */
    static void runSecondStage(@NonNull Context context) {
        File script = new File(SECOND_STAGE_PATH);
        if (!script.isFile() || new File(SECOND_STAGE_PATH + ".lock").exists()) return;
        Deque<String> output = new ArrayDeque<>();
        try {
            ProcessBuilder builder = new ProcessBuilder(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash",
                script.getAbsolutePath());
            builder.environment().clear();
            builder.environment().putAll(new TermuxShellEnvironment().getEnvironment(context, false));
            builder.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
            builder.redirectErrorStream(true);
            Process process = builder.start();
            process.getOutputStream().close();
            drain(process.getInputStream(), output);
            int exit = process.waitFor();
            String log = String.join("\n", tail(output));
            if (exit == 0) Logger.logInfo(LOG_TAG, "The bootstrap second stage ran:\n" + log);
            else Logger.logError(LOG_TAG, "The bootstrap second stage failed with exit " + exit + ":\n" + log);
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Logger.logError(LOG_TAG, "Could not run the bootstrap second stage: " + e);
        }
    }

    private static void drain(InputStream stream, Deque<String> tail) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (tail) {
                    if (tail.size() == STDERR_TAIL_LINES) tail.removeFirst();
                    tail.addLast(line);
                }
            }
        } catch (IOException ignored) {
            // The process ended.
        }
    }

    private static List<String> tail(Deque<String> tail) {
        synchronized (tail) {
            return new ArrayList<>(tail);
        }
    }
}
