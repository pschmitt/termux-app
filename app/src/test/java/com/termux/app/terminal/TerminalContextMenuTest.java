package com.termux.app.terminal;

import android.app.Activity;
import android.view.Menu;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import com.termux.R;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.fakes.RoboMenu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class TerminalContextMenuTest {

    // The ids that Help and Report issue used to have in the terminal menu.
    private static final int FORMER_HELP_ID = 7;
    private static final int FORMER_REPORT_ISSUE_ID = 9;

    private static Activity activity() {
        Activity activity = Robolectric.buildActivity(Activity.class).create().get();
        activity.setTheme(R.style.Theme_TermuxApp_DayNight_NoActionBar);
        return activity;
    }

    private static TerminalContextMenu.State everything() {
        return new TerminalContextMenu.State(true, true, 1234, true, false);
    }

    private static TerminalContextMenu.State minimal() {
        return new TerminalContextMenu.State(false, false, 1234, false, false);
    }

    private static List<Integer> sheetIds(Activity activity, TerminalContextMenu.State state) {
        return TerminalContextMenu.populateSheet(new TermuxContextMenuSheet(activity), activity.getResources(), state)
            .getItemIds();
    }

    private static List<Integer> menuIds(Activity activity, TerminalContextMenu.State state) {
        Menu menu = new RoboMenu(activity);
        TerminalContextMenu.populateMenu(menu, activity.getResources(), state);
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < menu.size(); i++) ids.add(menu.getItem(i).getItemId());
        return ids;
    }

    private static void assertNoHelpOrReportIssue(List<Integer> ids) {
        Assert.assertFalse("Help is gone from the menu", ids.contains(FORMER_HELP_ID));
        Assert.assertFalse("Report issue is gone from the menu", ids.contains(FORMER_REPORT_ISSUE_ID));
    }

    @Test
    public void sheetOmitsHelpAndReportIssue() {
        Activity activity = activity();
        assertNoHelpOrReportIssue(sheetIds(activity, everything()));
        assertNoHelpOrReportIssue(sheetIds(activity, minimal()));
    }

    @Test
    public void fallbackMenuOmitsHelpAndReportIssue() {
        Activity activity = activity();
        assertNoHelpOrReportIssue(menuIds(activity, everything()));
        assertNoHelpOrReportIssue(menuIds(activity, minimal()));
    }

    @Test
    public void sheetKeepsTheOtherActionsInOrder() {
        Assert.assertEquals(Arrays.asList(
                TerminalContextMenu.SELECT_URL, TerminalContextMenu.SHARE_SELECTED_TEXT,
                TerminalContextMenu.SHARE_TRANSCRIPT, TerminalContextMenu.AUTOFILL_USERNAME,
                TerminalContextMenu.AUTOFILL_PASSWORD, TerminalContextMenu.RESET_TERMINAL,
                TerminalContextMenu.KILL_PROCESS, TerminalContextMenu.TOGGLE_KEEP_SCREEN_ON,
                TerminalContextMenu.SETTINGS),
            sheetIds(activity(), everything()));
        Assert.assertEquals(Arrays.asList(
                TerminalContextMenu.SELECT_URL, TerminalContextMenu.SHARE_TRANSCRIPT,
                TerminalContextMenu.RESET_TERMINAL, TerminalContextMenu.KILL_PROCESS,
                TerminalContextMenu.TOGGLE_KEEP_SCREEN_ON, TerminalContextMenu.SETTINGS),
            sheetIds(activity(), minimal()));

        Activity activity = activity();
        List<Integer> allIds = sheetIds(activity, everything());
        Assert.assertEquals(1, allIds.indexOf(TerminalContextMenu.SHARE_TRANSCRIPT)
            - allIds.indexOf(TerminalContextMenu.SHARE_SELECTED_TEXT));
        Assert.assertEquals(1, allIds.indexOf(TerminalContextMenu.KILL_PROCESS)
            - allIds.indexOf(TerminalContextMenu.RESET_TERMINAL));
        Assert.assertEquals(activity.getString(R.string.action_kill_process_accessibility), "Kill session");
        Assert.assertEquals("Kill session", activity.getString(R.string.action_kill_session));
        Assert.assertTrue(activity.getString(R.string.msg_confirm_kill_process).contains("process will stop"));
    }

    @Test
    public void shareAndSessionRowsHaveLeadingIconsAndAccessibleActions() {
        Activity activity = activity();
        BottomSheetDialog dialog = TerminalContextMenu.populateSheet(
            new TermuxContextMenuSheet(activity), activity.getResources(), everything()).show(id -> { });
        View share = dualRow(dialog, activity, R.string.ui_menu_share);
        View session = dualRow(dialog, activity, R.string.ui_menu_session_controls);

        assertLeadingIcon(share);
        assertLeadingIcon(session);
        ImageView selectedText = share.findViewById(R.id.context_dual_first);
        ImageView transcript = share.findViewById(R.id.context_dual_second);
        Assert.assertEquals(activity.getString(R.string.action_share_selected_text),
            selectedText.getContentDescription().toString());
        Assert.assertEquals(activity.getString(R.string.action_share_transcript),
            transcript.getContentDescription().toString());
        Assert.assertNotNull(selectedText.getDrawable());
        Assert.assertNotNull(transcript.getDrawable());
    }

    private static void assertLeadingIcon(View row) {
        ImageView leading = row.findViewById(R.id.context_dual_icon);
        Assert.assertNotNull(leading);
        Assert.assertEquals(View.VISIBLE, leading.getVisibility());
        Assert.assertNotNull(leading.getDrawable());
        Assert.assertNull(leading.getContentDescription());
    }

    private static View dualRow(BottomSheetDialog dialog, Activity activity, int labelId) {
        String label = activity.getString(labelId);
        for (View view : allViews(dialog.findViewById(R.id.context_sheet_items))) {
            if (view instanceof TextView && label.contentEquals(((TextView) view).getText()))
                return (View) view.getParent();
        }
        throw new AssertionError("Missing dual-action row: " + label);
    }

    private static List<View> allViews(View root) {
        List<View> out = new ArrayList<>();
        out.add(root);
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) out.addAll(allViews(group.getChildAt(i)));
        }
        return out;
    }

    @Test
    public void fallbackMenuOffersTheSameActionsAsTheSheet() {
        Activity activity = activity();
        for (TerminalContextMenu.State state : Arrays.asList(everything(), minimal())) {
            List<Integer> sheet = sheetIds(activity, state);
            List<Integer> menu = menuIds(activity, state);
            Assert.assertEquals(sheet.size(), menu.size());
            Assert.assertTrue(menu.containsAll(sheet));
        }
    }

    @Test
    public void settingsStaysInTheMenu() {
        Assert.assertTrue(sheetIds(activity(), minimal()).contains(TerminalContextMenu.SETTINGS));
        Assert.assertTrue(menuIds(activity(), minimal()).contains(TerminalContextMenu.SETTINGS));
    }

    @Test
    public void urlEntryIsCalledExtractUrlInSheetAndFallbackMenu() {
        Activity activity = activity();
        Assert.assertEquals("Extract URL", activity.getString(R.string.action_select_url));

        Menu menu = new RoboMenu(activity);
        TerminalContextMenu.populateMenu(menu, activity.getResources(), minimal());
        Assert.assertEquals("Extract URL", menu.findItem(TerminalContextMenu.SELECT_URL).getTitle().toString());
    }

}
