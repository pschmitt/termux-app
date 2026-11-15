package com.termux.app.terminal;

import android.system.Os;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/** What the terminal menu shows about the process of a session, read from {@code /proc}. */
public final class ProcessInfo {

    private ProcessInfo() {}

    /** The name of the process, such as {@code zsh}, or null if it is not known. */
    @Nullable
    public static String readName(int pid) {
        if (pid <= 0) return null;
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/" + pid + "/comm"))) {
            String line = reader.readLine();
            return line == null || line.trim().isEmpty() ? null : line.trim();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The working directory of the process, or null if it is not known. */
    @Nullable
    public static String readCwd(int pid) {
        if (pid <= 0) return null;
        try {
            return Os.readlink("/proc/" + pid + "/cwd");
        } catch (Exception e) {
            return null;
        }
    }

    /** Show a path under the home directory as {@code ~/...}, like a shell prompt does. */
    @NonNull
    public static String abbreviateHome(@NonNull String path, @Nullable String home) {
        if (home == null || home.isEmpty()) return path;
        String base = home.endsWith("/") ? home.substring(0, home.length() - 1) : home;
        if (path.equals(base)) return "~";
        if (path.startsWith(base + "/")) return "~" + path.substring(base.length());
        return path;
    }
}
