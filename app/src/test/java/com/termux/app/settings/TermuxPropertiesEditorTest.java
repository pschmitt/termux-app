package com.termux.app.settings;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = TermuxPropertiesEditorTest.HostOs.class)
public class TermuxPropertiesEditorTest {
    @org.robolectric.annotation.Implements(android.system.Os.class)
    public static class HostOs {
        // Robolectric's legacy lstat shadow does not distinguish symlinks or missing files.
        @org.robolectric.annotation.Implementation
        protected static android.system.StructStat lstat(String path) throws android.system.ErrnoException {
            java.nio.file.Path file = new File(path).toPath();
            if (!Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                throw new android.system.ErrnoException("lstat", android.system.OsConstants.ENOENT);
            int mode = Files.isSymbolicLink(file) ? android.system.OsConstants.S_IFLNK
                : Files.isRegularFile(file) ? android.system.OsConstants.S_IFREG : android.system.OsConstants.S_IFDIR;
            return new android.system.StructStat(0, 0, mode, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void legacyIsCopiedWithoutChangingOriginal() throws Exception {
        File legacy = temp.newFile("termux.properties");
        String original = "# keep this comment\nunknown=value\nfullscreen=false\n";
        Files.write(legacy.toPath(), original.getBytes(StandardCharsets.ISO_8859_1));
        File target = new File(temp.getRoot(), "new/termuxpp.properties");
        TermuxPropertiesEditor editor = new TermuxPropertiesEditor(target, Arrays.asList(target.toString(), legacy.toString()));
        assertEquals("false", editor.read().getProperty("fullscreen"));
        editor.set("fullscreen", "true");
        assertEquals("true", editor.read().getProperty("fullscreen"));
        assertEquals("value", editor.read().getProperty("unknown"));
        assertTrue(new String(Files.readAllBytes(target.toPath()), StandardCharsets.ISO_8859_1).contains("# keep this comment"));
        assertEquals(original, new String(Files.readAllBytes(legacy.toPath()), StandardCharsets.ISO_8859_1));
        editor.set("fullscreen", null);
        assertNull(editor.read().getProperty("fullscreen"));
    }

    @Test public void escapedAndDuplicateKeysAndContinuationsAreReplaced() throws Exception {
        File target = temp.newFile("termuxpp.properties");
        Files.write(target.toPath(), ("# comment ending in \\\nfullscreen = false\nfullscr\\u0065en=true\nextra-keys=one\\\n two\nunknown=keep\n").getBytes(StandardCharsets.ISO_8859_1));
        TermuxPropertiesEditor editor = new TermuxPropertiesEditor(target, Arrays.asList(target.toString()));
        editor.set("fullscreen", null);
        assertNull(editor.read().getProperty("fullscreen"));
        editor.set("extra-keys", "Unicode \u2603\nnext");
        assertEquals("Unicode \u2603\nnext", editor.read().getProperty("extra-keys"));
        assertEquals("keep", editor.read().getProperty("unknown"));
    }

    @Test public void interruptedAtomicWriteRecoversBeforeEditing() throws Exception {
        File target = new File(temp.getRoot(), "termuxpp.properties");
        File backup = new File(target + ".bak");
        Files.write(backup.toPath(), "unknown=recovered\n".getBytes(StandardCharsets.ISO_8859_1));
        TermuxPropertiesEditor editor = new TermuxPropertiesEditor(target, Arrays.asList(target.toString()));
        assertEquals("recovered", editor.read().getProperty("unknown"));
        editor.set("fullscreen", "true");
        assertEquals("recovered", editor.read().getProperty("unknown"));
        assertEquals("true", editor.read().getProperty("fullscreen"));
    }

    @Test public void literalUtf8IsPreserved() throws Exception {
        File target = temp.newFile("termuxpp.properties");
        String original = "# Grüße ☃\nunknown=日本語\n";
        Files.write(target.toPath(), original.getBytes(StandardCharsets.UTF_8));
        TermuxPropertiesEditor editor = new TermuxPropertiesEditor(target, Arrays.asList(target.toString()));
        assertEquals("日本語", editor.read().getProperty("unknown"));
        editor.set("fullscreen", "true");
        assertEquals("日本語", editor.read().getProperty("unknown"));
        assertTrue(new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8).startsWith(original));
    }

    @Test public void symlinkSourcesAreSkippedAndWriteTargetsRejected() throws Exception {
        File real = temp.newFile("real.properties");
        byte[] original = "unknown=untouched\n".getBytes(StandardCharsets.UTF_8);
        Files.write(real.toPath(), original);
        File link = new File(temp.getRoot(), "termuxpp.properties");
        Files.createSymbolicLink(link.toPath(), real.toPath());
        TermuxPropertiesEditor editor = new TermuxPropertiesEditor(link, Arrays.asList(link.toString(), real.toString()));
        assertEquals("untouched", editor.read().getProperty("unknown"));
        assertThrows(java.io.IOException.class, () -> editor.set("fullscreen", "true"));
        assertArrayEquals(original, Files.readAllBytes(real.toPath()));
        File target = new File(temp.getRoot(), "safe.properties");
        TermuxPropertiesEditor fallback = new TermuxPropertiesEditor(target, Arrays.asList(link.toString(), target.toString()));
        assertNull(fallback.read().getProperty("unknown"));
    }

    @Test public void malformedFileIsNotOverwritten() throws Exception {
        File target = temp.newFile("termuxpp.properties");
        byte[] original = "broken=\\uXXXX\n".getBytes(StandardCharsets.ISO_8859_1);
        Files.write(target.toPath(), original);
        TermuxPropertiesEditor editor = new TermuxPropertiesEditor(target, Arrays.asList(target.toString()));
        assertThrows(IllegalArgumentException.class, () -> editor.set("fullscreen", "true"));
        assertArrayEquals(original, Files.readAllBytes(target.toPath()));
    }
}
