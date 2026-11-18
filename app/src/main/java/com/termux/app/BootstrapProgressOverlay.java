package com.termux.app;

import android.app.Activity;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.termux.R;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The first-start progress card, shown centred over the terminal area (not the whole screen): the
 * setup steps with their state, a progress bar and a terminal-style log of what is installed.
 * All methods must be called on the UI thread.
 */
final class BootstrapProgressOverlay {

    enum Step {
        EXTRACT(R.string.bootstrap_step_extract),
        CONFIGURE(R.string.bootstrap_step_configure),
        NIX_PREPARE(R.string.bootstrap_step_nix_prepare),
        NIX_PACKAGES(R.string.bootstrap_step_nix_packages),
        NIX_CHECK(R.string.bootstrap_step_nix_check);

        @StringRes final int label;

        Step(@StringRes int label) {
            this.label = label;
        }
    }

    private static final int LOG_LINES = 7;
    private static final long FADE_MS = 220;

    private final Activity activity;
    private final View root;
    private final TextView subtitle;
    private final TextView title;
    private final LinearProgressIndicator progress;
    private final TextView log;
    private final List<Step> steps = new ArrayList<>();
    private final List<View> rows = new ArrayList<>();
    private final Deque<CharSequence> lines = new ArrayDeque<>();
    @Nullable private Step current;

    private BootstrapProgressOverlay(Activity activity, View root) {
        this.activity = activity;
        this.root = root;
        this.title = root.findViewById(R.id.bootstrap_title);
        this.subtitle = root.findViewById(R.id.bootstrap_subtitle);
        this.progress = root.findViewById(R.id.bootstrap_progress);
        this.log = root.findViewById(R.id.bootstrap_log);
        addStep(Step.EXTRACT);
    }

    private void addStep(Step step) {
        LinearLayout list = root.findViewById(R.id.bootstrap_steps);
        View row = LayoutInflater.from(activity).inflate(R.layout.bootstrap_progress_step, list, false);
        ((TextView) row.findViewById(R.id.bootstrap_step_label)).setText(step.label);
        list.addView(row);
        steps.add(step);
        rows.add(row);
        setState(row, State.PENDING);
    }

    /** Show the card over the terminal area, with the bootstrap extraction as its first step. */
    @NonNull
    static BootstrapProgressOverlay show(@NonNull Activity activity) {
        View root = LayoutInflater.from(activity).inflate(R.layout.bootstrap_progress_overlay, null, false);
        attach(activity, root);
        BootstrapProgressOverlay overlay = new BootstrapProgressOverlay(activity, root);
        overlay.hideKeyboard();
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(FADE_MS).start();
        overlay.step(Step.EXTRACT);
        overlay.note(activity.getString(R.string.bootstrap_note_extracting));
        return overlay;
    }

    /** The bootstrap provides the Nix guest: add its setup steps. */
    void addNixSteps() {
        for (Step step : new Step[]{Step.CONFIGURE, Step.NIX_PREPARE, Step.NIX_PACKAGES, Step.NIX_CHECK})
            if (!steps.contains(step)) addStep(step);
        if (current != null) step(current);
    }

