package com.termux.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The apk-library marker must name the library the runtime's start script preloads: nixpp
 * restarts a process whose preload differs from it. A new app on an older runtime must keep
 * the APK path, or every controller start would see a replaced APK and restart forever.
 */
public class NixppRuntimeRecoveryTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private File script(String body) throws Exception {
        File script = folder.newFile("start-system-control.sh");
        Files.write(script.toPath(), body.getBytes(StandardCharsets.UTF_8));
        return script;
    }

    @Test
    public void newRuntimeScriptGetsTheStableCopy() throws Exception {
        assertTrue(NixppRuntimeRecovery.scriptPreloadsStableCopy(script(
            "redirect=${base_apk%/*}/lib/arm64/libtermux-pkg-redirect.so\n"
                + "if [[ -r $data_dir/files/lib/libtermux-pkg-redirect.so ]]\nthen\n"
                + "  redirect=$data_dir/files/lib/libtermux-pkg-redirect.so\nfi\n")));
    }

    @Test
    public void olderRuntimeScriptKeepsTheApkPath() throws Exception {
        assertFalse(NixppRuntimeRecovery.scriptPreloadsStableCopy(script(
            "redirect=${base_apk%/*}/lib/arm64/libtermux-pkg-redirect.so\n")));
    }

    @Test
    public void missingScriptKeepsTheApkPath() {
        assertFalse(NixppRuntimeRecovery.scriptPreloadsStableCopy(new File(folder.getRoot(), "missing.sh")));
    }
}
