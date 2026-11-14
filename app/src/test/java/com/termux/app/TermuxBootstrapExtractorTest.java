package com.termux.app;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Runs the real extraction and rewrite code against a temporary staging directory, with a sentinel
 * "prefix" next to it that must never be touched. This is the sandbox style of test: it does not install
 * anything on a device and does not test a fresh device install.
 */
public class TermuxBootstrapExtractorTest {

    private static final String STOCK_DIR = "/data/data/com.termux";
    private static final String FORK_DIR = "/data/data/dev.pschmitt.termux";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File staging;
    private File prefix;
    private File sentinel;
    private final List<String> chmods = new ArrayList<>();
    private final TermuxBootstrapExtractor.Chmod recordingChmod = (file, mode) -> chmods.add(file.getName() + ":" + Integer.toOctalString(mode));

    @Before
    public void setUp() throws IOException {
        staging = new File(temp.getRoot(), "usr-staging");
        prefix = temp.newFolder("usr");
        sentinel = new File(prefix, "important");
        Files.write(sentinel.toPath(), "do not touch".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] b(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] u(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static final byte[] ELF = new byte[] {0x7F, 'E', 'L', 'F', 2, 1, 1, 0, 'x', '/', 'd', 'a', 't', 'a', '/', 'd', 'a',
        't', 'a', '/', 'c', 'o', 'm', '.', 't', 'e', 'r', 'm', 'u', 'x', 0};

    private static Map<String, byte[]> bootstrapFiles() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("bin/login", b(TermuxHelperRewritesTest.stockFile("bin/login")));
        files.put("bin/termux-open", b(TermuxHelperRewritesTest.stockHelper("termux-open")));
        files.put("bin/termux-reset", b(TermuxHelperRewritesTest.stockHelper("termux-reset")));
        files.put("bin/pkg", b(TermuxHelperRewritesTest.stockFile("bin/pkg")));
        files.put("etc/termux-login.sh", b(TermuxHelperRewritesTest.stockFile("etc/termux-login.sh")));
        files.put("etc/profile", u("export PREFIX=" + STOCK_DIR + "/files/usr\n# café ✓\n"));
        files.put("etc/unknown.conf", u("nothing to see here\n"));
        files.put("var/lib/dpkg/info/foo.list", u(STOCK_DIR + "/files/usr/bin/foo\n" + STOCK_DIR + "/files/usr/share\n"));
        files.put("lib/libfoo.so", ELF);
        files.put("libexec/tool", ELF);
        files.put("share/latin1.txt", new byte[] {'o', 'k', (byte) 0xff, (byte) 0xfe, '\n'});
        return files;
    }

    private static byte[] zip(Map<String, byte[]> files, String symlinks, boolean withDirectory) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            if (symlinks != null) {
                zip.putNextEntry(new ZipEntry("SYMLINKS.txt"));
                zip.write(u(symlinks));
                zip.closeEntry();
            }
            if (withDirectory) {
                zip.putNextEntry(new ZipEntry("var/empty/"));
                zip.closeEntry();
            }
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static final String SYMLINKS = STOCK_DIR + "/files/usr/bin/bash←bin/sh\n"
        + STOCK_DIR + "/files/usr/lib/libz.so.1←lib/libz.so\n";

    private static TermuxBootstrapAdapter fork() throws IOException {
        return TermuxBootstrapAdapter.load("dev.pschmitt.termux", TermuxHelperRewritesTest.forkValues());
    }

    private byte[] read(String name) throws IOException {
        return Files.readAllBytes(new File(staging, name).toPath());
    }

    private void assertSentinelUntouched() throws IOException {
        Assert.assertArrayEquals(u("do not touch"), Files.readAllBytes(sentinel.toPath()));
        Assert.assertEquals(1, prefix.list().length);
    }

    @Test
    public void forkExtractionAdaptsTextAndKeepsEverythingElseByteIdentical() throws IOException {
        Map<String, byte[]> files = bootstrapFiles();
        TermuxBootstrapExtractor.Result result = TermuxBootstrapExtractor.extract(
            zip(files, SYMLINKS, true), staging, fork(), recordingChmod);

        // generic path rewrite everywhere, also in files no table row names
        Assert.assertEquals("export PREFIX=" + FORK_DIR + "/files/usr\n# café ✓\n",
            new String(read("etc/profile"), StandardCharsets.UTF_8));
        Assert.assertEquals(FORK_DIR + "/files/usr/bin/foo\n" + FORK_DIR + "/files/usr/share\n",
            new String(read("var/lib/dpkg/info/foo.list"), StandardCharsets.UTF_8));
        // login: shebang rewritten by the generic rule, preload by the table
        String login = new String(read("bin/login"), StandardCharsets.ISO_8859_1);
        Assert.assertTrue(login, login.startsWith("#!" + FORK_DIR + "/files/usr/bin/sh"));
        Assert.assertTrue(login.contains("${TERMUX_PKG_REDIRECT_SO:+$TERMUX_PKG_REDIRECT_SO:}"));
        Assert.assertFalse(login.contains(STOCK_DIR));
        // helpers: package identity for am, Java class names unchanged
        String reset = new String(read("bin/termux-reset"), StandardCharsets.ISO_8859_1);
        Assert.assertTrue(reset.contains("\"com.termux.termuxam.Am\" stopservice \"dev.pschmitt.termux/com.termux.app.TermuxService\""));
        // conffile
        Assert.assertTrue(new String(read("etc/termux-login.sh"), StandardCharsets.ISO_8859_1).contains("TERMUX_PKG_REDIRECT_SO"));
        // unknown text, invalid UTF-8 and ELF: byte for byte
        Assert.assertArrayEquals(files.get("etc/unknown.conf"), read("etc/unknown.conf"));
        Assert.assertArrayEquals(files.get("share/latin1.txt"), read("share/latin1.txt"));
        Assert.assertArrayEquals(ELF, read("lib/libfoo.so"));
        Assert.assertArrayEquals(ELF, read("libexec/tool"));

        Assert.assertTrue(new File(staging, "var/empty").isDirectory());
        Assert.assertTrue(result.adaptedFiles.contains("etc/profile"));
        Assert.assertTrue(result.adaptedFiles.contains("bin/login"));
        Assert.assertFalse(result.adaptedFiles.contains("etc/unknown.conf"));
        Assert.assertFalse(result.adaptedFiles.contains("lib/libfoo.so"));
        Assert.assertEquals(2, result.symlinks.size());
        Assert.assertEquals(FORK_DIR + "/files/usr/bin/bash", result.symlinks.get(0).target);
        Assert.assertEquals(staging.getPath() + "/bin/sh", result.symlinks.get(0).link);
        Assert.assertTrue(new File(staging, "bin").isDirectory());
        assertSentinelUntouched();
    }

    @Test
    public void executableBitsAreRequestedOnlyForTheExecutablePaths() throws IOException {
        TermuxBootstrapExtractor.extract(zip(bootstrapFiles(), SYMLINKS, false), staging, fork(), recordingChmod);
        Assert.assertTrue(chmods.toString(), chmods.contains("login:700"));
        Assert.assertTrue(chmods.toString(), chmods.contains("termux-open:700"));
        Assert.assertTrue(chmods.toString(), chmods.contains("tool:700"));
        Assert.assertFalse(chmods.toString(), chmods.contains("profile:700"));
        Assert.assertFalse(chmods.toString(), chmods.contains("libfoo.so:700"));
    }

    @Test
    public void stockExtractionIsByteIdenticalForEveryFile() throws IOException {
        Map<String, byte[]> files = bootstrapFiles();
        TermuxBootstrapExtractor.Result result = TermuxBootstrapExtractor.extract(
            zip(files, SYMLINKS, false), staging, TermuxBootstrapAdapter.stock(), recordingChmod);
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            Assert.assertArrayEquals(file.getKey(), file.getValue(), read(file.getKey()));
        }
        Assert.assertTrue(result.adaptedFiles.isEmpty());
        // symlinks stay on the stock path
        Assert.assertEquals(STOCK_DIR + "/files/usr/bin/bash", result.symlinks.get(0).target);
        assertSentinelUntouched();
    }

