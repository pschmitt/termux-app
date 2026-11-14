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
    private static final int OTHER = 99;

    private static Activity activity() {
        Activity activity = Robolectric.buildActivity(Activity.class).create().get();
        activity.setTheme(R.style.Theme_TermuxApp_DayNight_NoActionBar);
        return activity;
    }

    private static TermuxContextMenuSheet sheet(Activity activity) {
        return new TermuxContextMenuSheet(activity)
            .addGroup(R.string.ui_menu_group_text)
            .addDualAction(R.string.ui_menu_autofill,
                USERNAME, R.string.action_autofill_username, R.drawable.ui_ic_person,
                PASSWORD, R.string.action_autofill_password, R.drawable.ui_ic_lock)
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

    @Test
    public void usernameAndPasswordButtonsChooseTheirOwnIds() {
        Assert.assertEquals(USERNAME, clickAndGetChosen(activity(), R.id.context_dual_first));
        Assert.assertEquals(PASSWORD, clickAndGetChosen(activity(), R.id.context_dual_second));
    }

    @Test
    public void autofillButtonsAreLabelledAndTheRowIsNotClickable() {
        Activity activity = activity();
        BottomSheetDialog dialog = sheet(activity).show(id -> { });
        ImageView first = dialog.findViewById(R.id.context_dual_first);
        ImageView second = dialog.findViewById(R.id.context_dual_second);
        Assert.assertEquals(activity.getString(R.string.action_autofill_username), first.getContentDescription().toString());
        Assert.assertEquals(activity.getString(R.string.action_autofill_password), second.getContentDescription().toString());

        float density = activity.getResources().getDisplayMetrics().density;
        Assert.assertTrue(first.getLayoutParams().width >= Math.round(48 * density));
        Assert.assertTrue(first.getLayoutParams().height >= Math.round(48 * density));
        Assert.assertTrue(second.getLayoutParams().width >= Math.round(48 * density));
        Assert.assertTrue(second.getLayoutParams().height >= Math.round(48 * density));
        Assert.assertTrue(first.isClickable() && first.isFocusable());
        Assert.assertTrue(second.isClickable() && second.isFocusable());

        View row = (View) first.getParent();
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
