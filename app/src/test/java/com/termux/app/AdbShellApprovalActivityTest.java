package com.termux.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AdbShellApprovalActivityTest {
    @Test
    public void inventedRequestCannotOpenAnApproval() {
        AdbShellApprovalActivity.cancelPending();
        try (org.robolectric.android.controller.ActivityController<AdbShellApprovalActivity> controller =
            Robolectric.buildActivity(AdbShellApprovalActivity.class,
                new Intent().putExtra("request", "invented"))) {
            assertTrue(controller.create().get().isFinishing());
        }
    }

    @Test
    public void unavailableFingerprintHardwareFailsClosed() {
        boolean[] approved = {false};
        boolean[] rejected = {false};
        AdbShellFingerprintApproval authentication = AdbShellFingerprintApproval.start(
            RuntimeEnvironment.getApplication(), new AdbShellFingerprintApproval.Callback() {
                @Override public void onApproved() { approved[0] = true; }
                @Override public void onRejected() { rejected[0] = true; }
                @Override public void onRetry() { }
            });
        assertNull(authentication);
        assertTrue(rejected[0]);
        assertFalse(approved[0]);
    }

    @Test
    @Config(sdk = 22)
    public void preFingerprintAndroidCannotEnroll() throws Exception {
        assertFalse(AdbShellApprovalActivity.request(RuntimeEnvironment.getApplication(), null, "client"));
    }
}