    /** Over the terminal area (the drawer's bounds, above the extra keys), else over the whole content. */
    private static void attach(Activity activity, View root) {
        View terminalArea = activity.findViewById(R.id.drawer_layout);
        if (terminalArea != null && terminalArea.getParent() instanceof RelativeLayout) {
            RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            params.addRule(RelativeLayout.ALIGN_TOP, R.id.drawer_layout);
            params.addRule(RelativeLayout.ALIGN_BOTTOM, R.id.drawer_layout);
            ((RelativeLayout) terminalArea.getParent()).addView(root, params);
        } else {
            ViewGroup content = activity.findViewById(android.R.id.content);
            content.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    /** Make {@code step} the active one; earlier steps are done. */
    void step(@NonNull Step step) {
        int index = steps.indexOf(step);
        if (index < 0) return;
        for (int i = 0; i < rows.size(); i++)
            setState(rows.get(i), i < index ? State.DONE : i == index ? State.ACTIVE : State.PENDING);
        if (current != step) {
            current = step;
            subtitle.setText(step.label);
            if (step != Step.NIX_PACKAGES) indeterminate();
        }
    }

    /** Package {@code index} of {@code total} is being installed; {@code dependency} is the path being copied. */
    void packages(int index, int total, @Nullable String name, @Nullable String dependency) {
        step(Step.NIX_PACKAGES);
        View row = rows.get(steps.indexOf(Step.NIX_PACKAGES));
        TextView count = row.findViewById(R.id.bootstrap_step_count);
        count.setText(activity.getString(R.string.bootstrap_step_count, index, total));
        count.setVisibility(View.VISIBLE);
        if (progress.isIndeterminate()) {
            progress.setIndeterminate(false);
            progress.setMax(total * 100);
        }
        progress.setProgressCompat(Math.max(0, index - 1) * 100 + (dependency == null ? 10 : 60), true);
        if (name != null) subtitle.setText(name);
        if (dependency == null) {
            if (name != null) command("nix copy", name);
        } else if (!dependency.equals(name)) {
            dependencyLine(dependency);
        }
    }

    /** A plain log line, like a stage of the native setup. */
    void note(@NonNull String text) {
        SpannableStringBuilder line = new SpannableStringBuilder();
        append(line, "# ", R.color.bootstrap_code_dim);
        append(line, text, R.color.bootstrap_code_dim);
        push(line);
    }

    /** Everything is done: check every step and fade out. */
    void finish(@NonNull Runnable then) {
        for (View row : rows) setState(row, State.DONE);
        title.setText(R.string.bootstrap_overlay_title_done);
        subtitle.setText(R.string.nix_setup_ready);
        if (!progress.isIndeterminate()) progress.setProgressCompat(progress.getMax(), true);
        root.postDelayed(() -> dismiss(then), 650);
    }

    /** Remove the card, then run {@code then}. */
    void dismiss(@Nullable Runnable then) {
        root.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
            ViewGroup parent = (ViewGroup) root.getParent();
            if (parent != null) parent.removeView(root);
            // Hand the focus back: the terminal shows the keyboard on focus, as on a normal start.
            View terminal = activity.findViewById(R.id.terminal_view);
            if (terminal != null) terminal.requestFocus();
            if (then != null) then.run();
        }).start();
    }

    private void hideKeyboard() {
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        keyboard().hide(WindowInsetsCompat.Type.ime());
    }

    private WindowInsetsControllerCompat keyboard() {
        return WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView());
    }

    private void indeterminate() {
        if (!progress.isIndeterminate()) {
            progress.setVisibility(View.INVISIBLE);
            progress.setIndeterminate(true);
            progress.setVisibility(View.VISIBLE);
        }
    }

    private void command(String verb, String argument) {
        SpannableStringBuilder line = new SpannableStringBuilder();
        append(line, "$ ", R.color.bootstrap_code_accent);
        append(line, verb + " ", R.color.bootstrap_code_dim);
        append(line, argument, R.color.bootstrap_code_text);
        push(line);
    }

    private void dependencyLine(String dependency) {
        SpannableStringBuilder line = new SpannableStringBuilder();
        append(line, "  ↳ ", R.color.bootstrap_code_dim);
        append(line, dependency, R.color.bootstrap_code_dim);
        push(line);
    }

    private void append(SpannableStringBuilder line, String text, int color) {
        int start = line.length();
        line.append(text);
        line.setSpan(new ForegroundColorSpan(ContextCompat.getColor(activity, color)), start, line.length(),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void push(CharSequence line) {
        if (lines.size() == LOG_LINES) lines.removeFirst();
        lines.addLast(line);
        SpannableStringBuilder text = new SpannableStringBuilder();
        for (CharSequence each : lines) {
            if (text.length() > 0) text.append('\n');
            text.append(each);
        }
        log.setText(text);
    }

    private enum State { PENDING, ACTIVE, DONE }

    private void setState(View row, State state) {
        ImageView icon = row.findViewById(R.id.bootstrap_step_icon);
        CircularProgressIndicator spinner = row.findViewById(R.id.bootstrap_step_spinner);
        TextView label = row.findViewById(R.id.bootstrap_step_label);
        spinner.setVisibility(state == State.ACTIVE ? View.VISIBLE : View.GONE);
        icon.setVisibility(state == State.ACTIVE ? View.INVISIBLE : View.VISIBLE);
        icon.setImageResource(state == State.DONE ? R.drawable.ui_ic_check_circle : R.drawable.ui_ic_circle_outline);
        icon.setColorFilter(ContextCompat.getColor(activity,
            state == State.DONE ? R.color.ui_primary : R.color.ui_outline));
        label.setTextColor(ContextCompat.getColor(activity,
            state == State.PENDING ? R.color.ui_outline : R.color.ui_on_surface));
        if (state == State.DONE) row.findViewById(R.id.bootstrap_step_count).setVisibility(View.GONE);
    }
}
