package com.termux.app.terminal;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

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
    private static final int KILL_NOW = 12;
    private static final int SETTINGS = 8;
    private static final int OTHER = 99;

    private static Activity activity() {
        Activity activity = Robolectric.buildActivity(Activity.class).create().get();
        activity.setTheme(R.style.Theme_TermuxApp_DayNight_NoActionBar);
        return activity;
    }

    private static TermuxContextMenuSheet sheet(Activity activity, boolean killEnabled) {
        return new TermuxContextMenuSheet(activity)
            .setHeader("zsh", "~/devel · pid 4182", SETTINGS, "Settings", R.drawable.ui_ic_settings)
            .addTile(USERNAME, "Username", "autofill", R.drawable.ui_ic_person)
            .addTile(PASSWORD, "Password", "autofill", R.drawable.ui_ic_lock)
            .addTile(RESET, "Reset", null, R.drawable.ui_ic_refresh)
            .addDangerTile(KILL, "Kill zsh", "hold to kill now", R.drawable.ui_ic_delete, killEnabled,
                KILL_NOW, "Kill now")
            .addTile(TerminalContextMenu.SELECT_URL, "Open URL", "2 found", R.drawable.ui_ic_link)
            .addItem(OTHER, R.string.action_toggle_keep_screen_on, R.drawable.ui_ic_lightbulb);
    }

    private static TermuxContextMenuSheet sheet(Activity activity) {
        return sheet(activity, true);
    }

    private static View tile(BottomSheetDialog dialog, String title) {
        for (View view : allViews(dialog.findViewById(R.id.context_sheet_items))) {
            if (view.getId() == R.id.context_tile_title && title.contentEquals(((TextView) view).getText()))
                return (View) view.getParent().getParent();
        }
        throw new AssertionError("Missing tile: " + title);
    }

    private static int clickTile(Activity activity, String title, boolean longPress) {
        int[] chosen = {Integer.MIN_VALUE};
        BottomSheetDialog dialog = sheet(activity).show(id -> chosen[0] = id);
        View view = tile(dialog, title);
        Assert.assertTrue(longPress ? view.performLongClick() : view.performClick());
        // The dismiss callback is delivered through the main looper.
        ShadowLooper.idleMainLooper();
        return chosen[0];
    }

    @Test
    public void tilesChooseTheirOwnIds() {
        Activity activity = activity();
        Assert.assertEquals(USERNAME, clickTile(activity, "Username", false));
        Assert.assertEquals(PASSWORD, clickTile(activity, "Password", false));
        Assert.assertEquals(RESET, clickTile(activity, "Reset", false));
        Assert.assertEquals(KILL, clickTile(activity, "Kill zsh", false));
    }

    @Test
    public void holdingKillChoosesKillNowAndIsOfferedToScreenReaders() {
        Activity activity = activity();
        Assert.assertEquals(KILL_NOW, clickTile(activity, "Kill zsh", true));

        BottomSheetDialog dialog = sheet(activity).show(id -> { });
        AccessibilityNodeInfoCompat info = AccessibilityNodeInfoCompat.obtain();
        ViewCompat.onInitializeAccessibilityNodeInfo(tile(dialog, "Kill zsh"), info);
        boolean labelled = false;
        for (AccessibilityNodeInfoCompat.AccessibilityActionCompat action : info.getActionList()) {
            if (action.getId() == AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)
                labelled = "Kill now".contentEquals(action.getLabel());
        }
        Assert.assertTrue(labelled);
    }

    @Test
    public void disabledKillTileChoosesNothing() {
        Activity activity = activity();
        int[] chosen = {Integer.MIN_VALUE};
        BottomSheetDialog dialog = sheet(activity, false).show(id -> chosen[0] = id);
        View kill = tile(dialog, "Kill zsh");
        Assert.assertFalse(kill.isEnabled());
        Assert.assertFalse(kill.performClick());
        Assert.assertFalse(kill.performLongClick());
        ShadowLooper.idleMainLooper();
        Assert.assertEquals(Integer.MIN_VALUE, chosen[0]);
    }

    @Test
    public void tilesAreLaidOutTwoPerRowAndKeepTheTouchTarget() {
        Activity activity = activity();
        BottomSheetDialog dialog = sheet(activity).show(id -> { });
        ViewGroup first = (ViewGroup) tile(dialog, "Username").getParent();
        Assert.assertSame(first, tile(dialog, "Password").getParent());
        Assert.assertNotSame(first, tile(dialog, "Reset").getParent());

        // The fifth tile is alone in its row, and only takes half of it.
        ViewGroup last = (ViewGroup) tile(dialog, "Open URL").getParent();
        Assert.assertEquals(3, last.getChildCount());
        Assert.assertEquals(1f, ((LinearLayout.LayoutParams) last.getChildAt(2).getLayoutParams()).weight, 0f);

        float density = activity.getResources().getDisplayMetrics().density;
        Assert.assertTrue(tile(dialog, "Username").getMinimumHeight() >= Math.round(48 * density));
    }

    @Test
    public void tilesShowTheirDetailOnlyWhenThereIsOne() {
        Activity activity = activity();
        BottomSheetDialog dialog = sheet(activity).show(id -> { });
        TextView withDetail = tile(dialog, "Open URL").findViewById(R.id.context_tile_detail);
        TextView without = tile(dialog, "Reset").findViewById(R.id.context_tile_detail);
        Assert.assertEquals(View.VISIBLE, withDetail.getVisibility());
        Assert.assertEquals("2 found", withDetail.getText().toString());
        Assert.assertEquals(View.GONE, without.getVisibility());
    }

    @Test
    public void headerNamesTheSessionAndItsActionChoosesItsId() {
        Activity activity = activity();
        int[] chosen = {Integer.MIN_VALUE};
        BottomSheetDialog dialog = sheet(activity).show(id -> chosen[0] = id);
        View header = dialog.findViewById(R.id.context_sheet_header);
        Assert.assertEquals(View.VISIBLE, header.getVisibility());
        Assert.assertEquals("zsh", ((TextView) header.findViewById(R.id.context_header_title)).getText().toString());
        Assert.assertEquals("~/devel · pid 4182",
            ((TextView) header.findViewById(R.id.context_header_subtitle)).getText().toString());
        ImageView action = header.findViewById(R.id.context_header_action);
        Assert.assertEquals("Settings", action.getContentDescription().toString());
        Assert.assertTrue(action.performClick());
        ShadowLooper.idleMainLooper();
        Assert.assertEquals(SETTINGS, chosen[0]);
    }

    @Test
    public void headerActionIsListedLast() {
        Assert.assertEquals(java.util.Arrays.asList(USERNAME, PASSWORD, RESET, KILL,
                TerminalContextMenu.SELECT_URL, OTHER, SETTINGS),
            sheet(activity()).getItemIds());
    }

    @Test
    public void withoutAHeaderNothingIsShownAboveTheList() {
        Activity activity = activity();
        BottomSheetDialog dialog = new TermuxContextMenuSheet(activity)
            .addItem(OTHER, R.string.action_select_url, R.drawable.ui_ic_link).show(id -> { });
        Assert.assertEquals(View.GONE, dialog.findViewById(R.id.context_sheet_header).getVisibility());
    }

    @Test
    public void regularItemsStillChooseTheirId() {
        Activity activity = activity();
        int[] chosen = {Integer.MIN_VALUE};
        BottomSheetDialog dialog = sheet(activity).show(id -> chosen[0] = id);
        TextView title = null;
        for (View v : allViews(dialog.findViewById(R.id.context_sheet_items))) {
            if (v.getId() == R.id.context_item_title) {
                title = (TextView) v;
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
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
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
    public void headerIsTheDragHandleAndTheSessionWithoutAMenuTitle() {
        Activity activity = activity();
        BottomSheetDialog dialog = sheet(activity).show(id -> { });
        View items = dialog.findViewById(R.id.context_sheet_items);
        Assert.assertNotNull(items);

        // Content root: the handle, the session header, then the scrolling list.
        ViewGroup root = (ViewGroup) items.getParent().getParent();
        Assert.assertEquals(3, root.getChildCount());
        Assert.assertFalse(root.getChildAt(0) instanceof TextView);
        Assert.assertEquals(R.id.context_sheet_header, root.getChildAt(1).getId());
        Assert.assertTrue(root.getChildAt(2) instanceof androidx.core.widget.NestedScrollView);

        // Neither section labels nor a visible menu title are rendered.
        Assert.assertFalse(containsVisibleText(root, activity.getString(R.string.ui_menu_title)));
        Assert.assertFalse(containsVisibleText(root, activity.getString(R.string.ui_menu_group_text)));
        Assert.assertFalse(containsVisibleText(root, activity.getString(R.string.ui_menu_group_session)));
        Assert.assertFalse(containsVisibleText(root, activity.getString(R.string.ui_menu_group_app)));
    }

    @Test
    public void screenReadersStillGetATitle() {
        Activity activity = activity();
        BottomSheetDialog dialog = sheet(activity).show(id -> { });
        View items = dialog.findViewById(R.id.context_sheet_items);
        ViewGroup root = (ViewGroup) items.getParent().getParent();
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
        if (view instanceof TextView && view.getVisibility() == View.VISIBLE
            && text.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                if (containsVisibleText(group.getChildAt(i), text)) return true;
        }
        return false;
    }

}
