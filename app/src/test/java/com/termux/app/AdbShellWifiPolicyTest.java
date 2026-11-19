package com.termux.app;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AdbShellWifiPolicyTest {
    @Test
    public void emptyPolicyKeepsExistingBehavior() {
        assertTrue(AdbShellService.permitsSsid("", null));
    }

    @Test
    public void matchesOnlyAnExactConfiguredSsid() {
        String allowed = "Home, Backup network";
        assertTrue(AdbShellService.permitsSsid(allowed, "\"Home\""));
        assertTrue(AdbShellService.permitsSsid(allowed, "\"Backup network\""));
        assertFalse(AdbShellService.permitsSsid(allowed, "\"Home guest\""));
        assertFalse(AdbShellService.permitsSsid(allowed, "\"home\""));
    }

    @Test
    public void unknownOrMalformedInputDeniesAccess() {
        assertFalse(AdbShellService.permitsSsid("Home", null));
        assertFalse(AdbShellService.permitsSsid("Home", "<unknown ssid>"));
        assertFalse(AdbShellService.permitsSsid("Home", "486f6d65"));
        assertFalse(AdbShellService.permitsSsid(" , ", "\"Home\""));
    }
}
