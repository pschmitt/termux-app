package com.termux.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class AdbShellSessionProtocolTest {
    private boolean authenticate(String greeting, String response) throws Exception {
        AdbShellService service = Robolectric.buildService(AdbShellService.class).get();
        service.getSharedPreferences(AdbShellService.PREFS_NAME, 0).edit().clear()
            .putString(AdbShellService.PREF_TOKEN, "enrollment-fixture").commit();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Socket socket = new Socket() {
            @Override public InputStream getInputStream() {
                return new ByteArrayInputStream(greeting.getBytes(StandardCharsets.US_ASCII));
            }
            @Override public OutputStream getOutputStream() { return output; }
        };
        Method authenticate = AdbShellService.class.getDeclaredMethod("authenticate", Socket.class);
        authenticate.setAccessible(true);
        boolean result = (Boolean) authenticate.invoke(service, socket);
        assertEquals(response, output.toString("US-ASCII"));
        return result;
    }

    @Test public void certificateSessionGreetingNeedsNoEnrollmentToken() throws Exception {
        assertTrue(authenticate("TERMUXPP/2\n", "OK\n"));
    }

    @Test public void legacyClientStillNeedsCorrectToken() throws Exception {
        assertTrue(authenticate("TERMUXPP/1 enrollment-fixture\n", "OK\n"));
        assertFalse(authenticate("TERMUXPP/1 wrong-fixture\n", "ERR\n"));
    }

    @Test public void unsupportedGreetingIsRejected() throws Exception {
        assertFalse(authenticate("TERMUXPP/3\n", "ERR\n"));
        assertFalse(authenticate("TERMUXPP/2 extra\n", "ERR\n"));
    }
}
