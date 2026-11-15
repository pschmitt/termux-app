package com.termux.app.terminal;

import android.content.res.Resources;
import android.text.TextUtils;
import android.view.Menu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;

import java.util.ArrayList;
import java.util.List;

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
    /** Holding the kill tile: kill without asking. Only the sheet offers it, as a long press. */
    public static final int KILL_PROCESS_NOW = 12;

    /** What decides which entries are shown, and how. */
    public static final class State {
        public final boolean autoFillEnabled;
        public final boolean hasSelectedText;
        public final int sessionPid;
        public final boolean sessionRunning;
        public final boolean keepScreenOn;
        /** The name the user gave the session, or null. */
        @Nullable public final String sessionName;
        /** The name of the session's process, such as zsh, or null if unknown. */
        @Nullable public final String processName;
        /** The working directory of the session's process, as it should be shown, or null if unknown. */
        @Nullable public final String cwd;
        /** The 1-based position of the session, or 0 if unknown. */
        public final int sessionNumber;
        public final int sessionCount;
        /** The number of URLs that Open URL would list, or -1 if unknown. */
        public final int urlCount;

        public State(boolean autoFillEnabled, boolean hasSelectedText,
                     int sessionPid, boolean sessionRunning, boolean keepScreenOn) {
            this(autoFillEnabled, hasSelectedText, sessionPid, sessionRunning, keepScreenOn,
                null, null, null, 0, 0, -1);
        }

        public State(boolean autoFillEnabled, boolean hasSelectedText,
                     int sessionPid, boolean sessionRunning, boolean keepScreenOn,
                     @Nullable String sessionName, @Nullable String processName, @Nullable String cwd,
                     int sessionNumber, int sessionCount, int urlCount) {
            this.autoFillEnabled = autoFillEnabled;
            this.hasSelectedText = hasSelectedText;
            this.sessionPid = sessionPid;
            this.sessionRunning = sessionRunning;
            this.keepScreenOn = keepScreenOn;
            this.sessionName = sessionName;
            this.processName = processName;
            this.cwd = cwd;
            this.sessionNumber = sessionNumber;
            this.sessionCount = sessionCount;
            this.urlCount = urlCount;
        }
    }

    private TerminalContextMenu() {}

    /** The header title: the session name, else its process, else its number. */
    @NonNull
    static String headerTitle(@NonNull Resources resources, @NonNull State state) {
        if (!TextUtils.isEmpty(state.sessionName)) return state.sessionName;
        if (!TextUtils.isEmpty(state.processName)) return state.processName;
        return resources.getString(R.string.ui_session_unnamed, Math.max(state.sessionNumber, 1));
    }

    /** The header detail line, such as {@code ~/devel · pid 4182 · 2 of 3}. */
    @NonNull
    static String headerDetail(@NonNull Resources resources, @NonNull State state) {
        List<String> parts = new ArrayList<>();
        if (!TextUtils.isEmpty(state.cwd)) parts.add(state.cwd);
        parts.add(state.sessionRunning && state.sessionPid > 0
            ? resources.getString(R.string.ui_menu_session_pid, state.sessionPid)
            : resources.getString(R.string.ui_menu_not_running));
        if (state.sessionCount > 1 && state.sessionNumber > 0)
            parts.add(resources.getString(R.string.ui_menu_session_position, state.sessionNumber, state.sessionCount));
        return TextUtils.join(resources.getString(R.string.ui_menu_session_detail_separator), parts);
    }

    /** Add the entries of the bottom sheet: a session header, a grid of labelled actions and the toggle. */
    public static TermuxContextMenuSheet populateSheet(@NonNull TermuxContextMenuSheet sheet,
                                                       @NonNull Resources resources, @NonNull State state) {
        sheet.setHeader(headerTitle(resources, state), headerDetail(resources, state),
            SETTINGS, resources.getString(R.string.action_open_settings), R.drawable.ui_ic_settings);

        String urls = state.urlCount < 0 ? null
            : (state.urlCount == 0 ? resources.getString(R.string.ui_menu_urls_none)
            : resources.getQuantityString(R.plurals.ui_menu_urls_found, state.urlCount, state.urlCount));
        sheet.addTile(SELECT_URL, resources.getString(R.string.ui_menu_open_url), urls, R.drawable.ui_ic_link);
        if (state.hasSelectedText) {
            sheet.addTile(SHARE_SELECTED_TEXT, resources.getString(R.string.ui_menu_share),
                resources.getString(R.string.ui_menu_share_selection_detail), R.drawable.ui_ic_selected_text);
        }
        sheet.addTile(SHARE_TRANSCRIPT, resources.getString(R.string.ui_menu_share),
            resources.getString(R.string.ui_menu_share_transcript_detail), R.drawable.ui_ic_transcript);
        if (state.autoFillEnabled) {
            sheet.addTile(AUTOFILL_USERNAME, resources.getString(R.string.ui_menu_username),
                resources.getString(R.string.ui_menu_autofill_detail), R.drawable.ui_ic_person);
            sheet.addTile(AUTOFILL_PASSWORD, resources.getString(R.string.ui_menu_password),
                resources.getString(R.string.ui_menu_autofill_detail), R.drawable.ui_ic_lock);
        }
        sheet.addTile(RESET_TERMINAL, resources.getString(R.string.action_reset_terminal),
            resources.getString(R.string.ui_menu_reset_detail), R.drawable.ui_ic_refresh);
        String killTarget = TextUtils.isEmpty(state.processName)
            ? resources.getString(R.string.ui_menu_kill_session_target) : state.processName;
        sheet.addDangerTile(KILL_PROCESS, resources.getString(R.string.ui_menu_kill, killTarget),
            resources.getString(state.sessionRunning ? R.string.ui_menu_kill_detail : R.string.ui_menu_not_running),
            R.drawable.ui_ic_delete, state.sessionRunning,
            KILL_PROCESS_NOW, resources.getString(R.string.ui_menu_kill_now));
        sheet.addToggle(TOGGLE_KEEP_SCREEN_ON, R.string.action_toggle_keep_screen_on, R.drawable.ui_ic_lightbulb,
            state.keepScreenOn);
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
