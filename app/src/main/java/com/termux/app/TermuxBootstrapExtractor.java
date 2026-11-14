package com.termux.app;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts the bootstrap archive into a staging directory and adapts its files with a
 * {@link TermuxBootstrapAdapter}. It never touches anything but {@code stagingDir}, which is what makes
 * it testable against a temporary directory; {@link TermuxInstaller} owns the prefix itself and moves the
 * staging directory into place only after this returned successfully.
 */
final class TermuxBootstrapExtractor {

    /** Sets the mode of a file; the app uses {@code Os.chmod}, tests use plain Java. */
    interface Chmod {
        void chmod(File file, int mode) throws IOException;
    }

    /** A symlink to create after all files exist; {@code target} is already adapted to the app. */
    static final class Symlink {
        final String target;
        final String link;

        Symlink(String target, String link) {
            this.target = target;
            this.link = link;
        }
    }

    static final class Result {
        final List<Symlink> symlinks = new ArrayList<>();
        final List<String> adaptedFiles = new ArrayList<>();
    }

    private TermuxBootstrapExtractor() {}

    @NonNull
    static Result extract(@NonNull byte[] zipBytes, @NonNull File stagingDir, @NonNull TermuxBootstrapAdapter adapter,
                          @NonNull Chmod chmod) throws IOException {
        Result result = new Result();
        byte[] buffer = new byte[8096];

        try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry zipEntry;
            while ((zipEntry = zipInput.getNextEntry()) != null) {
                String zipEntryName = zipEntry.getName();
                if (zipEntryName.equals("SYMLINKS.txt")) {
                    BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput, StandardCharsets.UTF_8));
                    String line;
                    while ((line = symlinksReader.readLine()) != null) {
                        String[] parts = line.split("\u2190");
                        if (parts.length != 2) throw new IOException("Malformed symlink line: " + line);
                        String oldPath = parts[0].replace(TermuxBootstrapAdapter.STOCK_DATA_DIR, adapter.dataDir());
                        String newPath = stagingDir.getPath() + "/" + parts[1];
                        if (!(new File(newPath).getCanonicalPath() + File.separator)
                            .startsWith(stagingDir.getCanonicalPath() + File.separator)) {
                            throw new IOException("Symlink escapes the staging directory: " + parts[1]);
                        }
                        result.symlinks.add(new Symlink(oldPath, newPath));
                        ensureDirectory(new File(newPath).getParentFile());
                    }
                    continue;
                }

                File targetFile = new File(stagingDir, zipEntryName);
                String stagingRoot = stagingDir.getCanonicalPath() + File.separator;
                if (!(targetFile.getCanonicalPath() + File.separator).startsWith(stagingRoot)) {
                    throw new IOException("Bootstrap entry escapes the staging directory: " + zipEntryName);
                }
                boolean isDirectory = zipEntry.isDirectory();
                ensureDirectory(isDirectory ? targetFile : targetFile.getParentFile());
                if (isDirectory) continue;

                ByteArrayOutputStream entryBytesStream = new ByteArrayOutputStream();
                int readBytes;
                while ((readBytes = zipInput.read(buffer)) != -1) entryBytesStream.write(buffer, 0, readBytes);
                byte[] entryBytes = entryBytesStream.toByteArray();

                byte[] adapted = adapter.adaptEntry(zipEntryName, entryBytes);
                if (adapted != entryBytes) result.adaptedFiles.add(zipEntryName);

                try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                    outStream.write(adapted);
                }
                if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec")
                    || zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods")) {
                    //noinspection OctalInteger
                    chmod.chmod(targetFile, 0700);
                }
            }
        }

        if (result.symlinks.isEmpty()) throw new IOException("No SYMLINKS.txt encountered");
        return result;
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (directory != null && !directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Cannot create directory " + directory);
        }
    }
}
