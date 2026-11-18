package com.termux.app.fragments.settings.termux;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONException;
import org.junit.Test;

import java.util.List;

public class ServicesPreferencesFragmentTest {
    @Test
    public void parsesServiceState() throws Exception {
        List<ServicesPreferencesFragment.Service> services =
            ServicesPreferencesFragment.parseServices(
                "[{\"name\":\"sshd\",\"status\":\"running\",\"enabled\":true}]");

        assertEquals(1, services.size());
        assertEquals("sshd", services.get(0).name);
        assertEquals("running", services.get(0).status);
        assertTrue(services.get(0).enabled);
    }

    @Test
    public void rejectsUnsafeServiceNames() throws Exception {
        try {
            ServicesPreferencesFragment.parseServices(
                "[{\"name\":\"sshd; rm -rf /\",\"status\":\"running\",\"enabled\":true}]");
            fail("service names must stay a single argument");
        } catch (JSONException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }
}
