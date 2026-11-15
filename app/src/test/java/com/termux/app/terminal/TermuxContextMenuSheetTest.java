package com.termux.app.terminal;

import android.app.Activity;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.termux.R;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
public class TermuxContextMenuSheetTest {

    private static final int USERNAME = 11;
    private static final int PASSWORD = 2;
    private static final int RESET = 3;
    private static final int KILL = 4;
    private static final int OTHER = 99;

    private static Activity activity() {
        Activity activity = Robolectric.buildActivity(Activity.class).create().get();
        activity.setTheme(R.style.Theme_TermuxApp_DayNight_NoActionBar);
        return activity;
    }

    private static TermuxContextMenuSheet sheet(Activity activity) {
        return new TermuxContextMenuSheet(activity)
            .addGroup(R.string.ui_menu_group_text)
            .addDualAction(R.string.ui_menu_autofill, R.drawable.ui_ic_bolt,
                USERNAME, R.string.action_autofill_username, R.drawable.ui_ic_person, true,
                PASSWORD, R.string.action_autofill_password, R.drawable.ui_ic_lock, true)
            .addDualAction(R.string.ui_menu_session_controls,
                RESET, R.string.action_reset_terminal, R.drawable.ui_ic_refresh, true,
                KILL, R.string.action_kill_process_accessibility, R.drawable.ui_ic_power, false)
            .addItem(OTHER, R.string.action_select_url, R.drawable.ui_ic_link);
    }

    private static int clickAndGetChosen(Activity activity, int viewId) {
        int[] chosen = {Integer.MIN_VALUE};
        BottomSheetDialog dialog = sheet(activity).show(id -> chosen[0] = id);
        View button = dialog.findViewById(viewId);
        Assert.assertNotNull(button);
        Assert.assertTrue(button.performClick());
        // The dismiss callback is delivered through the main looper.
        ShadowLooper.idleMainLooper();
        return chosen[0];
    }

    private static View dualRow(BottomSheetDialog dialog, Activity activity, int labelId) {
        String label = activity.getString(labelId);
        for (View view : allViews(dialog.findViewById(R.id.context_sheet_items))) {
            if (view instanceof android.widget.TextView
                && label.contentEquals(((android.widget.TextView) view).getText())) {
                return (View) view.getParent();
            }
        }
        throw new AssertionError("Missing dual-action row: " + label);
    }

    @Test
    public void usernameAndPasswordButtonsChooseTheirOwnIds() {
        Assert.assertEquals(USERNAME, clickAndGetChosen(activity(), R.id.context_dual_first));
        Assert.assertEquals(PASSWORD, clickAndGetChosen(activity(), R.id.context_dual_second));
    }

    @Test
    public void sessionButtonsKeepIndependentLabelsAndDisabledKillState() {
        Activity activity = activity();
        BottomSheetDialog dialog = sheet(activity).show(id -> { });
        View row = dualRow(dialog, activity, R.string.ui_menu_session_controls);
        ImageView first = row.findViewById(R.id.context_dual_first);
        ImageView second = row.findViewById(R.id.context_dual_second);
        Assert.assertEquals(activity.getString(R.string.action_reset_terminal), first.getContentDescription().toString());
        Assert.assertEquals(activity.getString(R.string.action_kill_process_accessibility), second.getContentDescription().toString());
        Assert.assertTrue(first.isEnabled());
        Assert.assertTrue(first.isClickable());
        Assert.assertFalse(second.isEnabled());
        Assert.assertFalse(second.isFocusable());
        Assert.assertFalse(row.isClickable());
    }

    @Test
    public void resetAndKillButtonsChooseTheirOwnIds() {
        Activity activity = activity();
        int[] chosen = {Integer.MIN_VALUE};
        BottomSheetDialog dialog = sheet(activity).show(id -> chosen[0] = id);
        View row = dualRow(dialog, activity, R.string.ui_menu_session_controls);
        Assert.assertTrue(row.findViewById(R.id.context_dual_first).performClick());
        ShadowLooper.idleMainLooper();
        Assert.assertEquals(RESET, chosen[0]);

        chosen[0] = Integer.MIN_VALUE;
        dialog = sheet(activity).show(id -> chosen[0] = id);
        row = dualRow(dialog, activity, R.string.ui_menu_session_controls);
        Assert.assertFalse(row.findViewById(R.id.context_dual_second).performClick());
        ShadowLooper.idleMainLooper();
        Assert.assertEquals(Integer.MIN_VALUE, chosen[0]);
    }

    @Test
    public void autofillButtonsAreLabelledAndTheRowIsNotClickable() {
        Activity activity = activity();
        BottomSheetDialog dialog = sheet(activity).show(id -> { });
        View row = dualRow(dialog, activity, R.string.ui_menu_autofill);
        ImageView first = row.findViewById(R.id.context_dual_first);
        ImageView second = row.findViewById(R.id.context_dual_second);
        Assert.assertEquals(activity.getString(R.string.action_autofill_username), first.getContentDescription().toString());
        Assert.assertEquals(activity.getString(R.string.action_autofill_password), second.getContentDescription().toString());
        ImageView leading = row.findViewById(R.id.context_dual_icon);
        Assert.assertEquals(View.VISIBLE, leading.getVisibility());

        float density = activity.getResources().getDisplayMetrics().density;
        Assert.assertTrue(first.getLayoutParams().width >= Math.round(48 * density));
        Assert.assertTrue(first.getLayoutParams().height >= Math.round(48 * density));
        Assert.assertTrue(second.getLayoutParams().width >= Math.round(48 * density));
        Assert.assertTrue(second.getLayoutParams().height >= Math.round(48 * density));
        Assert.assertTrue(first.isClickable() && first.isFocusable());
        Assert.assertTrue(second.isClickable() && second.isFocusable());

        Assert.assertFalse(row.isClickable());
        Assert.assertFalse(row.isFocusable());
    }

