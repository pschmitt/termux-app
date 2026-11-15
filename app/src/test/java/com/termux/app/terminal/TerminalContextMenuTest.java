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
                TerminalContextMenu.SELECT_URL, TerminalContextMenu.SHARE_MENU,
                TerminalContextMenu.AUTOFILL_MENU, TerminalContextMenu.RESET_TERMINAL,
                TerminalContextMenu.KILL_PROCESS, TerminalContextMenu.KILL_PROCESS_NOW,
                TerminalContextMenu.TOGGLE_KEEP_SCREEN_ON, TerminalContextMenu.SETTINGS),
            sheetIds(activity(), everything()));
        Assert.assertEquals(Arrays.asList(
                TerminalContextMenu.SELECT_URL, TerminalContextMenu.SHARE_TRANSCRIPT,
                TerminalContextMenu.RESET_TERMINAL, TerminalContextMenu.KILL_PROCESS,
                TerminalContextMenu.KILL_PROCESS_NOW, TerminalContextMenu.TOGGLE_KEEP_SCREEN_ON,
                TerminalContextMenu.SETTINGS),
            sheetIds(activity(), minimal()));

        Activity activity = activity();
        List<Integer> allIds = sheetIds(activity, everything());
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
        Assert.assertEquals(Arrays.asList("Open URL", "Share", "Autofill", "Reset", "Kill zsh"), titles);
        Assert.assertEquals(Arrays.asList("2 found", "selection or all", "user or password",
            "terminal state", "hold to kill now"), details);
    }

    @Test
    public void withoutASelectionShareIsTheTranscript() {
        Activity activity = activity();
        BottomSheetDialog dialog = TerminalContextMenu.populateSheet(
            new TermuxContextMenuSheet(activity), activity.getResources(), minimal()).show(id -> { });
        List<String> details = new ArrayList<>();
        for (View view : allViews(dialog.findViewById(R.id.context_sheet_items))) {
            if (view.getId() == R.id.context_tile_detail) details.add(((TextView) view).getText().toString());
        }
        Assert.assertTrue(details.contains("transcript"));
        Assert.assertFalse(sheetIds(activity, minimal()).contains(TerminalContextMenu.SHARE_SELECTED_TEXT));
        Assert.assertFalse(sheetIds(activity, minimal()).contains(TerminalContextMenu.SHARE_MENU));
    }

    private static List<Integer> choiceIds(Activity activity, int menuId, TerminalContextMenu.State state) {
        TermuxContextMenuSheet sheet = TerminalContextMenu.populateChoiceSheet(menuId,
            new TermuxContextMenuSheet(activity), activity.getResources(), state, "hello", "a\nb\n");
        return sheet == null ? new ArrayList<>() : sheet.getItemIds();
    }

    private static List<String> texts(BottomSheetDialog dialog, int viewId) {
        List<String> out = new ArrayList<>();
        for (View view : allViews(dialog.findViewById(R.id.context_sheet_items))) {
            if (view.getId() == viewId && view.getVisibility() == View.VISIBLE)
                out.add(((TextView) view).getText().toString());
        }
        return out;
    }

    @Test
    public void shareSheetPreviewsTheSelectionAndTheTranscript() {
        Activity activity = activity();
        BottomSheetDialog dialog = TerminalContextMenu.populateChoiceSheet(TerminalContextMenu.SHARE_MENU,
            new TermuxContextMenuSheet(activity), activity.getResources(), session(null, true, 1, 1),
            "\n6806d1b0 Normalize  \nline two\nthree\nfour", "$ git log\nfirst\nsecond\n~ $ \n\n").show(id -> { });

        View header = dialog.findViewById(R.id.context_sheet_header);
        Assert.assertEquals("Share", ((TextView) header.findViewById(R.id.context_header_title)).getText().toString());
        Assert.assertEquals("zsh", ((TextView) header.findViewById(R.id.context_header_subtitle)).getText().toString());
        Assert.assertEquals(View.GONE, header.findViewById(R.id.context_header_action).getVisibility());

        Assert.assertEquals(Arrays.asList("Selected text", "Transcript"), texts(dialog, R.id.context_tile_title));
        Assert.assertEquals(Arrays.asList("41 characters", "4 lines"), texts(dialog, R.id.context_tile_detail));
        Assert.assertEquals(Arrays.asList("6806d1b0 Normalize\nline two\nthree", "first\nsecond\n~ $"),
            texts(dialog, R.id.context_card_preview));
        Assert.assertEquals(Arrays.asList(TerminalContextMenu.SHARE_SELECTED_TEXT, TerminalContextMenu.SHARE_TRANSCRIPT),
            choiceIds(activity, TerminalContextMenu.SHARE_MENU, session(null, true, 1, 1)));
    }

    @Test
    public void autofillSheetOffersUsernameAndPassword() {
        Activity activity = activity();
        BottomSheetDialog dialog = TerminalContextMenu.populateChoiceSheet(TerminalContextMenu.AUTOFILL_MENU,
            new TermuxContextMenuSheet(activity), activity.getResources(), session("build", true, 1, 1),
            null, null).show(id -> { });
        Assert.assertEquals(Arrays.asList("Username", "Password"), texts(dialog, R.id.context_tile_title));
        Assert.assertEquals(Arrays.asList("login: ▌", "password: ••••••••"), texts(dialog, R.id.context_card_preview));
        Assert.assertEquals(Arrays.asList(TerminalContextMenu.AUTOFILL_USERNAME, TerminalContextMenu.AUTOFILL_PASSWORD),
            choiceIds(activity, TerminalContextMenu.AUTOFILL_MENU, everything()));
        Assert.assertNull(TerminalContextMenu.populateChoiceSheet(TerminalContextMenu.SETTINGS,
            new TermuxContextMenuSheet(activity), activity.getResources(), everything(), null, null));
    }

    @Test
    public void previewLinesSkipBlankEdgesAndTrailingSpaces() {
        Assert.assertNull(TerminalContextMenu.firstLines(null, 3));
        Assert.assertNull(TerminalContextMenu.firstLines(" \n\n", 3));
        Assert.assertEquals(" a\nb", TerminalContextMenu.firstLines("\n a  \nb\nc", 2));
        Assert.assertNull(TerminalContextMenu.lastLines("\n \n", 2));
        Assert.assertEquals("a\nc", TerminalContextMenu.lastLines("a\n\nc\n\n", 2));
        Assert.assertEquals("https://github.co…",
            TerminalContextMenu.firstLines("https://github.com/pschmitt/termux-app", 3));
        Assert.assertEquals(TerminalContextMenu.PREVIEW_COLUMNS,
            TerminalContextMenu.previewLine("https://github.com/pschmitt/termux-app").length());
        Assert.assertEquals("b\nc", TerminalContextMenu.lastLines("a\nb\nc \n\n", 2));
        Assert.assertEquals(3, TerminalContextMenu.countLines("a\n\nc\n \n"));
        Assert.assertEquals(0, TerminalContextMenu.countLines(""));
        Assert.assertEquals(-1, TerminalContextMenu.countLines(null));
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
            // The sheet reaches some actions through a sheet of choices; killing without asking is only
            // offered by holding the kill tile.
            java.util.Set<Integer> sheet = new java.util.HashSet<>(sheetIds(activity, state));
            for (int menuId : new int[]{TerminalContextMenu.SHARE_MENU, TerminalContextMenu.AUTOFILL_MENU}) {
                if (sheet.remove(menuId)) sheet.addAll(choiceIds(activity, menuId, state));
            }
            sheet.remove(TerminalContextMenu.KILL_PROCESS_NOW);
            Assert.assertEquals(new java.util.HashSet<>(menuIds(activity, state)), sheet);
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
