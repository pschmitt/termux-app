package com.termux.shared.termux;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.file.TermuxFileUtils;

import java.io.File;
import java.util.List;

/**
 * Resolves user config files (colors, fonts, scripts) across the TermuxPP config directories.
 *
 * The lookup order is {@code ~/.config/termuxpp} (canonical), {@code ~/.config/termux} and
 * {@code ~/.termux}. The lookup is per file: a file that is missing in the canonical directory is
 * taken from the first legacy directory that has it. New files are always meant to be written to
 * the canonical directory, which is also what is returned when no candidate exists.
 *
 * All methods take the directory list explicitly (use
 * {@link TermuxConstants#TERMUX_CONFIG_DIR_PATHS_LIST}) so that they can be unit tested.
 */
public final class TermuxConfigDirs {

    private TermuxConfigDirs() {}

    /**
     * Get the file to read for {@code relativePath}: the first candidate that is a readable regular
     * file (symlinks are followed, so symlinked fonts work) and, if {@code requireNonEmpty}, not
     * empty. If there is none, the canonical candidate is returned so that callers can treat it as
     * "missing" and so that writes go to the canonical directory.
     *
     * @param dirPaths The config directory paths in lookup order. The first one is canonical.
     * @param relativePath The path relative to a config directory, like {@code font.ttf}.
     * @param requireNonEmpty Skip empty files (for fonts).
     */
    @NonNull
    public static File resolveFile(@NonNull List<String> dirPaths, @NonNull String relativePath, boolean requireNonEmpty) {
        for (String dirPath : dirPaths) {
            File file = new File(dirPath, relativePath);
            if (file.isFile() && file.canRead() && (!requireNonEmpty || file.length() > 0))
                return file;
        }

        return new File(dirPaths.get(0), relativePath);
    }

    /**
     * Get the canonical path of a Tasker script executable. Expands the Termux path variables,
     * then, if the path is relative, resolves it against the first scripts directory in
     * {@code dirPaths} that has it; if none has it, against the canonical (first) one.
     * Absolute paths are only canonicalized.
     */
    @NonNull
    public static String resolveScriptCanonicalPath(@Nullable String path, @NonNull List<String> dirPaths) {
        String expanded = TermuxFileUtils.getExpandedTermuxPath(path == null ? "" : path);
        String prefix = dirPaths.get(0);

        if (!expanded.isEmpty() && !expanded.startsWith("/")) {
            for (String dirPath : dirPaths) {
                if (new File(dirPath, expanded).exists()) {
                    prefix = dirPath;
                    break;
                }
            }
        }

        return FileUtils.getCanonicalPath(expanded, prefix);
    }

    /**
     * Get the scripts directory that contains the canonical {@code path}, or the canonical
     * (first) directory if it is in none of them.
     */
    @NonNull
    public static String getMatchedDirPath(@Nullable String path, @NonNull List<String> dirPaths) {
        for (String dirPath : dirPaths) {
            if (FileUtils.isPathInDirPath(path, dirPath, true))
                return dirPath;
        }

        return dirPaths.get(0);
    }

}
