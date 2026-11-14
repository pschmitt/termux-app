package com.termux.app.update;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ShizukuStateTest {

    @Test
    public void notInstalledAndNotRunning() {
        assertEquals(ShizukuState.UNAVAILABLE, ShizukuState.of(false, false, false, true, false));
    }

    @Test
    public void installedButStopped() {
        assertEquals(ShizukuState.NOT_RUNNING, ShizukuState.of(true, false, false, true, false));
        // A stale grant or denial means nothing while the service is down.
        assertEquals(ShizukuState.NOT_RUNNING, ShizukuState.of(true, false, true, true, true));
    }

    @Test
    public void runningWithoutPermission() {
        assertEquals(ShizukuState.PERMISSION_NEEDED, ShizukuState.of(true, true, false, true, false));
        assertEquals(ShizukuState.PERMISSION_DENIED, ShizukuState.of(true, true, false, true, true));
    }

    @Test
    public void suiHasNoManagerApp() {
        assertEquals(ShizukuState.READY, ShizukuState.of(false, true, true, true, false));
        assertEquals(ShizukuState.PERMISSION_NEEDED, ShizukuState.of(false, true, false, true, false));
    }

    @Test
    public void readyNeedsAGrant() {
        assertEquals(ShizukuState.READY, ShizukuState.of(true, true, true, true, false));
    }

    @Test
    public void oldServiceIsUnsupportedEvenWithAGrant() {
        assertEquals(ShizukuState.UNSUPPORTED, ShizukuState.of(true, true, true, false, false));
    }
}