    @Test
    public void aLoginThatCannotBeAdaptedAbortsTheExtractionAndTouchesOnlyStaging() throws IOException {
        Map<String, byte[]> files = bootstrapFiles();
        files.put("bin/login", b(TermuxHelperRewritesTest.stockFile("bin/login")
            .replace("export LD_PRELOAD=\"/", "export LD_PRELOAD=$X/")));
        try {
            TermuxBootstrapExtractor.extract(zip(files, SYMLINKS, false), staging, fork(), recordingChmod);
            Assert.fail("extraction must fail");
        } catch (IOException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("Fork identity check failed"));
        }
        assertSentinelUntouched();
    }

    @Test
    public void aMissingSymlinksFileFailsTheExtraction() throws IOException {
        try {
            TermuxBootstrapExtractor.extract(zip(bootstrapFiles(), null, false), staging, fork(), recordingChmod);
            Assert.fail("extraction must fail");
        } catch (IOException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("SYMLINKS.txt"));
        }
        assertSentinelUntouched();
    }

    @Test
    public void aMalformedSymlinkLineFailsTheExtraction() throws IOException {
        try {
            TermuxBootstrapExtractor.extract(zip(bootstrapFiles(), "no arrow here\n", false), staging, fork(), recordingChmod);
            Assert.fail("extraction must fail");
        } catch (IOException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("Malformed symlink"));
        }
    }

    @Test
    public void entriesCannotEscapeTheStagingDirectory() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("../usr/important", u("overwritten"));
        try {
            TermuxBootstrapExtractor.extract(zip(files, SYMLINKS, false), staging, fork(), recordingChmod);
            Assert.fail("extraction must fail");
        } catch (IOException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("escapes"));
        }
        assertSentinelUntouched();

        try {
            TermuxBootstrapExtractor.extract(zip(bootstrapFiles(), STOCK_DIR + "/x←../usr/link\n", false),
                new File(temp.getRoot(), "usr-staging2"), fork(), recordingChmod);
            Assert.fail("extraction must fail");
        } catch (IOException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("escapes"));
        }
        assertSentinelUntouched();
    }

    @Test
    public void aChmodFailureFailsTheExtraction() throws IOException {
        try {
            TermuxBootstrapExtractor.extract(zip(bootstrapFiles(), SYMLINKS, false), staging, fork(), (file, mode) -> {
                throw new IOException("chmod failed");
            });
            Assert.fail("extraction must fail");
        } catch (IOException expected) {
            Assert.assertEquals("chmod failed", expected.getMessage());
        }
    }

    @Test
    public void theMandatoryRewriteHoldsEvenWhenTheTableHasNoRows() throws IOException {
        // The fallback regression: a lost table used to mean "no rows, so no generic replacement".
        TermuxBootstrapAdapter noRows = TermuxBootstrapAdapter.withRules(FORK_DIR,
            java.util.Collections.<TermuxHelperRewrites.Rule>emptyList());
        TermuxBootstrapExtractor.extract(zip(bootstrapFiles(), SYMLINKS, false), staging, noRows, recordingChmod);
        Assert.assertEquals("export PREFIX=" + FORK_DIR + "/files/usr\n# café ✓\n",
            new String(read("etc/profile"), StandardCharsets.UTF_8));
        Assert.assertFalse(new String(read("bin/login"), StandardCharsets.ISO_8859_1).contains(STOCK_DIR));
    }
}
