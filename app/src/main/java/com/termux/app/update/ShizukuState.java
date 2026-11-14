package com.termux.app.update;

/** How far along the privileged (Shizuku) install path is. */
public enum ShizukuState {

    /** Neither the Shizuku manager is installed nor is a Shizuku or Sui service running. */
    UNAVAILABLE,

    /** The service is running but its API is too old (pre-v11) to be used. */
    UNSUPPORTED,

    /** Shizuku is installed but its service is not running. */
    NOT_RUNNING,

    /** The service runs; this app has not been allowed to use it yet (it can still ask). */
    PERMISSION_NEEDED,

    /** The user denied the permission (or ticked "don't ask again"); only Shizuku can undo it. */
    PERMISSION_DENIED,

    /** Installs can go through the privileged binder. */
    READY;

    /** Maps what Shizuku reports to the state the UI and the updater act on. */
    public static ShizukuState of(boolean managerInstalled, boolean binderAlive, boolean permissionGranted,
                                  boolean supportedVersion, boolean permissionDenied) {
        if (binderAlive && !supportedVersion) return UNSUPPORTED;
        if (binderAlive && permissionGranted) return READY;
        if (binderAlive && permissionDenied) return PERMISSION_DENIED;
        if (binderAlive) return PERMISSION_NEEDED;
        if (managerInstalled) return NOT_RUNNING;
        return UNAVAILABLE;
    }

}
