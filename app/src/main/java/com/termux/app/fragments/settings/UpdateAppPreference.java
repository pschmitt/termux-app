package com.termux.app.fragments.settings;

import android.content.Context;
import android.text.format.Formatter;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.termux.R;
import com.termux.app.update.UpdateState;

/** The update status card, including the current action and download progress. */
public class UpdateAppPreference extends Preference {
    private UpdateState updateState = UpdateState.IDLE;
    private int actionTitle;
    private Runnable action;

    public UpdateAppPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void render(@NonNull UpdateState state, @StringRes int title, Runnable action) {
        updateState = state;
        actionTitle = title;
        this.action = action;
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        MaterialButton button = (MaterialButton) holder.findViewById(R.id.update_action);
        button.setVisibility(actionTitle == 0 ? android.view.View.GONE : android.view.View.VISIBLE);
        if (actionTitle != 0) button.setText(actionTitle);
        button.setOnClickListener(action == null ? null : view -> action.run());

        LinearProgressIndicator progress = (LinearProgressIndicator) holder.findViewById(R.id.update_progress);
        if (progress == null) return;
        boolean downloading = updateState.phase == UpdateState.Phase.DOWNLOADING
                || updateState.phase == UpdateState.Phase.VERIFYING;
        progress.setVisibility(downloading ? android.view.View.VISIBLE : android.view.View.GONE);
        if (!downloading) return;
        if (updateState.phase == UpdateState.Phase.DOWNLOADING && updateState.total > 0) {
            progress.setIndeterminate(false);
            progress.setMax(1000);
            progress.setProgressCompat((int) Math.min(1000, updateState.done * 1000 / updateState.total), false);
            progress.setContentDescription(Formatter.formatShortFileSize(getContext(), updateState.done)
                    + " / " + Formatter.formatShortFileSize(getContext(), updateState.total));
        } else {
            progress.setIndeterminate(true);
            progress.setContentDescription(updateState.phase == UpdateState.Phase.VERIFYING
                    ? getContext().getString(R.string.termux_update_status_verifying)
                    : getContext().getString(R.string.termux_update_status_downloading_unknown,
                            Formatter.formatShortFileSize(getContext(), updateState.done)));
        }
    }
}
