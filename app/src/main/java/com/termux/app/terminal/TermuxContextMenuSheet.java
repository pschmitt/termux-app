package com.termux.app.terminal;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.termux.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Terminal context menu presented as a grouped Material bottom sheet.
 * <p>
 * Items keep the ids of the system context menu entries they replace. A chosen action is delivered
 * to the {@link Listener} only after the sheet is dismissed, which matches the order of the system
 * menu (item selected, then menu closed) and lets the host release any stored selected text last.
 */
public class TermuxContextMenuSheet {

    public interface Listener {
        /** Called after the sheet has been dismissed, with the chosen item id or -1 if none. */
        void onSheetDismissed(int chosenItemId);
    }

    private static final class Entry {
        final int id;
        final CharSequence title;
        @DrawableRes final int icon;
        final boolean enabled;
        final boolean danger;
        @Nullable final Boolean checked;

        Entry(int id, CharSequence title, int icon, boolean enabled, boolean danger, @Nullable Boolean checked) {
            this.id = id;
            this.title = title;
            this.icon = icon;
            this.enabled = enabled;
            this.danger = danger;
            this.checked = checked;
        }
    }

    /** One label with two independently clickable icon buttons, like Autofill username / password. */
    private static final class DualEntry {
        final CharSequence label;
        @DrawableRes final int icon;
        final Action first;
        final Action second;

        DualEntry(CharSequence label, int icon, Action first, Action second) {
            this.label = label;
            this.icon = icon;
            this.first = first;
            this.second = second;
        }
    }

    private static final class Action {
        final int id;
        final CharSequence description;
        @DrawableRes final int icon;
        final boolean enabled;

        Action(int id, CharSequence description, int icon, boolean enabled) {
            this.id = id;
            this.description = description;
            this.icon = icon;
            this.enabled = enabled;
        }
    }

    /** The largest share of the available height that the sheet starts out with. */
    static final float MAX_HEIGHT_FRACTION = 0.65f;
    /** The sheet is never smaller than this (or the available height), so that it stays usable in landscape. */
    static final int MIN_MAX_HEIGHT_DP = 300;
    /** The part of the screen above the sheet that stays visible, so that the scrim can be tapped. */
    static final int TOP_GAP_DP = 48;

    private final Activity mActivity;
    // Either a group title (String/CharSequence), an Entry or a DualEntry.
    private final List<Object> mRows = new ArrayList<>();

    public TermuxContextMenuSheet(@NonNull Activity activity) {
        mActivity = activity;
    }

    public TermuxContextMenuSheet addGroup(@StringRes int title) {
        mRows.add(mActivity.getString(title));
        return this;
    }

    public TermuxContextMenuSheet addItem(int id, CharSequence title, @DrawableRes int icon) {
        mRows.add(new Entry(id, title, icon, true, false, null));
        return this;
    }

    public TermuxContextMenuSheet addItem(int id, @StringRes int title, @DrawableRes int icon) {
        return addItem(id, mActivity.getString(title), icon);
    }

    public TermuxContextMenuSheet addItem(int id, CharSequence title, @DrawableRes int icon, boolean enabled, boolean danger) {
        mRows.add(new Entry(id, title, icon, enabled, danger, null));
        return this;
    }

    public TermuxContextMenuSheet addToggle(int id, @StringRes int title, @DrawableRes int icon, boolean checked) {
        mRows.add(new Entry(id, mActivity.getString(title), icon, true, false, checked));
        return this;
    }

    /**
     * Add one row with a label and two icon buttons, each choosing its own item id.
     * The row itself is not clickable.
     */
    public TermuxContextMenuSheet addDualAction(@StringRes int label,
                                                int firstId, @StringRes int firstDescription, @DrawableRes int firstIcon,
                                                int secondId, @StringRes int secondDescription, @DrawableRes int secondIcon) {
        return addDualAction(label, 0,
            firstId, firstDescription, firstIcon, true,
            secondId, secondDescription, secondIcon, true);
    }

    /** Add one row with independently labelled and enabled icon buttons. */
    public TermuxContextMenuSheet addDualAction(@StringRes int label,
                                                int firstId, @StringRes int firstDescription, @DrawableRes int firstIcon,
                                                boolean firstEnabled,
                                                int secondId, @StringRes int secondDescription, @DrawableRes int secondIcon,
                                                boolean secondEnabled) {
        return addDualAction(label, 0, firstId, firstDescription, firstIcon, firstEnabled,
            secondId, secondDescription, secondIcon, secondEnabled);
    }

