package com.termux.app;

import java.io.File;
import java.io.IOException;

final class AdbShellCommand {
    static String resolveShell(File home, File prefix, String requested) throws IOException {
        File selected = null;
        if (requested != null) {
            if (requested.isEmpty() || !requested.matches("[!-~]+")) {
                throw new IOException("Invalid shell executable");
            }
            selected = requested.startsWith("/") ? new File(requested) : new File(prefix, "bin/" + requested);
        } else {
            for (String directory : new String[]{".config/termuxpp", ".config/termux", ".termux"}) {
                File candidate = new File(home, directory + "/shell");
                if (hasEntry(candidate)) {
                    selected = candidate;
                    break;
                }
            }
            if (selected == null) {
                selected = new File(prefix, "bin/bash");
                if (!selected.canExecute()) selected = new File(prefix, "bin/sh");
            }
        }
        selected = selected.getCanonicalFile();
        if (!selected.isFile() || !selected.canExecute()) throw new IOException("Selected shell is not executable");
        return selected.getPath();
    }

    private static boolean hasEntry(File file) {
        if (file.exists()) return true;
        String[] entries = file.getParentFile().list();
        if (entries != null) {
            for (String entry : entries) {
                if (file.getName().equals(entry)) return true;
            }
        }
        return false;
    }

    private AdbShellCommand() {}
}
