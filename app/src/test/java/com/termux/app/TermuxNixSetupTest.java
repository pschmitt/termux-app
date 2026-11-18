package com.termux.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class TermuxNixSetupTest {

    @Test
    public void progressEventsBecomeDialogUpdates() {
        TermuxNixSetup.Tracker tracker = new TermuxNixSetup.Tracker();
        assertEquals(TermuxNixSetup.Status.Stage.EXTRACT, tracker.accept("{\"event\":\"extract\"}").stage);
        assertEquals(TermuxNixSetup.Status.Stage.PREPARED, tracker.accept("{\"event\":\"prepared\"}").stage);

        TermuxNixSetup.Status pkg = tracker.accept(
            "{\"event\":\"package\",\"index\":3,\"total\":13,\"name\":\"coreutils-9.12\"}");
        assertEquals(TermuxNixSetup.Status.Stage.PACKAGE, pkg.stage);
        assertEquals(3, pkg.index);
        assertEquals(13, pkg.total);
        assertEquals("coreutils-9.12", pkg.name);
        assertNull(pkg.detail);

        TermuxNixSetup.Status copy = tracker.accept("{\"event\":\"copy\",\"name\":\"gmp-6.3.0\"}");
        assertEquals(3, copy.index);
        assertEquals("coreutils-9.12", copy.name);
        assertEquals("gmp-6.3.0", copy.detail);

        assertEquals(TermuxNixSetup.Status.Stage.CHECK, tracker.accept("{\"event\":\"check\"}").stage);
        assertEquals(TermuxNixSetup.Status.Stage.READY, tracker.accept("{\"event\":\"ready\",\"runtime\":\"/r\"}").stage);
    }

    @Test
    public void otherLinesLeaveTheDialogAlone() {
        TermuxNixSetup.Tracker tracker = new TermuxNixSetup.Tracker();
        assertNull(tracker.accept("not json"));
        assertNull(tracker.accept("{\"event\":\"log\",\"line\":\"warning\"}"));
        // A copy before any package has no package to belong to.
        assertNull(tracker.accept("{\"event\":\"copy\",\"name\":\"x\"}"));
    }
}
