package com.termux.app.terminal;

import android.app.Activity;
import android.content.res.Resources;
import android.view.Menu;
import android.view.View;
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

    private static TerminalContextMenu.State session(String name, boolean running, int number, int count) {
        return new TerminalContextMenu.State(true, true, 4182, running, false,
            name, "zsh", "~/devel", number, count, 2);
    }

    @Test
    public void headerNamesTheSessionItsDirectoryPidAndPosition() {
        Resources resources = activity().getResources();
        Assert.assertEquals("zsh", TerminalContextMenu.headerTitle(resources, session(null, true, 2, 3)));
        Assert.assertEquals("build", TerminalContextMenu.headerTitle(resources, session("build", true, 2, 3)));
        Assert.assertEquals("Session 1", TerminalContextMenu.headerTitle(resources, minimal()));

        Assert.assertEquals("~/devel · pid 4182 · 2 of 3",
            TerminalContextMenu.headerDetail(resources, session(null, true, 2, 3)));
        Assert.assertEquals("~/devel · pid 4182",
            TerminalContextMenu.headerDetail(resources, session(null, true, 1, 1)));
        Assert.assertEquals("~/devel · not running",
            TerminalContextMenu.headerDetail(resources, session(null, false, 1, 1)));
    }

    @Test
    public void tilesAreLabelledWithWhatTheyDo() {
        Activity activity = activity();
        BottomSheetDialog dialog = TerminalContextMenu.populateSheet(
            new TermuxContextMenuSheet(activity), activity.getResources(), session(null, true, 2, 3)).show(id -> { });
        List<String> titles = new ArrayList<>();
        List<String> details = new ArrayList<>();
        for (View view : allViews(dialog.findViewById(R.id.context_sheet_items))) {
            if (view.getId() == R.id.context_tile_title) titles.add(((TextView) view).getText().toString());
            if (view.getId() == R.id.context_tile_detail) details.add(((TextView) view).getText().toString());
        }
        Assert.assertEquals(Arrays.asList("Open URL", "Share", "Share", "Username", "Password", "Reset", "Kill zsh"),
            titles);
        Assert.assertEquals(Arrays.asList("2 found", "selected text", "transcript", "autofill", "autofill",
            "terminal state", "hold to kill now"), details);
    }

    @Test
    public void killTileFallsBackToTheSessionAndSaysWhenNothingRuns() {
        Activity activity = activity();
        BottomSheetDialog dialog = TerminalContextMenu.populateSheet(
            new TermuxContextMenuSheet(activity), activity.getResources(), minimal()).show(id -> { });
        boolean found = false;
        for (View view : allViews(dialog.findViewById(R.id.context_sheet_items))) {
            if (view.getId() == R.id.context_tile_title && "Kill session".contentEquals(((TextView) view).getText())) {
                View tile = (View) view.getParent().getParent();
                Assert.assertFalse(tile.isEnabled());
                Assert.assertEquals("not running",
                    ((TextView) tile.findViewById(R.id.context_tile_detail)).getText().toString());
                found = true;
            }
        }
        Assert.assertTrue(found);
    }

    @Test
    public void processInfoAbbreviatesTheHomeDirectory() {
        String home = "/data/data/com.termux/files/home";
        Assert.assertEquals("~", ProcessInfo.abbreviateHome(home, home));
        Assert.assertEquals("~/devel", ProcessInfo.abbreviateHome(home + "/devel", home + "/"));
        Assert.assertEquals(home + "x/devel", ProcessInfo.abbreviateHome(home + "x/devel", home));
        Assert.assertEquals("/sdcard", ProcessInfo.abbreviateHome("/sdcard", home));
        Assert.assertNull(ProcessInfo.readName(-1));
        Assert.assertNull(ProcessInfo.readCwd(0));
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
