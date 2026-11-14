package com.termux.app.terminal;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

    private final Activity mActivity;
    // Either a group title (String/CharSequence) or an Entry.
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
            } else {
                TextView header = (TextView) LayoutInflater.from(dialog.getContext())
                    .inflate(R.layout.ui_context_sheet_group, container, false);
                header.setText((CharSequence) row);
                container.addView(header);
            }
        }

        dialog.setContentView(content);
        dialog.setOnDismissListener(d -> {
            // Dismissal can be reported more than once (for example back press); only act on the first.
            if (notified[0]) return;
            notified[0] = true;
            listener.onSheetDismissed(chosen[0]);
        });
        dialog.setOnShowListener(d -> {
            BottomSheetBehavior<?> behavior = dialog.getBehavior();
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        });
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
}
