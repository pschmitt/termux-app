package dev.pschmitt.termux.tasker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

import androidx.core.content.ContextCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ExecutionIntegrationTest {
    @Test
    public void orderedPluginCommandReturnsOutputAndExitStatus() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File home = new File(context.getFilesDir(), "home");
        File directory = new File(home, ".config/termuxpp/tasker");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File script = new File(directory, "termuxpp-integration-" + UUID.randomUUID());
        File nixpp = new File(context.getFilesDir(), "usr/bin/nixpp");
        assertTrue("nixpp is not installed in the Termux prefix: " + nixpp, nixpp.canExecute());
        String action = context.getPackageName() + ".TEST_RESULT." + UUID.randomUUID();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Intent> received = new AtomicReference<>();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                received.set(intent);
                done.countDown();
            }
        };
        ContextCompat.registerReceiver(context, receiver, new IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED);
        try {
            String command = "printf termuxpp-stdout; printf termuxpp-stderr >&2; exit 7";
            String scriptBody = "#!/system/bin/sh\nexec \"" + nixpp.getAbsolutePath()
                    + "\" enter -- /run/current-system/sw/bin/env sh -c '" + command + "'\n";
            Files.write(script.toPath(), scriptBody.getBytes(StandardCharsets.UTF_8));
            assertTrue(script.setExecutable(true, true));
            Bundle bundle = PluginBundleManager.generateBundle(context, script.getName(), "", null, null, null, null, false, true);
            Intent fire = new Intent(com.twofortyfouram.locale.Intent.ACTION_FIRE_SETTING);
            fire.setClass(context, FireReceiver.class);
            fire.putExtra(com.twofortyfouram.locale.Intent.EXTRA_BUNDLE, bundle);
            fire.putExtra("net.dinglisch.android.tasker.extras.COMPLETION_INTENT",
                    new Intent(action).setPackage(context.getPackageName()).toUri(Intent.URI_INTENT_SCHEME));
            context.sendOrderedBroadcast(fire, null);
            assertTrue("No plugin completion received", done.await(30, TimeUnit.SECONDS));
            Bundle variables = received.get().getBundleExtra("net.dinglisch.android.tasker.extras.VARIABLES");
            assertNotNull(variables);
            assertTrue(variables.getString("%stdout"), variables.getString("%stdout").contains("termuxpp-stdout"));
            assertTrue(variables.getString("%stderr"), variables.getString("%stderr").contains("termuxpp-stderr"));
            assertEquals("7", variables.getString("%result"));
        } finally {
            context.unregisterReceiver(receiver);
            assertTrue(script.delete());
        }
    }
}
