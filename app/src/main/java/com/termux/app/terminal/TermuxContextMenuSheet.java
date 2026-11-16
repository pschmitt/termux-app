package com.termux.app.terminal;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageButton;
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
 * Terminal context menu presented as a Material bottom sheet: a header naming the session, a two-column grid
 * of labelled actions, and full-width items such as toggles.
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

    /** The session the actions act on, with one icon action (Settings) at its end. */
    private static final class Header {
        @DrawableRes final int icon;
        final CharSequence title;
        final CharSequence detail;
        /** The id of the icon action at the end, or -1 for none. */
        final int actionId;
        @Nullable final CharSequence actionDescription;
        @DrawableRes final int actionIcon;

        Header(int icon, CharSequence title, CharSequence detail, int actionId,
               @Nullable CharSequence actionDescription, int actionIcon) {
            this.icon = icon;
            this.title = title;
            this.detail = detail;
            this.actionId = actionId;
            this.actionDescription = actionDescription;
            this.actionIcon = actionIcon;
        }
    }

    /** One labelled action of the two-column grid. */
    private static final class Tile {
        final int id;
        final CharSequence title;
        @Nullable final CharSequence detail;
        @DrawableRes final int icon;
        final boolean enabled;
        final boolean danger;
        /** The id chosen by a long press, or -1 for none. */
        final int longPressId;
        @Nullable final CharSequence longPressLabel;
        /** A large card of a choice sheet instead of a compact tile. */
        final boolean card;
        /** What a card acts on, shown in a small terminal-like box, or null for none. */
        @Nullable final CharSequence preview;
        /** The state of a toggle tile, or null if the tile is not a toggle. */
        @Nullable Boolean checked;

        Tile(int id, CharSequence title, @Nullable CharSequence detail, int icon, boolean enabled, boolean danger,
             int longPressId, @Nullable CharSequence longPressLabel) {
            this(id, title, detail, icon, enabled, danger, longPressId, longPressLabel, false, null);
        }

        Tile(int id, CharSequence title, @Nullable CharSequence detail, int icon, boolean enabled, boolean danger,
             int longPressId, @Nullable CharSequence longPressLabel, boolean card, @Nullable CharSequence preview) {
            this.card = card;
            this.preview = preview;
            this.id = id;
            this.title = title;
            this.detail = detail;
            this.icon = icon;
            this.enabled = enabled;
            this.danger = danger;
            this.longPressId = longPressId;
            this.longPressLabel = longPressLabel;
        }
    }

    /** The largest share of the available height that the sheet starts out with. */
    static final float MAX_HEIGHT_FRACTION = 0.65f;
    /** The sheet is never smaller than this (or the available height), so that it stays usable in landscape. */
    static final int MIN_MAX_HEIGHT_DP = 300;
    /** The part of the screen above the sheet that stays visible, so that the scrim can be tapped. */
    static final int TOP_GAP_DP = 48;
    /** The space between two tiles of the grid, across and down. */
    static final int TILE_GAP_DP = 8;

    private final Activity mActivity;
    private static final Object ROW_BREAK = new Object();

    private final List<Object> mRows = new ArrayList<>();
    @Nullable private Header mHeader;
    /** The number of lines of the longest card preview. */
    private int mPreviewLines = 1;

    public TermuxContextMenuSheet(@NonNull Activity activity) {
        mActivity = activity;
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

    /** Show which session the actions act on, with one icon action at the end of the header. */
    public TermuxContextMenuSheet setHeader(CharSequence title, CharSequence detail,
                                            int actionId, CharSequence actionDescription, @DrawableRes int actionIcon) {
        mHeader = new Header(R.drawable.ui_ic_menu_terminal, title, detail, actionId, actionDescription, actionIcon);
        return this;
    }

    /** Show a header with its own icon and no action, as the title of a sheet of choices. */
    public TermuxContextMenuSheet setHeader(@DrawableRes int icon, CharSequence title, CharSequence detail) {
        mHeader = new Header(icon, title, detail, -1, null, 0);
        return this;
    }

    /** Add a labelled action to the grid. Consecutive tiles are laid out two per row; a lone last one spans it. */
    public TermuxContextMenuSheet addTile(int id, CharSequence title, @Nullable CharSequence detail,
                                          @DrawableRes int icon) {
        mRows.add(new Tile(id, title, detail, icon, true, false, -1, null));
        return this;
    }

    /**
     * Add a large card to the grid, for a sheet of a few choices: a big icon, the label, a detail line
     * and a preview of what the choice acts on.
     */
    public TermuxContextMenuSheet addCard(int id, CharSequence title, @Nullable CharSequence detail,
                                          @DrawableRes int icon, @Nullable CharSequence preview) {
        mRows.add(new Tile(id, title, detail, icon, true, false, -1, null, true, preview));
        return this;
    }

    /** Add an on/off setting to the grid; its badge is filled while it is on. */
    public TermuxContextMenuSheet addToggleTile(int id, CharSequence title, @DrawableRes int icon, boolean checked,
                                                CharSequence onDetail, CharSequence offDetail) {
        Tile tile = new Tile(id, title, checked ? onDetail : offDetail, icon, true, false, -1, null);
        tile.checked = checked;
        mRows.add(tile);
        return this;
    }

    /** Start a new row of the grid, so that the next tiles are laid out together. */
    public TermuxContextMenuSheet addRowBreak() {
        mRows.add(ROW_BREAK);
        return this;
    }

    /** Add a destructive action to the grid; a long press chooses {@code longPressId} instead. */
    public TermuxContextMenuSheet addDangerTile(int id, CharSequence title, @Nullable CharSequence detail,
                                                @DrawableRes int icon, boolean enabled,
                                                int longPressId, CharSequence longPressLabel) {
        mRows.add(new Tile(id, title, detail, icon, enabled, true, longPressId, longPressLabel));
        return this;
    }

    /** The ids of all items, buttons and long presses that the sheet offers, in order. */
    List<Integer> getItemIds() {
        List<Integer> ids = new ArrayList<>();
        for (Object row : mRows) {
            if (row instanceof Entry) {
                ids.add(((Entry) row).id);
            } else if (row instanceof Tile) {
                ids.add(((Tile) row).id);
                if (((Tile) row).longPressId >= 0) ids.add(((Tile) row).longPressId);
            }
        }
        // The header action is drawn first but listed last, like Settings at the end of a menu.
        if (mHeader != null && mHeader.actionId >= 0) ids.add(mHeader.actionId);
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
            if (row instanceof Tile && ((Tile) row).preview != null)
                mPreviewLines = Math.max(mPreviewLines, ((Tile) row).preview.toString().split("\n", -1).length);
        }
        if (mHeader != null) bindHeader(content.findViewById(R.id.context_sheet_header), dialog, mHeader, chosen);

        LinearLayout tileRow = null;
        for (Object row : mRows) {
            if (row instanceof Tile) {
                if (tileRow == null || tileRow.getChildCount() >= 3) {
                    tileRow = createTileRow(container);
                    container.addView(tileRow);
                } else {
                    tileRow.addView(createTileGap(tileRow));
                }
                tileRow.addView(createTile(tileRow, dialog, (Tile) row, chosen));
            } else if (row == ROW_BREAK) {
                tileRow = null;
            } else if (row instanceof Entry) {
                tileRow = null;
                container.addView(createItem(container, dialog, (Entry) row, chosen));
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

    private void bindHeader(View view, BottomSheetDialog dialog, Header header, int[] chosen) {
        view.setVisibility(View.VISIBLE);
        ((ImageView) view.findViewById(R.id.context_header_icon)).setImageResource(header.icon);
        ((TextView) view.findViewById(R.id.context_header_title)).setText(header.title);
        ((TextView) view.findViewById(R.id.context_header_subtitle)).setText(header.detail);
        if (header.actionId < 0) return;
        ImageButton action = view.findViewById(R.id.context_header_action);
        action.setVisibility(View.VISIBLE);
        action.setImageResource(header.actionIcon);
        action.setContentDescription(header.actionDescription);
        action.setImageTintList(ColorStateList.valueOf(
            ContextCompat.getColor(dialog.getContext(), R.color.ui_on_surface_variant)));
        action.setOnClickListener(v -> {
            chosen[0] = header.actionId;
            dialog.dismiss();
        });
    }

    private LinearLayout createTileRow(ViewGroup parent) {
        LinearLayout row = new LinearLayout(parent.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        // Cards of one row share the height of the tallest, whatever their text baselines.
        row.setBaselineAligned(false);
        int gutter = parent.getResources().getDimensionPixelSize(R.dimen.ui_gutter);
        int gap = Math.round(TILE_GAP_DP * parent.getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(gutter, 0, gutter, gap);
        row.setLayoutParams(params);
        return row;
    }

    private View createTileGap(ViewGroup parent) {
        View gap = new View(parent.getContext());
        gap.setLayoutParams(new LinearLayout.LayoutParams(
            Math.round(TILE_GAP_DP * parent.getResources().getDisplayMetrics().density), 1));
        return gap;
    }

    private View createTile(ViewGroup parent, BottomSheetDialog dialog, Tile tile, int[] chosen) {
        View view = LayoutInflater.from(dialog.getContext()).inflate(
            tile.card ? R.layout.ui_context_sheet_card : R.layout.ui_context_sheet_tile, parent, false);
        ImageView icon = view.findViewById(R.id.context_tile_icon);
        TextView title = view.findViewById(R.id.context_tile_title);
        TextView detail = view.findViewById(R.id.context_tile_detail);

        icon.setImageResource(tile.icon);
        title.setText(tile.title);
        if (!TextUtils.isEmpty(tile.detail)) {
            detail.setText(tile.detail);
            detail.setVisibility(View.VISIBLE);
        }

        if (tile.card && !TextUtils.isEmpty(tile.preview)) {
            TextView preview = view.findViewById(R.id.context_card_preview);
            preview.setText(tile.preview);
            // Like a terminal that does not wrap: the lines come cut to length, anything longer is clipped.
            preview.setHorizontallyScrolling(true);
            // Previews of one sheet are as tall as the longest, so that its cards line up.
            preview.setMinLines(mPreviewLines);
            preview.setVisibility(View.VISIBLE);
        }

        // The icon sits on a tonal badge; text keeps the colors of the theme unless the tile is destructive.
        int colorRes = !tile.enabled ? R.color.ui_outline : (tile.danger ? R.color.ui_danger : R.color.ui_on_container);
        int color = ContextCompat.getColor(dialog.getContext(), colorRes);
        icon.setImageTintList(ColorStateList.valueOf(color));
        if (tile.danger) {
            icon.setBackgroundResource(R.drawable.ui_sheet_tile_icon_danger_bg);
            view.setBackgroundResource(R.drawable.ui_sheet_tile_danger_bg);
            title.setTextColor(color);
            detail.setTextColor(color);
        } else if (!tile.enabled) {
            title.setTextColor(color);
        } else if (Boolean.TRUE.equals(tile.checked)) {
            icon.setBackgroundResource(R.drawable.ui_sheet_tile_icon_on_bg);
            view.setBackgroundResource(R.drawable.ui_sheet_tile_on_bg);
            icon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(dialog.getContext(), R.color.ui_on_active)));
        }
        if (tile.checked != null) {
            final boolean checked = tile.checked;
            ViewCompat.setAccessibilityDelegate(view, new AccessibilityDelegateCompat() {
                @Override
                public void onInitializeAccessibilityNodeInfo(@NonNull View host, @NonNull AccessibilityNodeInfoCompat info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName("android.widget.Switch");
                    info.setCheckable(true);
                    info.setChecked(checked);
                }
            });
        }

        view.setEnabled(tile.enabled);
        title.setEnabled(tile.enabled);
        if (!tile.enabled) {
            view.setClickable(false);
            view.setLongClickable(false);
            return view;
        }

        view.setOnClickListener(v -> {
            chosen[0] = tile.id;
            dialog.dismiss();
        });
        if (tile.longPressId >= 0) {
            view.setOnLongClickListener(v -> {
                chosen[0] = tile.longPressId;
                dialog.dismiss();
                return true;
            });
            ViewCompat.replaceAccessibilityAction(view, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
                tile.longPressLabel, null);
        }
        return view;
    }
}
