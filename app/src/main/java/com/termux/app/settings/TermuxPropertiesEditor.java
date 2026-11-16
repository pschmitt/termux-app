package com.termux.app.settings;

import android.util.AtomicFile;

import dev.pschmitt.termux.shared.termux.TermuxConstants;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import dev.pschmitt.termux.shared.file.filesystem.FileType;

import java.io.InputStreamReader;
import java.io.StringReader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

public final class TermuxPropertiesEditor {
    private final File target;
    private final List<String> paths;

    public TermuxPropertiesEditor() {
        this(TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE, TermuxConstants.TERMUX_PROPERTIES_FILE_PATHS_LIST);
    }

    public TermuxPropertiesEditor(File target, List<String> paths) {
        this.target = target;
        this.paths = paths;
    }

    private byte[] source() throws IOException {
        for (String path : paths) {
            File file = new File(path);
            FileType type = fileType(path);
            if (type != FileType.REGULAR && type != FileType.NO_EXIST) continue;
            if (file.equals(target) && (type == FileType.REGULAR || new File(path + ".bak").isFile())) {
                validateWriteTargets();
                return new AtomicFile(file).readFully();
            }
            if (type == FileType.REGULAR && file.canRead()) {
                try (FileInputStream input = new FileInputStream(file)) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                    return bytes.toByteArray();
                }
            }
        }
        return new byte[0];
    }

    public synchronized Properties read() throws IOException {
        Properties properties = new Properties();
        properties.load(new InputStreamReader(new ByteArrayInputStream(source()), StandardCharsets.UTF_8));
        return properties;
    }

    private static byte[] withoutKey(byte[] original, String key) throws IOException {
        String document = new String(original, StandardCharsets.UTF_8);
        StringBuilder kept = new StringBuilder();
        StringBuilder record = new StringBuilder();
        for (String line : document.split("(?<=\\n)|(?<=\\r)(?!\\n)")) {
            record.append(line);
            String content = line.replaceFirst("[\\r\\n]+$", "");
            String trimmed = content.trim();
            boolean comment = record.length() == line.length() && (trimmed.startsWith("#") || trimmed.startsWith("!"));
            int slashes = 0;
            for (int i = content.length() - 1; i >= 0 && content.charAt(i) == '\\'; i--) slashes++;
            if (!comment && (slashes & 1) != 0) continue;
            Properties entry = new Properties();
            entry.load(new StringReader(record.toString()));
            if (!entry.containsKey(key)) kept.append(record);
            record.setLength(0);
        }
        if (record.length() != 0) {
            Properties entry = new Properties();
            entry.load(new StringReader(record.toString()));
            if (!entry.containsKey(key)) kept.append(record);
        }
        return kept.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static FileType fileType(String path) throws IOException {
        try {
            int mode = Os.lstat(path).st_mode;
            if (OsConstants.S_ISREG(mode)) return FileType.REGULAR;
            return FileType.UNKNOWN;
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.ENOENT) return FileType.NO_EXIST;
            throw new IOException("Cannot inspect properties file", e);
        }
    }

    private void validateWriteTargets() throws IOException {
        for (String suffix : new String[] {"", ".bak", ".new"}) {
            FileType type = fileType(target.toString() + suffix);
            if (type != FileType.NO_EXIST && type != FileType.REGULAR)
                throw new IOException("Properties write target is not a regular file");
        }
    }

    public synchronized void set(String key, String value) throws IOException {
        validateWriteTargets();
        byte[] original = source();
        Properties existing = new Properties();
        existing.load(new InputStreamReader(new ByteArrayInputStream(original), StandardCharsets.UTF_8));
        Properties change = new Properties();
        if (value != null) change.setProperty(key, value);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        change.store(encoded, null);
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create properties directory");
        AtomicFile atomic = new AtomicFile(target);
        FileOutputStream output = null;
        try {
            output = atomic.startWrite();
            output.write(withoutKey(original, key));
            // A blank line terminates a possible continuation at the end of the original file.
            output.write('\n');
            output.write('\n');
            if (value != null) {
                String property = new String(encoded.toByteArray(), StandardCharsets.ISO_8859_1)
                    .replaceFirst("^#[^\\r\\n]*[\\r\\n]+", "");
                output.write(property.getBytes(StandardCharsets.ISO_8859_1));
            }
            atomic.finishWrite(output);
        } catch (IOException e) {
            if (output != null) atomic.failWrite(output);
            throw e;
        }
    }
}
