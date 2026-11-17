package com.termux.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AdbShellCommandTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private File executable(String name) throws IOException {
        File file = temporary.newFile(name);
        assertTrue(file.setExecutable(true));
        return file;
    }

    @Test public void canonicalShellWinsOverLegacy() throws Exception {
        File home = temporary.newFolder("home");
        File prefix = temporary.newFolder("prefix");
        File zsh = executable("zsh");
        File fish = executable("fish");
        File canonical = new File(home, ".config/termuxpp/shell");
        File legacy = new File(home, ".termux/shell");
        assertTrue(canonical.getParentFile().mkdirs());
        assertTrue(legacy.getParentFile().mkdirs());
        Files.createSymbolicLink(canonical.toPath(), zsh.toPath());
        Files.createSymbolicLink(legacy.toPath(), fish.toPath());
        assertEquals(zsh.getCanonicalPath(), AdbShellCommand.resolveShell(home, prefix, null));
        Files.delete(canonical.toPath());
        assertEquals(fish.getCanonicalPath(), AdbShellCommand.resolveShell(home, prefix, null));
    }

    @Test public void overrideAndFallback() throws Exception {
        File home = temporary.newFolder("home");
        File prefix = temporary.newFolder("prefix");
        assertTrue(new File(prefix, "bin").mkdir());
        File bash = new File(prefix, "bin/bash");
        assertTrue(bash.createNewFile());
        assertTrue(bash.setExecutable(true));
        assertEquals(bash.getCanonicalPath(), AdbShellCommand.resolveShell(home, prefix, null));
        assertEquals(bash.getCanonicalPath(), AdbShellCommand.resolveShell(home, prefix, "bash"));
        File fish = executable("fish");
        assertEquals(fish.getCanonicalPath(), AdbShellCommand.resolveShell(home, prefix, fish.getPath()));
        for (String invalid : new String[]{"missing", "bash -c id", "bash\nLIST", ""}) {
            try {
                AdbShellCommand.resolveShell(home, prefix, invalid);
                fail("Invalid override accepted");
            } catch (IOException expected) {}
        }
    }

    @Test public void brokenConfiguredShellDoesNotSilentlyFallback() throws Exception {
        File home = temporary.newFolder("home");
        File prefix = temporary.newFolder("prefix");
        File configured = new File(home, ".config/termuxpp/shell");
        assertTrue(configured.getParentFile().mkdirs());
        Files.createSymbolicLink(configured.toPath(), new File(prefix, "missing").toPath());
        try {
            AdbShellCommand.resolveShell(home, prefix, null);
            fail("Broken configured shell accepted");
        } catch (IOException expected) {}
    }
}
