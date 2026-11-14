package com.termux.app.dialogs;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.text.Spanned;
import android.text.style.URLSpan;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.shape.MaterialShapeDrawable;
import com.google.android.material.textfield.TextInputEditText;
import com.termux.R;
import com.termux.shared.interact.DialogUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.termux.interact.TextInputDialogUtils;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

/**
 * The look and the behaviour of every kind of dialog the app shows, in the light theme. The night
 * variant of the same tests is {@link DialogStyleNightTest}.
 */
@RunWith(RobolectricTestRunner.class)
public class DialogStyleTest {

    @NonNull
    static Activity activity() {
        Activity activity = Robolectric.buildActivity(Activity.class).create().get();
        activity.setTheme(R.style.Theme_TermuxApp_DayNight_NoActionBar);
        return activity;
    }

    @NonNull
    static AlertDialog latest() {
        ShadowLooper.idleMainLooper();
        Dialog dialog = ShadowDialog.getLatestDialog();
        Assert.assertNotNull("no dialog was shown", dialog);
        Assert.assertTrue("not an AppCompat dialog: " + dialog.getClass(), dialog instanceof AlertDialog);
        Assert.assertTrue(dialog.isShowing());
        return (AlertDialog) dialog;
    }

    @NonNull
    private static MaterialShapeDrawable surface(@NonNull AlertDialog dialog) {
        Drawable background = dialog.getWindow().getDecorView().getBackground();
        if (background instanceof InsetDrawable) background = ((InsetDrawable) background).getDrawable();
        Assert.assertTrue("dialog surface is " + background, background instanceof MaterialShapeDrawable);
        return (MaterialShapeDrawable) background;
    }

    /** The dialog has the purple Material look: rounded surface, token surface colour, token buttons. */
    private static void assertStyled(@NonNull Activity activity, @NonNull AlertDialog dialog) {
        MaterialShapeDrawable surface = surface(dialog);
        float radius = 28 * activity.getResources().getDisplayMetrics().density;
        RectF bounds = new RectF(0, 0, 800, 600);
        Assert.assertEquals(radius, surface.getShapeAppearanceModel().getTopLeftCornerSize().getCornerSize(bounds), 0.5f);
        Assert.assertEquals(radius, surface.getShapeAppearanceModel().getBottomRightCornerSize().getCornerSize(bounds), 0.5f);
        Assert.assertEquals(ContextCompat.getColor(activity, R.color.ui_row), surface.getFillColor().getDefaultColor());

        Button positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        Assert.assertNotNull(positive);
        Assert.assertEquals(ContextCompat.getColor(activity, R.color.ui_primary), positive.getCurrentTextColor());
    }

    @NonNull
    private static TextView title(@NonNull AlertDialog dialog) {
        TextView title = dialog.findViewById(androidx.appcompat.R.id.alertTitle);
        Assert.assertNotNull(title);
        return title;
    }

    @NonNull
    private static TextView message(@NonNull AlertDialog dialog) {
        TextView message = dialog.findViewById(android.R.id.message);
        Assert.assertNotNull(message);
        return message;
    }

    // -- Theme ---------------------------------------------------------------------------------

    @Test
    public void activityThemesRouteEveryDialogKindToTheOverlay() {
        Activity activity = activity();
        for (int attr : new int[]{androidx.appcompat.R.attr.alertDialogTheme,
            com.google.android.material.R.attr.materialAlertDialogTheme}) {
            TypedValue value = new TypedValue();
            Assert.assertTrue(activity.getTheme().resolveAttribute(attr, value, true));
            Assert.assertEquals(R.style.ThemeOverlay_TermuxPP_Dialog, value.resourceId);
        }
        TypedValue sheet = new TypedValue();
        Assert.assertTrue(activity.getTheme().resolveAttribute(
            com.google.android.material.R.attr.bottomSheetDialogTheme, sheet, true));
        Assert.assertNotEquals(0, sheet.resourceId);
    }

    @Test
    public void preferenceDialogsGetTheRoundedWindowBackground() {
        // androidx.preference builds a plain AppCompat AlertDialog from alertDialogTheme, not a
        // MaterialAlertDialogBuilder, so the overlay itself has to provide the rounded surface.
        Activity activity = activity();
        new AlertDialog.Builder(activity).setTitle("Channel").setItems(new String[]{"Latest"}, null).show();
        AlertDialog dialog = latest();
        TypedValue value = new TypedValue();
        Assert.assertTrue(dialog.getContext().getTheme().resolveAttribute(
            android.R.attr.windowBackground, value, true));
        Assert.assertEquals(R.drawable.ui_dialog_background, value.resourceId);
    }

