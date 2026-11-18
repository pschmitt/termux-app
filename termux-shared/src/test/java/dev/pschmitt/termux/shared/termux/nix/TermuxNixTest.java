package dev.pschmitt.termux.shared.termux.nix;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TermuxNixTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void provisionedNeedsNixppAndEveryAsset() throws IOException {
        File nixpp = folder.newFile("nixpp");
        File assets = folder.newFolder("share", "nixpp");
        assertFalse(TermuxNix.isProvisioned(nixpp, assets));

        assertTrue(nixpp.setExecutable(true));
        for (String asset : new String[]{"launcher", "bootstrap-aarch64.zip"})
            Files.write(new File(assets, asset).toPath(), new byte[]{1});
        assertFalse("the manifest is missing", TermuxNix.isProvisioned(nixpp, assets));

        Files.write(new File(assets, "manifest.json").toPath(), new byte[]{'{', '}'});
        assertTrue(TermuxNix.isProvisioned(nixpp, assets));

        assertTrue(nixpp.setExecutable(false));
        assertFalse(TermuxNix.isProvisioned(nixpp, assets));
    }

    @Test
    public void guestHomeRequiresReadyRuntimeAndSafeHome() throws IOException {
        File home = folder.newFolder("root", "home", "guest");
        File session = folder.newFile("session.env");
        Files.write(session.toPath(), "SESSION_HOME=/home/guest\n".getBytes(StandardCharsets.UTF_8));
        assertNull(TermuxNix.guestHome(folder.getRoot()));
        folder.newFile("ready");
        assertEquals(home, TermuxNix.guestHome(folder.getRoot()));
        Files.write(session.toPath(), "SESSION_HOME=/home/../guest\n".getBytes(StandardCharsets.UTF_8));
        assertNull(TermuxNix.guestHome(folder.getRoot()));
    }

    @Test
    public void commandsNameTheUser() {
        assertArrayEquals(new String[]{"login", "--user", "pschmitt"}, TermuxNix.loginArguments("pschmitt"));
        assertArrayEquals(new String[]{"--json", "init", "--user", "nix"}, TermuxNix.initArguments("nix"));
    }
}
