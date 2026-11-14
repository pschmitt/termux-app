package com.termux.app.update;

/** Why an in-app update stopped; each maps to a user-facing message. */
public enum UpdateFailure {
    NO_APK,
    NOT_TRUSTED,
    SHIZUKU_NOT_READY,
    DOWNLOAD_FAILED,
    INSUFFICIENT_STORAGE,
    CHECKSUM_UNAVAILABLE,
    CHECKSUM_MISMATCH,
    UNREADABLE_APK,
    WRONG_PACKAGE,
    SIGNATURE_MISMATCH,
    DOWNGRADE,
    INSTALL_FAILED,
    CANCELLED,
}