    @Test
    public void aContextWithoutAMaterialThemeGetsOneInsteadOfCrashing() {
        Context activity = new android.view.ContextThemeWrapper(
            org.robolectric.RuntimeEnvironment.getApplication(), android.R.style.Theme_DeviceDefault);
        Assert.assertFalse(DialogUtils.isMaterialTheme(activity));
        Assert.assertTrue(DialogUtils.isMaterialTheme(DialogUtils.materialContext(activity)));

        DialogUtils.builder(activity).setMessage("Plain host theme").setPositiveButton(android.R.string.ok, null).show();
        Assert.assertEquals("Plain host theme", message(latest()).getText().toString());
    }

    @Test
    public void aMaterialContextIsUsedAsItIs() {
        Activity activity = activity();
        Assert.assertTrue(DialogUtils.isMaterialTheme(activity));
        Assert.assertSame(activity, DialogUtils.materialContext(activity));
    }

    // -- Message dialog (MessageDialogUtils: bootstrap errors, report issue, file receiver, package utils) --

    @Test
    public void messageDialogIsStyled() {
        Activity activity = activity();
        MessageDialogUtils.showMessage(activity, "Title", "Body", null);
        AlertDialog dialog = latest();
        assertStyled(activity, dialog);
        Assert.assertEquals("Title", title(dialog).getText().toString());
        Assert.assertEquals("Body", message(dialog).getText().toString());
        Assert.assertEquals(activity.getString(android.R.string.ok), dialog.getButton(DialogInterface.BUTTON_POSITIVE).getText().toString());
        Assert.assertNotEquals(View.VISIBLE, dialog.getButton(DialogInterface.BUTTON_NEGATIVE).getVisibility());
    }

    @Test
    public void messageDialogMakesAddressesClickable() {
        MessageDialogUtils.showMessage(activity(), "Title", "See https://example.org/page or mail me@example.org", null);
        TextView message = message(latest());
        Assert.assertTrue(message.getText() instanceof Spanned);
        URLSpan[] spans = ((Spanned) message.getText()).getSpans(0, message.getText().length(), URLSpan.class);
        Assert.assertEquals(2, spans.length);
        Assert.assertEquals("https://example.org/page", spans[0].getURL());
        Assert.assertEquals("mailto:me@example.org", spans[1].getURL());
        Assert.assertNotNull(message.getMovementMethod());
    }

    @Test
    public void messageDialogButtonsAndDismissCallbacks() {
        int[] positive = {0};
        int[] negative = {0};
        int[] dismissed = {0};
        MessageDialogUtils.showMessage(activity(), "Title", "Body",
            "Yes", (d, w) -> positive[0]++, "No", (d, w) -> negative[0]++, d -> dismissed[0]++);
        AlertDialog dialog = latest();
        Assert.assertEquals("Yes", dialog.getButton(DialogInterface.BUTTON_POSITIVE).getText().toString());
        Assert.assertEquals("No", dialog.getButton(DialogInterface.BUTTON_NEGATIVE).getText().toString());

        Assert.assertTrue(dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick());
        ShadowLooper.idleMainLooper();
        Assert.assertEquals(0, positive[0]);
        Assert.assertEquals(1, negative[0]);
        Assert.assertEquals(1, dismissed[0]);
        Assert.assertFalse(dialog.isShowing());
    }

    @Test
    public void messageDialogPositiveButtonDismisses() {
        int[] positive = {0};
        int[] dismissed = {0};
        MessageDialogUtils.showMessage(activity(), "Title", "Body",
            null, (d, w) -> positive[0]++, null, null, d -> dismissed[0]++);
        AlertDialog dialog = latest();
        Assert.assertTrue(dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick());
        ShadowLooper.idleMainLooper();
        Assert.assertEquals(1, positive[0]);
        Assert.assertEquals(1, dismissed[0]);
    }

    // -- Confirmation dialogs (kill session, styling, update, hyperlink, max terminals, bootstrap error) --

    @Test
    public void confirmationDialogWithAnErrorIconIsStyled() {
        Activity activity = activity();
        int[] confirmed = {0};
        DialogUtils.builder(activity, R.drawable.ui_ic_warning, true)
            .setTitle(R.string.title_confirm_kill_process)
            .setPositiveButton(android.R.string.yes, (d, w) -> confirmed[0]++)
            .setNegativeButton(android.R.string.no, null)
            .show();
        AlertDialog dialog = latest();
        assertStyled(activity, dialog);
        View icon = dialog.findViewById(android.R.id.icon);
        Assert.assertNotNull(icon);
        Assert.assertEquals(View.VISIBLE, icon.getVisibility());
        Assert.assertEquals(activity.getString(R.string.title_confirm_kill_process), title(dialog).getText().toString());

        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        ShadowLooper.idleMainLooper();
        Assert.assertEquals(0, confirmed[0]);
        Assert.assertFalse(dialog.isShowing());
    }