    @Test
    public void regularItemsStillChooseTheirId() {
        Activity activity = activity();
        int[] chosen = {Integer.MIN_VALUE};
        BottomSheetDialog dialog = sheet(activity).show(id -> chosen[0] = id);
        android.widget.TextView title = null;
        for (View v : allViews(dialog.findViewById(R.id.context_sheet_items))) {
            if (v.getId() == R.id.context_item_title) {
                title = (android.widget.TextView) v;
                break;
            }
        }
        Assert.assertNotNull(title);
        Assert.assertTrue(((View) title.getParent()).performClick());
        ShadowLooper.idleMainLooper();
        Assert.assertEquals(OTHER, chosen[0]);
    }

    @NonNull
    private static java.util.List<View> allViews(View root) {
        java.util.List<View> out = new java.util.ArrayList<>();
        out.add(root);
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) out.addAll(allViews(group.getChildAt(i)));
        }
        return out;
    }

    @Test
    public void maxHeightIsAboutTwoThirdsOfAPortraitScreen() {
        // 2400px tall display at 2.625 density: 65% = 1560px.
        int max = TermuxContextMenuSheet.computeMaxHeight(2200, 2.625f);
        Assert.assertEquals(Math.round(2200 * 0.65f), max);
        Assert.assertTrue(max < 2200 * 0.7f);
    }

    @Test
    public void maxHeightKeepsAGapAtTheTopAndAFloorInLandscape() {
        float density = 2.625f;
        // Landscape: only 400px are left. The floor of 300dp would be 787px, the top gap wins.
        int max = TermuxContextMenuSheet.computeMaxHeight(400, density);
        Assert.assertEquals(400 - Math.round(48 * density), max);
        Assert.assertTrue(max < 400);
    }

    @Test
    public void maxHeightShrinksWithTheKeyboardAndIsUnboundedWhenUnknown() {
        float density = 2.625f;
        int full = TermuxContextMenuSheet.computeMaxHeight(2200, density);
        int withKeyboard = TermuxContextMenuSheet.computeMaxHeight(1300, density);
        Assert.assertTrue(withKeyboard < full);
        Assert.assertTrue(withKeyboard <= 1300 - Math.round(48 * density));
        Assert.assertEquals(-1, TermuxContextMenuSheet.computeMaxHeight(0, density));
    }
    @Test
    public void headerIsOnlyTheDragHandleWithoutAVisibleTitle() {
        Activity activity = activity();
        BottomSheetDialog dialog = sheet(activity).show(id -> { });
        View items = dialog.findViewById(R.id.context_sheet_items);
        Assert.assertNotNull(items);

        // Content root: the handle, then the scrolling list. No heading in between.
        android.view.ViewGroup root = (android.view.ViewGroup) items.getParent().getParent();
        Assert.assertEquals(2, root.getChildCount());
        Assert.assertFalse(root.getChildAt(0) instanceof android.widget.TextView);
        Assert.assertTrue(root.getChildAt(1) instanceof androidx.core.widget.NestedScrollView);

        // The group labels stay, and the old heading text is not drawn anywhere.
        Assert.assertFalse(containsVisibleText(root, activity.getString(R.string.ui_menu_title)));
        Assert.assertTrue(containsVisibleText(root, activity.getString(R.string.ui_menu_group_text)));
    }

    @Test
    public void screenReadersStillGetATitle() {
        Activity activity = activity();
        BottomSheetDialog dialog = sheet(activity).show(id -> { });
        View items = dialog.findViewById(R.id.context_sheet_items);
        android.view.ViewGroup root = (android.view.ViewGroup) items.getParent().getParent();
        Assert.assertEquals(activity.getString(R.string.ui_menu_title),
            String.valueOf(androidx.core.view.ViewCompat.getAccessibilityPaneTitle(root)));
    }

    @Test
    public void expandedSheetKeepsItsRoundedTopCorners() {
        Activity activity = activity();
        BottomSheetDialog dialog = sheet(activity).show(id -> { });
        ShadowLooper.idleMainLooper();
        Assert.assertEquals(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED,
            dialog.getBehavior().getState());
        Assert.assertFalse(dialog.getBehavior().isShouldRemoveExpandedCorners());

        View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        Assert.assertNotNull(sheet);
        Assert.assertTrue(sheet.getBackground() instanceof com.google.android.material.shape.MaterialShapeDrawable);
        com.google.android.material.shape.ShapeAppearanceModel shape =
            ((com.google.android.material.shape.MaterialShapeDrawable) sheet.getBackground()).getShapeAppearanceModel();
        android.graphics.RectF bounds = new android.graphics.RectF(0, 0, 1080, 1200);
        float expected = 28 * activity.getResources().getDisplayMetrics().density;
        Assert.assertEquals(expected, shape.getTopLeftCornerSize().getCornerSize(bounds), 0.5f);
        Assert.assertEquals(expected, shape.getTopRightCornerSize().getCornerSize(bounds), 0.5f);
        Assert.assertEquals(0f, shape.getBottomLeftCornerSize().getCornerSize(bounds), 0.5f);
        Assert.assertEquals(0f, shape.getBottomRightCornerSize().getCornerSize(bounds), 0.5f);
    }

    private static boolean containsVisibleText(View view, String text) {
        if (view instanceof android.widget.TextView && view.getVisibility() == View.VISIBLE
            && text.contentEquals(((android.widget.TextView) view).getText())) return true;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                if (containsVisibleText(group.getChildAt(i), text)) return true;
        }
        return false;
    }

}
