package com.termux.app.terminal;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import androidx.annotation.NonNull;

import dev.pschmitt.termux.shared.termux.TermuxConstants;

/**
 * The Termux:Styling companion that the "Style" menu entry opens.
 * <p>
 * The target is one exact component of the fork's package (see
 * {@link TermuxConstants#TERMUX_STYLING_PACKAGE_NAME}), never an implicit intent, so no unrelated
 * app can make the entry appear. Seeing the package on Android 11 and later needs the matching
 * {@code <queries>} entry of the manifest.
 */
public final class StylingApp {

    private StylingApp() {
    }

    /** The intent that opens the styling activity. The same one is used to check and to launch. */
    @NonNull
    public static Intent createIntent() {
        Intent intent = new Intent();
        intent.setClassName(TermuxConstants.TERMUX_STYLING_PACKAGE_NAME, TermuxConstants.TERMUX_STYLING_APP.TERMUX_STYLING_ACTIVITY_NAME);
        return intent;
    }

    /**
     * Whether the styling activity is installed and can be started by this app, checked with the
     * same intent that launches it.
     */
    public static boolean isAvailable(@NonNull PackageManager packageManager) {
        try {
            ResolveInfo info = packageManager.resolveActivity(createIntent(), 0);
            if (info == null || info.activityInfo == null) return false;
            ActivityInfo activity = info.activityInfo;
            // Only the exact component counts, not a resolver or any other app.
            return TermuxConstants.TERMUX_STYLING_PACKAGE_NAME.equals(activity.packageName)
                && TermuxConstants.TERMUX_STYLING_APP.TERMUX_STYLING_ACTIVITY_NAME.equals(activity.name);
        } catch (RuntimeException e) {
            // A failing package manager (for example a dead remote) must not break the menu: leave the entry out.
            return false;
        }
    }

}
