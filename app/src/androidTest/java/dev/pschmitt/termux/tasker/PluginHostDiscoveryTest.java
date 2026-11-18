package dev.pschmitt.termux.tasker;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import dev.pschmitt.termux.tasker.utils.PluginUtils;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Mirrors what a real Locale/Tasker plug-in host does before it fires or edits a plug-in, using
 * the PackageManager and the identity of the real, installed host app.
 *
 * {@link ExecutionIntegrationTest} cannot catch host discovery problems: it fires the receiver
 * from the plug-in's own UID, and Android always allows the owning UID to reach its components,
 * whatever permission they declare. A receiver protected by a permission the host does not hold
 * is only rejected for the host ("plugin error: missing, disabled, not exported or no permission
 * receiver, or too many receivers").
 *
 * The host package defaults to Tasker and can be changed with the instrumentation argument
 * {@code termuxpp.hostPackage}. The host test is skipped when the host is not installed, unless
 * the argument is given explicitly.
 */
@RunWith(AndroidJUnit4.class)
public class PluginHostDiscoveryTest {
    private static final String DEFAULT_HOST_PACKAGE = "net.dinglisch.android.taskerm";

    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final PackageManager packageManager = context.getPackageManager();

    private List<ActivityInfo> ownFireReceivers() {
        Intent fire = new Intent(com.twofortyfouram.locale.Intent.ACTION_FIRE_SETTING);
        List<ActivityInfo> receivers = new ArrayList<>();
        for (ResolveInfo info : packageManager.queryBroadcastReceivers(fire, 0)) {
            if (context.getPackageName().equals(info.activityInfo.packageName)) receivers.add(info.activityInfo);
        }
        return receivers;
    }

    private String hostPackage() {
        Bundle args = InstrumentationRegistry.getArguments();
        String explicit = args.getString("termuxpp.hostPackage");
        String host = explicit != null ? explicit : DEFAULT_HOST_PACKAGE;
        try {
            packageManager.getPackageInfo(host, 0);
        } catch (PackageManager.NameNotFoundException e) {
            if (explicit != null) fail("Requested plug-in host is not installed: " + host);
            assumeTrue("Plug-in host not installed: " + host, false);
        }
        return host;
    }

    @Test
    public void fireReceiverIsDiscoverableByHost() {
        List<ActivityInfo> receivers = ownFireReceivers();
        assertEquals("A host rejects plug-ins with zero or several fire receivers", 1, receivers.size());
        ActivityInfo receiver = receivers.get(0);
        assertEquals(FireReceiver.class.getName(), receiver.name);
        assertTrue("fire receiver must be exported", receiver.exported);
        assertTrue("fire receiver must be enabled", receiver.enabled);
        assertEquals("Hosts only request the stock permission; they cannot be granted the TermuxPP one",
                PluginUtils.PLUGIN_HOST_PERMISSION, receiver.permission);
    }

    @Test
    public void editActivityIsDiscoverableByHost() {
        Intent edit = new Intent(com.twofortyfouram.locale.Intent.ACTION_EDIT_SETTING);
        List<ActivityInfo> activities = new ArrayList<>();
        for (ResolveInfo info : packageManager.queryIntentActivities(edit, 0)) {
            if (context.getPackageName().equals(info.activityInfo.packageName)) activities.add(info.activityInfo);
        }
        assertEquals(1, activities.size());
        ActivityInfo activity = activities.get(0);
        assertEquals(EditConfigurationActivity.class.getName(), activity.name);
        assertTrue("edit activity must be exported", activity.exported);
        assertTrue("edit activity must be enabled", activity.enabled);
        assertNull("edit activity must not require a permission", activity.permission);
        assertNotEquals("Hosts require an icon", 0, activity.getIconResource());
        assertNotEquals("Hosts require a label", 0, activity.labelRes);
    }

    @Test
    public void realHostHoldsFireReceiverPermission() {
        String host = hostPackage();
        List<ActivityInfo> receivers = ownFireReceivers();
        assertEquals(1, receivers.size());
        String permission = receivers.get(0).permission;
        assertNotNull(permission);
        assertEquals("Host " + host + " does not hold " + permission + ", so it cannot fire the plug-in",
                PackageManager.PERMISSION_GRANTED, packageManager.checkPermission(permission, host));
        assertNull("The edit screen must not warn about a missing permission for " + host,
                PluginUtils.checkIfPackageHasPermissionRunCommand(context, host));
    }
}