    @Test
    public void confirmationDialogRunsThePositiveAction() {
        int[] confirmed = {0};
        DialogUtils.builder(activity(), R.drawable.ui_ic_system_update, false)
            .setTitle("Update").setMessage("Install?")
            .setPositiveButton(R.string.termux_update_confirm_positive, (d, w) -> confirmed[0]++)
            .setNegativeButton(android.R.string.cancel, null)
            .show();
        AlertDialog dialog = latest();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        Assert.assertEquals(1, confirmed[0]);
        Assert.assertFalse(dialog.isShowing());
    }

    @Test
    public void threeButtonConsentDialogKeepsAllItsActions() {
        Activity activity = activity();
        List<String> actions = new ArrayList<>();
        DialogUtils.builder(activity, R.drawable.ui_ic_link, false)
            .setTitle(R.string.title_open_hyperlink)
            .setMessage(activity.getString(R.string.msg_open_hyperlink_concealing, "label", "https://example.org"))
            .setPositiveButton(R.string.action_open_hyperlink, (d, w) -> actions.add("open"))
            .setNeutralButton(R.string.action_copy_hyperlink, (d, w) -> actions.add("copy"))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
        AlertDialog dialog = latest();
        assertStyled(activity, dialog);
        Assert.assertEquals(View.VISIBLE, dialog.getButton(DialogInterface.BUTTON_NEUTRAL).getVisibility());
        Assert.assertEquals(ContextCompat.getColor(activity, R.color.ui_primary),
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).getCurrentTextColor());
        Assert.assertTrue(message(dialog).getText().toString().contains("https://example.org"));
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();
        Assert.assertEquals(java.util.Collections.singletonList("copy"), actions);
    }

    // -- Selection dialog (select URL) ------------------------------------------------------------

    @Test
    public void selectionDialogListsItemsAndKeepsLongPress() {
        Activity activity = activity();
        CharSequence[] urls = {"https://a.example", "https://b.example"};
        List<Integer> clicked = new ArrayList<>();
        AlertDialog dialog = DialogUtils.builder(activity, R.drawable.ui_ic_link, false)
            .setItems(urls, (d, which) -> clicked.add(which))
            .setTitle(R.string.title_select_url_dialog).create();
        List<Integer> longPressed = new ArrayList<>();
        dialog.setOnShowListener(di -> dialog.getListView().setOnItemLongClickListener((p, v, position, id) -> {
            longPressed.add(position);
            return true;
        }));
        dialog.show();
        ShadowLooper.idleMainLooper();

        Assert.assertEquals(2, dialog.getListView().getCount());
        Assert.assertEquals("https://b.example", dialog.getListView().getItemAtPosition(1).toString());
        Assert.assertTrue(dialog.getListView().getOnItemLongClickListener()
            .onItemLongClick(dialog.getListView(), null, 1, 1));
        Assert.assertEquals(java.util.Collections.singletonList(1), longPressed);
        Assert.assertTrue(dialog.getListView().performItemClick(null, 0, 0));
        Assert.assertEquals(java.util.Collections.singletonList(0), clicked);
    }

    // -- Text input dialog (named session, rename session, file receiver) --------------------------

    private static TextInputEditText input(@NonNull AlertDialog dialog) {
        TextInputEditText input = dialog.findViewById(com.termux.shared.R.id.dialog_input);
        Assert.assertNotNull(input);
        return input;
    }

    @Test
    public void textInputDialogIsStyledAndPrefilled() {
        Activity activity = activity();
        TextInputDialogUtils.textInput(activity, R.string.title_create_named_session, "abc",
            android.R.string.ok, text -> { }, 0, null, 0, null, null);
        AlertDialog dialog = latest();
        assertStyled(activity, dialog);
        Assert.assertEquals(activity.getString(R.string.title_create_named_session), title(dialog).getText().toString());
        TextInputEditText input = input(dialog);
        Assert.assertEquals("abc", input.getText().toString());
        Assert.assertEquals(3, input.getSelectionStart());
        Assert.assertEquals(3, input.getSelectionEnd());
        Assert.assertNotNull(dialog.findViewById(com.termux.shared.R.id.dialog_input_layout));
        Assert.assertEquals(activity.getString(android.R.string.cancel),
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).getText().toString());
        Assert.assertNotEquals(View.VISIBLE, dialog.getButton(DialogInterface.BUTTON_NEUTRAL).getVisibility());
    }

    @Test
    public void textInputDialogDeliversTheEditedText() {
        List<String> results = new ArrayList<>();
        int[] dismissed = {0};
        TextInputDialogUtils.textInput(activity(), R.string.title_create_named_session, null,
            android.R.string.ok, results::add, 0, null, 0, null, d -> dismissed[0]++);
        AlertDialog dialog = latest();
        input(dialog).setText("my session");
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        Assert.assertEquals(java.util.Collections.singletonList("my session"), results);
        Assert.assertEquals(1, dismissed[0]);
        Assert.assertFalse(dialog.isShowing());
    }

    @Test
    public void textInputDialogImeActionConfirmsAndDismisses() {
        List<String> results = new ArrayList<>();
        TextInputDialogUtils.textInput(activity(), R.string.title_create_named_session, "x",
            android.R.string.ok, results::add, 0, null, 0, null, null);
        AlertDialog dialog = latest();
        TextInputEditText input = input(dialog);
        input.setText("typed");
        input.onEditorAction(EditorInfo.IME_ACTION_DONE);
        Assert.assertEquals(java.util.Collections.singletonList("typed"), results);
        Assert.assertFalse(dialog.isShowing());
    }

    @Test
    public void textInputDialogNeutralAndNegativeActionsReceiveTheText() {
        List<String> neutral = new ArrayList<>();
        List<String> negative = new ArrayList<>();
        TextInputDialogUtils.textInput(activity(), R.string.title_create_named_session, "t",
            android.R.string.ok, text -> { },
            android.R.string.copy, neutral::add,
            android.R.string.no, negative::add, null);
        AlertDialog dialog = latest();
        Assert.assertEquals(View.VISIBLE, dialog.getButton(DialogInterface.BUTTON_NEUTRAL).getVisibility());
        Assert.assertEquals(activity().getString(android.R.string.no),
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).getText().toString());
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();
        Assert.assertEquals(java.util.Collections.singletonList("t"), neutral);
        Assert.assertTrue(negative.isEmpty());
    }

    @Test
    public void textInputDialogIsNotCancelledByATouchOutside() {
        TextInputDialogUtils.textInput(activity(), R.string.title_create_named_session, null,
            android.R.string.ok, text -> { }, 0, null, 0, null, null);
        AlertDialog dialog = latest();
        Assert.assertTrue(dialog.isShowing());
        long now = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent outside = android.view.MotionEvent.obtain(now, now,
            android.view.MotionEvent.ACTION_DOWN, -10f, -10f, 0);
        dialog.onTouchEvent(outside);
        outside.recycle();
        Assert.assertTrue(dialog.isShowing());
    }

    // -- Progress dialog (bootstrap installer) --------------------------------------------------------

    @Test
    public void progressDialogShowsASpinnerAndCannotBeCancelled() {
        Activity activity = activity();
        AlertDialog progress = DialogUtils.showProgress(activity, activity.getString(R.string.bootstrap_installer_body));
        AlertDialog dialog = latest();
        Assert.assertSame(progress, dialog);

        TextView text = dialog.findViewById(com.termux.shared.R.id.dialog_progress_message);
        Assert.assertNotNull(text);
        Assert.assertEquals(activity.getString(R.string.bootstrap_installer_body), text.getText().toString());
        Assert.assertTrue(containsSpinner(dialog.getWindow().getDecorView()));
        MaterialShapeDrawable surface = surface(dialog);
        Assert.assertEquals(ContextCompat.getColor(activity, R.color.ui_row), surface.getFillColor().getDefaultColor());

        Assert.assertFalse(org.robolectric.Shadows.shadowOf(dialog).isCancelable());
        dialog.dismiss();
        Assert.assertFalse(dialog.isShowing());
    }

    private static boolean containsSpinner(View view) {
        if (view instanceof CircularProgressIndicator) return ((CircularProgressIndicator) view).isIndeterminate();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (containsSpinner(group.getChildAt(i))) return true;
        }
        return false;
    }

    // -- Builder ---------------------------------------------------------------------------------------

    @Test
    public void builderIsAMaterialBuilderOnTheThemedContext() {
        MaterialAlertDialogBuilder builder = DialogUtils.builder(activity());
        Assert.assertTrue(DialogUtils.isMaterialTheme(builder.getContext()));
    }

    @Test
    public void iconTintFollowsTheDialogTheme() {
        Activity activity = activity();
        MaterialAlertDialogBuilder builder = DialogUtils.builder(activity);
        Assert.assertNotNull(DialogUtils.tintedIcon(builder.getContext(), R.drawable.ui_ic_warning, true));
        Assert.assertNotNull(DialogUtils.tintedIcon(builder.getContext(), R.drawable.ui_ic_link, false));
    }

    @Test
    public void keyBackDoesNotCloseTheProgressDialog() {
        DialogUtils.showProgress(activity(), "Working");
        AlertDialog dialog = latest();
        dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
        dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
        Assert.assertTrue(dialog.isShowing());
    }

}
