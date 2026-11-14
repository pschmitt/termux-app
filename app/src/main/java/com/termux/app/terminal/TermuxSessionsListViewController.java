package com.termux.app.terminal;

import android.annotation.SuppressLint;
import android.graphics.Paint;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.util.List;

public class TermuxSessionsListViewController extends ArrayAdapter<TermuxSession> implements AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener {

    final TermuxActivity mActivity;

    public TermuxSessionsListViewController(TermuxActivity activity, List<TermuxSession> sessionList) {
        super(activity.getApplicationContext(), R.layout.item_terminal_sessions_list, sessionList);
        this.mActivity = activity;
    }

    @SuppressLint("SetTextI18n")
    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        View sessionRowView = convertView;
        if (sessionRowView == null) {
            LayoutInflater inflater = mActivity.getLayoutInflater();
            sessionRowView = inflater.inflate(R.layout.item_terminal_sessions_list, parent, false);
        }

        TextView sessionIndexView = sessionRowView.findViewById(R.id.session_index);
        TextView sessionTitleView = sessionRowView.findViewById(R.id.session_title);
        TextView sessionSubtitleView = sessionRowView.findViewById(R.id.session_subtitle);

        int number = position + 1;
        sessionIndexView.setText(String.valueOf(number));

        TerminalSession sessionAtRow = getItem(position).getTerminalSession();
        if (sessionAtRow == null) {
            sessionTitleView.setText(R.string.ui_session_null);
            sessionTitleView.setPaintFlags(sessionTitleView.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            sessionTitleView.setTextColor(ContextCompat.getColor(mActivity, R.color.ui_on_surface));
            sessionSubtitleView.setVisibility(View.GONE);
            sessionRowView.setContentDescription(number + ", " + mActivity.getString(R.string.ui_session_null));
            return sessionRowView;
        }

        String name = sessionAtRow.mSessionName;
        String terminalTitle = sessionAtRow.getTitle();
        boolean hasName = !TextUtils.isEmpty(name);
        boolean hasTitle = !TextUtils.isEmpty(terminalTitle);
        boolean sessionRunning = sessionAtRow.isRunning();

        // The name is what the user chose, so it leads; the terminal title (usually the running
        // program or directory) follows beneath it. Unnamed sessions lead with their title.
        String title;
        String subtitle = "";
        if (hasName) {
            title = name;
            if (hasTitle) subtitle = terminalTitle;
        } else if (hasTitle) {
            title = terminalTitle;
        } else {
            title = mActivity.getString(R.string.ui_session_unnamed, number);
        }

        if (!sessionRunning) {
            String exited = mActivity.getString(R.string.ui_session_exited, sessionAtRow.getExitStatus());
            subtitle = subtitle.isEmpty() ? exited : subtitle + " - " + exited;
        }

        sessionTitleView.setText(title);
        sessionSubtitleView.setText(subtitle);
        sessionSubtitleView.setVisibility(subtitle.isEmpty() ? View.GONE : View.VISIBLE);

        if (sessionRunning) {
            sessionTitleView.setPaintFlags(sessionTitleView.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        } else {
            sessionTitleView.setPaintFlags(sessionTitleView.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        }
        boolean failed = !sessionRunning && sessionAtRow.getExitStatus() != 0;
        sessionTitleView.setTextColor(ContextCompat.getColor(mActivity, failed ? R.color.ui_danger : R.color.ui_on_surface));

        sessionRowView.setContentDescription(subtitle.isEmpty() ? number + ", " + title : number + ", " + title + ", " + subtitle);
        return sessionRowView;
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        TermuxSession clickedSession = getItem(position);
        mActivity.getTermuxTerminalSessionClient().setCurrentSession(clickedSession.getTerminalSession());
        mActivity.getDrawer().closeDrawers();
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        final TermuxSession selectedSession = getItem(position);
        mActivity.getTermuxTerminalSessionClient().renameSession(selectedSession.getTerminalSession());
        return true;
    }

}