    /** Add one row with a leading icon and independently labelled and enabled icon buttons. */
    public TermuxContextMenuSheet addDualAction(@StringRes int label, @DrawableRes int rowIcon,
                                                int firstId, @StringRes int firstDescription, @DrawableRes int firstIcon,
                                                boolean firstEnabled,
                                                int secondId, @StringRes int secondDescription, @DrawableRes int secondIcon,
                                                boolean secondEnabled) {
        mRows.add(new DualEntry(mActivity.getString(label), rowIcon,
            new Action(firstId, mActivity.getString(firstDescription), firstIcon, firstEnabled),
            new Action(secondId, mActivity.getString(secondDescription), secondIcon, secondEnabled)));
        return this;
    }

    /** The ids of all items and buttons that the sheet offers, in order. */
    List<Integer> getItemIds() {
        List<Integer> ids = new ArrayList<>();
        for (Object row : mRows) {
            if (row instanceof Entry) {
                ids.add(((Entry) row).id);
            } else if (row instanceof DualEntry) {
                ids.add(((DualEntry) row).first.id);
                ids.add(((DualEntry) row).second.id);
            }
        }
        return ids;
    }

    /**
     * The height that the sheet may start out with, in pixels, or -1 for no limit if the available
     * height is unknown.
     *
     * @param availableHeightPx The height of the window that is not covered by system bars or the
     *                          soft keyboard.
     * @param density The display density, to turn dp into pixels.
     */
    static int computeMaxHeight(int availableHeightPx, float density) {
        if (availableHeightPx <= 0) return -1;
        int fractional = Math.round(availableHeightPx * MAX_HEIGHT_FRACTION);
        int floor = Math.min(availableHeightPx, Math.round(MIN_MAX_HEIGHT_DP * density));
        int ceiling = Math.max(availableHeightPx - Math.round(TOP_GAP_DP * density), 0);
        // The gap above wins over the floor, so that the top never goes offscreen.
        return Math.min(Math.max(fractional, floor), ceiling);
    }

    private int getAvailableHeight() {
        View decor = mActivity.getWindow().getDecorView();
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(decor);
        int height = decor.getHeight();
        if (insets != null && height > 0) {
            // The software keyboard is deliberately not subtracted: it closes when the sheet takes focus, and sizing
            // the sheet against the keyboard-reduced frame would leave it needlessly small afterwards. The sheet pads
            // itself for the keyboard instead (see the insets listener in show()).
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            return Math.max(height - bars.top - bars.bottom, 0);
        }
        Rect frame = new Rect();
        decor.getWindowVisibleDisplayFrame(frame);
        return frame.height();
    }

