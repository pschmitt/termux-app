package com.termux.shared.interact;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.graphics.drawable.DrawableCompat;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.shared.R;

/**
 * The one place that creates the dialogs of the app, so that all of them share the Material look
 * the theme gives them (the {@code materialAlertDialogTheme} of the activity theme: shape, surface,
 * title, message and button styles).
 * <p>
 * The framework {@link android.app.AlertDialog} and {@link android.app.ProgressDialog} ignore that
 * theme and must not be used; the source scan test of the app enforces it.
 */
public final class DialogUtils {

    private DialogUtils() {}

    /** Whether the theme of the {@link Context} is a Material Components theme. */
    public static boolean isMaterialTheme(@NonNull Context context) {
        TypedValue value = new TypedValue();
        return context.getTheme().resolveAttribute(com.google.android.material.R.attr.isMaterialTheme, value, true)
            && value.type == TypedValue.TYPE_INT_BOOLEAN && value.data != 0;
    }

    /**
     * The {@link Context} to build a dialog with: the given one when its theme is a Material theme.
     * A Material dialog crashes while inflating under any other theme, so such a context (a service,
     * or an activity of a host application) gets a Material theme on top of its own.
     */
    @NonNull
    public static Context materialContext(@NonNull Context context) {
        if (isMaterialTheme(context)) return context;
        return new ContextThemeWrapper(context, com.google.android.material.R.style.Theme_MaterialComponents_DayNight_NoActionBar);
    }

    /** A themed Material dialog builder. */
    @NonNull
    public static MaterialAlertDialogBuilder builder(@NonNull Context context) {
        return new MaterialAlertDialogBuilder(materialContext(context));
    }

    /**
     * A themed Material dialog builder with an icon in front of the title. The dialog needs a title,
     * AppCompat hides the icon of one that has none.
     *
     * @param icon The drawable resource of the icon, tinted with the primary colour of the dialog.
     * @param error Whether the icon is tinted with the error colour of the dialog instead.
     */
    @NonNull
    public static MaterialAlertDialogBuilder builder(@NonNull Context context, @DrawableRes int icon, boolean error) {
        MaterialAlertDialogBuilder builder = builder(context);
        Drawable drawable = tintedIcon(builder.getContext(), icon, error);
        if (drawable != null) builder.setIcon(drawable);
        return builder;
    }

    /**
     * @param dialogContext The themed context of the dialog, whose theme the colour comes from.
     */
    @Nullable
    public static Drawable tintedIcon(@NonNull Context dialogContext, @DrawableRes int icon, boolean error) {
        Drawable drawable = AppCompatResources.getDrawable(dialogContext, icon);
        if (drawable == null) return null;
        int fallback = error ? 0xFFB3261E : 0xFF6750A4;
        int attr = error ? androidx.appcompat.R.attr.colorError : androidx.appcompat.R.attr.colorPrimary;
        drawable = DrawableCompat.wrap(drawable.mutate());
        DrawableCompat.setTint(drawable, MaterialColors.getColor(dialogContext, attr, fallback));
        return drawable;
    }

    /** Make the web addresses and e-mail addresses of the message of a shown dialog clickable. */
    public static void linkifyMessage(@NonNull AlertDialog dialog) {
        TextView message = dialog.findViewById(android.R.id.message);
        if (message == null) return;
        Linkify.addLinks(message, Linkify.WEB_URLS | Linkify.EMAIL_ADDRESSES);
        message.setMovementMethod(LinkMovementMethod.getInstance());
    }

    /**
     * Show a modal progress dialog with a spinner and a message. It cannot be cancelled, whoever
     * shows it has to dismiss it.
     */
    @NonNull
    public static AlertDialog showProgress(@NonNull Context context, @NonNull CharSequence message) {
        MaterialAlertDialogBuilder builder = builder(context);
        View view = LayoutInflater.from(builder.getContext()).inflate(R.layout.dialog_progress, null);
        TextView text = view.findViewById(R.id.dialog_progress_message);
        text.setText(message);
        builder.setView(view).setCancelable(false);
        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        return dialog;
    }

}
