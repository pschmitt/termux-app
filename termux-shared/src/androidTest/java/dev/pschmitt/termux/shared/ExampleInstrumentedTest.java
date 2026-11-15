package dev.pschmitt.termux.shared;

import android.content.Context;

import dev.pschmitt.termux.shared.net.socket.local.LocalSocketManager;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class ExampleInstrumentedTest {
    @Test
    public void nativeJniResolvesMigratedClasses() {
        // Exercise a JNI entry point and its FindClass lookup of the migrated JniResult class.
        assertNotNull(LocalSocketManager.createServerSocket("namespace-test", new byte[0], 1));
    }

    @Test
    public void useAppContext() {
        // Context of the app under test.
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("dev.pschmitt.termux.shared.test", appContext.getPackageName());
    }
}
