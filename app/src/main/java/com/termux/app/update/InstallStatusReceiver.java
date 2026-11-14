package com.termux.app.update;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Receives the PackageInstaller verdict of an update session. Not exported; reached by explicit intent only. */
public class InstallStatusReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !UpdateController.ACTION_INSTALL_STATUS.equals(intent.getAction())) return;
        UpdateController.getInstance(context).onInstallStatus(intent);
    }

}
