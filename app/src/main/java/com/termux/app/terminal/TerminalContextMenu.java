package com.termux.app.terminal;

import android.content.res.Resources;
import android.view.Menu;

import androidx.annotation.NonNull;

import com.termux.R;

/**
 * The entries of the terminal long-press menu, shared by the bottom sheet and by the system context
 * menu that is used as a fallback, so that both offer the same actions.
 *
 * Help and Report issue are deliberately not part of this menu: Report issue lives in Settings
 * (see {@link ReportIssueRequest}) and Help is reachable from the documentation.
 */
public final class TerminalContextMenu {

    public static final int SELECT_URL = 0;
    public static final int SHARE_TRANSCRIPT = 1;
    public static final int AUTOFILL_PASSWORD = 2;
    public static final int RESET_TERMINAL = 3;
    public static final int KILL_PROCESS = 4;
    public static final int TOGGLE_KEEP_SCREEN_ON = 6;
    public static final int SETTINGS = 8;
    public static final int SHARE_SELECTED_TEXT = 10;
    public static final int AUTOFILL_USERNAME = 11;

    /** What decides which entries are shown, and how. */
    public static final class State {
        public final boolean autoFillEnabled;
        public final boolean hasSelectedText;
        public final int sessionPid;
        public final boolean sessionRunning;
        public final boolean keepScreenOn;

        public State(boolean autoFillEnabled, boolean hasSelectedText,
                     int sessionPid, boolean sessionRunning, boolean keepScreenOn) {
            this.autoFillEnabled = autoFillEnabled;
            this.hasSelectedText = hasSelectedText;
            this.sessionPid = sessionPid;
            this.sessionRunning = sessionRunning;
            this.keepScreenOn = keepScreenOn;
        }
    }

    private TerminalContextMenu() {}

    /** Add the grouped entries of the bottom sheet. */
    public static TermuxContextMenuSheet populateSheet(@NonNull TermuxContextMenuSheet sheet,
                                                       @NonNull Resources resources, @NonNull State state) {
        sheet.addGroup(R.string.ui_menu_group_text)
            .addItem(SELECT_URL, R.string.action_select_url, R.drawable.ui_ic_link);
        if (state.hasSelectedText)
            sheet.addItem(SHARE_SELECTED_TEXT, R.string.action_share_selected_text, R.drawable.ui_ic_share);
        sheet.addItem(SHARE_TRANSCRIPT, R.string.action_share_transcript, R.drawable.ui_ic_share);
        if (state.autoFillEnabled) {
            sheet.addDualAction(R.string.ui_menu_autofill,
                AUTOFILL_USERNAME, R.string.action_autofill_username, R.drawable.ui_ic_person,
                AUTOFILL_PASSWORD, R.string.action_autofill_password, R.drawable.ui_ic_lock);
        }
        sheet.addGroup(R.string.ui_menu_group_session)
            .addItem(RESET_TERMINAL, R.string.action_reset_terminal, R.drawable.ui_ic_refresh)
            .addItem(KILL_PROCESS, resources.getString(R.string.action_kill_process, state.sessionPid),
                R.drawable.ui_ic_power, state.sessionRunning, true)
            .addToggle(TOGGLE_KEEP_SCREEN_ON, R.string.action_toggle_keep_screen_on, R.drawable.ui_ic_lightbulb,
                state.keepScreenOn)
            .addGroup(R.string.ui_menu_group_app)
            .addItem(SETTINGS, R.string.action_open_settings, R.drawable.ui_ic_settings);
        return sheet;
    }

    /** Add the flat entries of the system context menu fallback. */
    public static void populateMenu(@NonNull Menu menu, @NonNull Resources resources, @NonNull State state) {
        menu.add(Menu.NONE, SELECT_URL, Menu.NONE, R.string.action_select_url);
        menu.add(Menu.NONE, SHARE_TRANSCRIPT, Menu.NONE, R.string.action_share_transcript);
        if (state.hasSelectedText)
            menu.add(Menu.NONE, SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text);
        if (state.autoFillEnabled) {
            menu.add(Menu.NONE, AUTOFILL_USERNAME, Menu.NONE, R.string.action_autofill_username);
            menu.add(Menu.NONE, AUTOFILL_PASSWORD, Menu.NONE, R.string.action_autofill_password);
        }
        menu.add(Menu.NONE, RESET_TERMINAL, Menu.NONE, R.string.action_reset_terminal);
        menu.add(Menu.NONE, KILL_PROCESS, Menu.NONE, resources.getString(R.string.action_kill_process, state.sessionPid))
            .setEnabled(state.sessionRunning);
        menu.add(Menu.NONE, TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on)
            .setCheckable(true).setChecked(state.keepScreenOn);
        menu.add(Menu.NONE, SETTINGS, Menu.NONE, R.string.action_open_settings);
    }

}
