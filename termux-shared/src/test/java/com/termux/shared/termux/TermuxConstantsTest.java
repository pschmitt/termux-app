package com.termux.shared.termux;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class TermuxConstantsTest {
    @Test
    public void coreComponentsUseJavaNamespaceNotApplicationId() {
        assertNotEquals(TermuxConstants.TERMUX_PACKAGE_NAME, TermuxConstants.TERMUX_JAVA_NAMESPACE);
        assertEquals("com.termux.BuildConfig", TermuxConstants.TERMUX_APP.BUILD_CONFIG_CLASS_NAME);
        assertEquals("com.termux.app.TermuxActivity", TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY_NAME);
        assertEquals("com.termux.app.TermuxService", TermuxConstants.TERMUX_APP.TERMUX_SERVICE_NAME);
        assertEquals("com.termux.app.RunCommandService", TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE_NAME);
    }

    @Test
    public void companionIdentitiesAndPermissionsUseForkApplicationId() {
        assertEquals("dev.pschmitt.termux.api", TermuxConstants.TERMUX_API_PACKAGE_NAME);
        assertEquals("dev.pschmitt.termux.tasker", TermuxConstants.TERMUX_TASKER_PACKAGE_NAME);
        assertEquals("dev.pschmitt.termux.permission.RUN_COMMAND", TermuxConstants.PERMISSION_RUN_COMMAND);
        assertEquals("dev.pschmitt.termux.RUN_COMMAND", TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND);
    }
}
