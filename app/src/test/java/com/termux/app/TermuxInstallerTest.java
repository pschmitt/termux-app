package com.termux.app;

import org.junit.Assert;
import org.junit.Test;

public class TermuxInstallerTest {

    private static final String STOCK_HELPER =
        "#!/data/data/com.termux/files/usr/bin/sh\n\n"
            + "case \"${TERMUX__USER_ID:-}\" in ''|*[!0-9]*|0[0-9]*) TERMUX__USER_ID=0;; esac\n\n"
            + "am broadcast --user \"$TERMUX__USER_ID\" -a \"com.termux.app.reload_style\" \"com.termux\" > /dev/null\n";

    @Test
    public void reloadSettingsHelperIsPointedAtTheForkIdentity() {
        String adapted = TermuxInstaller.adaptReloadSettingsScript(
            STOCK_HELPER, "dev.pschmitt.termux.app.reload_style", "dev.pschmitt.termux");
        Assert.assertTrue(adapted.contains(
            "-a \"dev.pschmitt.termux.app.reload_style\" \"dev.pschmitt.termux\" > /dev/null"));
        Assert.assertFalse(adapted.contains("\"com.termux.app.reload_style\""));
        // Only the broadcast line changes; the shebang path is handled by the generic path rewrite.
        Assert.assertTrue(adapted.startsWith("#!/data/data/com.termux/files/usr/bin/sh\n"));
    }

    @Test
    public void reloadSettingsHelperIsUnchangedForTheStockIdentity() {
        Assert.assertEquals(STOCK_HELPER, TermuxInstaller.adaptReloadSettingsScript(
            STOCK_HELPER, "com.termux.app.reload_style", "com.termux"));
    }

    @Test
    public void unrecognisedHelperIsLeftAlone() {
        String other = "#!/bin/sh\necho hello\n";
        Assert.assertEquals(other, TermuxInstaller.adaptReloadSettingsScript(
            other, "dev.pschmitt.termux.app.reload_style", "dev.pschmitt.termux"));
    }
}