    /** Show the sheet. The listener is always invoked once on dismissal. */
    @SuppressLint("InflateParams")
    public BottomSheetDialog show(@NonNull Listener listener) {
        final int[] chosen = {-1};
        final boolean[] notified = {false};

        BottomSheetDialog dialog = new BottomSheetDialog(mActivity);
        View content = LayoutInflater.from(dialog.getContext()).inflate(R.layout.ui_context_sheet, null, false);
        LinearLayout container = content.findViewById(R.id.context_sheet_items);

        for (Object row : mRows) {
            if (row instanceof Entry) {
                container.addView(createItem(container, dialog, (Entry) row, chosen));
            } else if (row instanceof DualEntry) {
                container.addView(createDualItem(container, dialog, (DualEntry) row, chosen));
            } else {
                TextView header = (TextView) LayoutInflater.from(dialog.getContext())
                    .inflate(R.layout.ui_context_sheet_group, container, false);
                header.setText((CharSequence) row);
                container.addView(header);
            }
        }

        dialog.setContentView(content);
        // No visible heading, but screen readers still announce what this window is.
        String title = mActivity.getString(R.string.ui_menu_title);
        dialog.setTitle(title);
        ViewCompat.setAccessibilityPaneTitle(content, title);

        // Keep the bottom of the list reachable above the navigation bar or the soft keyboard when
        // the sheet is drawn under them. With no inset the padding is left as the layout has it.
        final int basePaddingBottom = container.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            int bottom = Math.max(
                insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom,
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom);
            container.setPadding(container.getPaddingLeft(), container.getPaddingTop(),
                container.getPaddingRight(), basePaddingBottom + bottom);
            return insets;
        });

        // The dialog window is never resized for the keyboard: with ADJUST_RESIZE the system shrinks it to the
        // space above a keyboard that the sheet hides anyway, and the sheet ends up pinned to the top. The
        // insets listener above keeps the list clear of the keyboard if it stays visible, and the height limit
        // is computed from the space that is not covered by it.
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED);
        }

        final int maxHeight = computeMaxHeight(getAvailableHeight(), mActivity.getResources().getDisplayMetrics().density);
        dialog.setOnDismissListener(d -> {
            // Dismissal can be reported more than once (for example back press); only act on the first.
            if (notified[0]) return;
            notified[0] = true;
            listener.onSheetDismissed(chosen[0]);
        });
        // Configure the behavior before the first measure: the maximum height is only read while measuring.
        BottomSheetBehavior<?> behavior = dialog.getBehavior();
        behavior.setFitToContents(true);
        behavior.setDraggable(true);
        behavior.setHideable(true);
        // A downward drag dismisses, there is no collapsed state in between.
        behavior.setSkipCollapsed(true);
        // Material removes the rounded corners of a sheet once it is expanded, and the sheet always is: keep them.
        behavior.setShouldRemoveExpandedCorners(false);
        if (maxHeight > 0) behavior.setMaxHeight(maxHeight);
        dialog.setOnShowListener(d -> behavior.setState(BottomSheetBehavior.STATE_EXPANDED));
        dialog.show();
        return dialog;
    }

    private View createItem(ViewGroup parent, BottomSheetDialog dialog, Entry entry, int[] chosen) {
        View view = LayoutInflater.from(dialog.getContext()).inflate(R.layout.ui_context_sheet_item, parent, false);
        ImageView icon = view.findViewById(R.id.context_item_icon);
        TextView title = view.findViewById(R.id.context_item_title);
        SwitchMaterial toggle = view.findViewById(R.id.context_item_switch);

        title.setText(entry.title);
        icon.setImageResource(entry.icon);

        int tintRes = !entry.enabled ? R.color.ui_outline : (entry.danger ? R.color.ui_danger : R.color.ui_on_surface_variant);
        icon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(dialog.getContext(), tintRes)));
        if (entry.danger && entry.enabled) {
            title.setTextColor(ContextCompat.getColor(dialog.getContext(), R.color.ui_danger));
        }

        view.setEnabled(entry.enabled);
        title.setEnabled(entry.enabled);

        if (entry.checked != null) {
            toggle.setVisibility(View.VISIBLE);
            toggle.setChecked(entry.checked);
            ViewCompat.setAccessibilityDelegate(view, new AccessibilityDelegateCompat() {
                @Override
                public void onInitializeAccessibilityNodeInfo(@NonNull View host, @NonNull AccessibilityNodeInfoCompat info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName("android.widget.Switch");
                    info.setCheckable(true);
                    info.setChecked(entry.checked);
                }
            });
        }

        if (entry.enabled) {
            view.setOnClickListener(v -> {
                chosen[0] = entry.id;
                dialog.dismiss();
            });
        } else {
            view.setClickable(false);
        }
        return view;
    }

    private View createDualItem(ViewGroup parent, BottomSheetDialog dialog, DualEntry entry, int[] chosen) {
        View view = LayoutInflater.from(dialog.getContext()).inflate(R.layout.ui_context_sheet_dual_item, parent, false);
        ((TextView) view.findViewById(R.id.context_dual_label)).setText(entry.label);
        ImageView rowIcon = view.findViewById(R.id.context_dual_icon);
        if (entry.icon == 0) {
            rowIcon.setVisibility(View.GONE);
        } else {
            rowIcon.setImageResource(entry.icon);
            rowIcon.setImageTintList(ColorStateList.valueOf(
                ContextCompat.getColor(dialog.getContext(), R.color.ui_on_surface_variant)));
        }
        bindDualAction(dialog, view.findViewById(R.id.context_dual_first), entry.first, chosen);
        bindDualAction(dialog, view.findViewById(R.id.context_dual_second), entry.second, chosen);
        return view;
    }

    private static void bindDualAction(BottomSheetDialog dialog, ImageView button, Action action, int[] chosen) {
        button.setImageResource(action.icon);
        button.setContentDescription(action.description);
        button.setEnabled(action.enabled);
        button.setClickable(action.enabled);
        button.setFocusable(action.enabled);
        int tintRes = !action.enabled ? R.color.ui_outline
            : (action.id == TerminalContextMenu.KILL_PROCESS ? R.color.ui_danger : R.color.ui_on_surface_variant);
        button.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(dialog.getContext(), tintRes)));
        if (action.enabled) {
            button.setOnClickListener(v -> {
                chosen[0] = action.id;
                dialog.dismiss();
            });
        }
    }
}
