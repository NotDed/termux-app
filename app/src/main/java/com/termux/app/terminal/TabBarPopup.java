package com.termux.app.terminal;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.termux.shared.terminal.io.extrakeys.ExtraKeysStyle;

/**
 * The panel used by every tab bar menu: shown above a key ({@link #show(View)}) or as a centered
 * dialog ({@link #showCentered()}). Rows added one after another are drawn as one rounded group
 * with dividers; headers, messages, footers and buttons end a group.
 */
final class TabBarPopup {

    static final int DOT_GREY = 0xFF6E6E78;
    static final int DOT_GREEN = 0xFF66BB6A;
    static final int DOT_RED = 0xFFEF5350;
    static final int DOT_AMBER = 0xFFFFC107;

    static final int GROUP_COLOR = 0xFF101013;
    static final int DIVIDER_COLOR = 0xFF1E1E23;
    static final int BORDER_COLOR = 0xFF26262C;

    enum Kind { INFO, WARN, ERROR }

    /** A row whose parts can be updated while the popup is showing. */
    static final class Row {
        private final View mView;
        private final TextView mDot;
        private final TextView mSub;
        private final CompoundButton mToggle;

        private Row(View view, TextView dot, TextView sub, CompoundButton toggle) {
            mView = view;
            mDot = dot;
            mSub = sub;
            mToggle = toggle;
        }

        void setDotColor(int color) {
            if (mDot != null) mDot.setTextColor(color);
        }

        void setSubtitle(@Nullable String text) {
            setSubtitle(text, ExtraKeysStyle.KEY_HINT_TEXT_COLOR);
        }

        void setSubtitle(@Nullable String text, int color) {
            if (mSub == null) return;
            mSub.setText(text);
            mSub.setTextColor(color);
            mSub.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
        }

        /** Set the switch or checkbox without running its listener. */
        void setChecked(boolean checked) {
            if (mToggle == null) return;
            mToggle.setTag(Boolean.TRUE);
            mToggle.setChecked(checked);
            mToggle.setTag(null);
        }

        boolean isChecked() {
            return mToggle != null && mToggle.isChecked();
        }

        void setEnabled(boolean enabled) {
            mView.setEnabled(enabled);
            mView.setAlpha(enabled ? 1f : 0.4f);
            if (mToggle != null) mToggle.setEnabled(enabled);
        }
    }

    /** A toggle listener. Not called for {@link Row#setChecked}. */
    interface OnToggle {
        void onToggle(boolean checked);
    }

    /** A button of {@link #addButtons}. */
    static final class Button {
        final String label;
        final int style;
        final Runnable action;

        static final int TEXT = 0;
        static final int PRIMARY = 1;
        static final int DANGER = 2;

        Button(String label, int style, @Nullable Runnable action) {
            this.label = label;
            this.style = style;
            this.action = action;
        }
    }

    /** A tap outside that closes a popup, then reaches its key within this time, must not reopen it. */
    private static final long RETAP_GUARD_MS = 400;
    private static View sLastOutsideAnchor;
    private static long sLastOutsideAt;
    /** Popups currently showing, so they can all be closed when the activity stops. */
    private static final java.util.Set<TabBarPopup> sShowing = new java.util.HashSet<>();

    private static final int MIN_WIDTH_DP = 220;
    private static final int MAX_WIDTH_DP = 320;
    private static final int DIALOG_WIDTH_DP = 320;

    private final Context mContext;
    private final LinearLayout mRoot;
    private final LinearLayout mHeaderActions;
    private final TextView mTitleView;
    private final ScrollView mScroll;
    private final LinearLayout mList;
    /** The group rows are currently added to, or null to start a new one. */
    private LinearLayout mGroup;

    private PopupWindow mWindow;
    private Dialog mDialog;
    private View mAnchor;
    /** The anchor window's root view and the anchor's position, see {@link #trackAnchor}. */
    private View mRootView;
    private int mAnchorCenterX;
    private int mAnchorTop;
    private Runnable mOnDismiss;

    TabBarPopup(Context context, @Nullable String title) {
        mContext = context;

        mRoot = new LinearLayout(context);
        mRoot.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(ExtraKeysStyle.PANEL_COLOR);
        background.setCornerRadius(dp(12));
        background.setStroke(Math.max(1, dp(1)), BORDER_COLOR);
        mRoot.setBackground(background);
        mRoot.setClipToOutline(true);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(8), dp(6), dp(2));
        mTitleView = new TextView(context);
        mTitleView.setTextColor(ExtraKeysStyle.KEY_TEXT_COLOR);
        mTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mTitleView.setTypeface(null, Typeface.BOLD);
        mTitleView.setSingleLine(true);
        mTitleView.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(mTitleView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        mHeaderActions = new LinearLayout(context);
        mHeaderActions.setOrientation(LinearLayout.HORIZONTAL);
        header.addView(mHeaderActions);
        header.setMinimumHeight(dp(40));
        mRoot.addView(header);
        setTitle(title);

        mList = new LinearLayout(context);
        mList.setOrientation(LinearLayout.VERTICAL);
        mList.setPadding(dp(8), dp(2), dp(8), dp(8));
        mScroll = new ScrollView(context);
        mScroll.setVerticalScrollBarEnabled(false);
        mScroll.addView(mList);
        mRoot.addView(mScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private int dp(float value) {
        return Math.round(ExtraKeysStyle.dp(mContext, value));
    }

    void setTitle(@Nullable String title) {
        mTitleView.setText(title);
        ((View) mTitleView.getParent()).setVisibility(TextUtils.isEmpty(title) && mHeaderActions.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    void setOnDismiss(@Nullable Runnable onDismiss) {
        mOnDismiss = onDismiss;
    }

    /** Add a small icon button to the title bar, like ✎ or ↻. */
    TextView addHeaderAction(String glyph, Runnable action) {
        TextView button = new TextView(mContext);
        button.setText(glyph);
        button.setTextColor(ExtraKeysStyle.ACCENT_COLOR);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        button.setGravity(Gravity.CENTER);
        button.setBackground(pressable(0, dp(8)));
        button.setOnClickListener(v -> action.run());
        mHeaderActions.addView(button, new LinearLayout.LayoutParams(dp(38), dp(34)));
        ((View) mTitleView.getParent()).setVisibility(View.VISIBLE);
        return button;
    }

    /** A background that is {@code color} (0 for none) and lighter while pressed. */
    private Drawable pressable(int color, int radius) {
        StateListDrawable states = new StateListDrawable();
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(ExtraKeysStyle.KEY_PRESSED_COLOR);
        pressed.setCornerRadius(radius);
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(color);
        normal.setCornerRadius(radius);
        states.addState(new int[]{}, normal);
        return states;
    }

    /** The group to add a row to, starting one with a divider-less first row if needed. */
    private LinearLayout group() {
        if (mGroup == null) {
            mGroup = new LinearLayout(mContext);
            mGroup.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(GROUP_COLOR);
            shape.setCornerRadius(dp(10));
            mGroup.setBackground(shape);
            mGroup.setClipToOutline(true);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(6);
            mList.addView(mGroup, params);
        } else {
            View divider = new View(mContext);
            divider.setBackgroundColor(DIVIDER_COLOR);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
            params.leftMargin = dp(12);
            mGroup.addView(divider, params);
        }
        return mGroup;
    }

    private void endGroup() {
        mGroup = null;
    }

    private TextView text(String value, float sp, int color) {
        TextView view = new TextView(mContext);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        return view;
    }

    /** Build a row with a dot, a label, a subtitle and a trailing view, added to the current group. */
    private Row buildRow(String label, @Nullable String subtitle, boolean withDot, @Nullable View trailing,
                         @Nullable CompoundButton toggle, @Nullable Runnable onClick, @Nullable Runnable onLongClick) {
        LinearLayout row = new LinearLayout(mContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        row.setPadding(dp(12), dp(6), dp(8), dp(6));
        if (onClick != null || onLongClick != null) row.setBackground(pressable(0, 0));

        TextView dot = null;
        if (withDot) {
            dot = text("●", 11, DOT_GREY);
            dot.setPadding(0, 0, dp(10), 0);
            row.addView(dot);
        }

        LinearLayout texts = new LinearLayout(mContext);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView labelView = text(label, 14, ExtraKeysStyle.KEY_TEXT_COLOR);
        labelView.setSingleLine(true);
        labelView.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(labelView);

        TextView sub = text("", 12, ExtraKeysStyle.KEY_HINT_TEXT_COLOR);
        sub.setMaxLines(3);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setPadding(0, dp(1), 0, 0);
        texts.addView(sub);

        if (trailing != null) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.leftMargin = dp(8);
            row.addView(trailing, params);
        }

        if (onClick != null) row.setOnClickListener(v -> onClick.run());
        if (onLongClick != null) row.setOnLongClickListener(v -> {
            onLongClick.run();
            return true;
        });

        group().addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Row handle = new Row(row, dot, sub, toggle);
        handle.setSubtitle(subtitle);
        return handle;
    }

    private TextView chip(String text, @Nullable Runnable onClick) {
        TextView chip = text(text, 12, ExtraKeysStyle.ACCENT_COLOR);
        chip.setGravity(Gravity.CENTER);
        chip.setMinWidth(dp(44));
        chip.setPadding(dp(12), dp(7), dp(12), dp(7));
        StateListDrawable states = new StateListDrawable();
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(ExtraKeysStyle.KEY_PRESSED_COLOR);
        pressed.setCornerRadius(dp(14));
        pressed.setStroke(Math.max(1, dp(1)), ExtraKeysStyle.ACCENT_COLOR);
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(ExtraKeysStyle.KEY_COLOR);
        normal.setCornerRadius(dp(14));
        normal.setStroke(Math.max(1, dp(1)), 0x6680DEEA);
        states.addState(new int[]{}, normal);
        chip.setBackground(states);
        if (onClick != null) chip.setOnClickListener(v -> onClick.run());
        return chip;
    }

    /**
     * Add a row.
     *
     * @param withDot Show a status dot, initially grey.
     * @param chip Text of a small button at the end of the row, or {@code null}.
     */
    Row addRow(String label, @Nullable String subtitle, boolean withDot, @Nullable String chip,
               @Nullable Runnable onClick, @Nullable Runnable onLongClick, @Nullable Runnable onChipClick) {
        View trailing = chip != null ? chip(chip, onChipClick) : null;
        return buildRow(label, subtitle, withDot, trailing, null, onClick, onLongClick);
    }

    /** Add a row that opens something, with a chevron at the end. */
    Row addLinkRow(String label, @Nullable String subtitle, Runnable onClick) {
        return buildRow(label, subtitle, false, text("›", 18, ExtraKeysStyle.KEY_HINT_TEXT_COLOR), null, onClick, null);
    }

    /** Add a row with a switch, toggled by tapping anywhere on the row. */
    Row addSwitchRow(String label, @Nullable String subtitle, boolean withDot, boolean checked, OnToggle onToggle) {
        Switch toggle = new Switch(mContext);
        int[][] states = {{android.R.attr.state_checked}, {}};
        toggle.setThumbTintList(new ColorStateList(states, new int[]{ExtraKeysStyle.ACCENT_COLOR, 0xFFB0B0B8}));
        toggle.setTrackTintList(new ColorStateList(states, new int[]{0x9980DEEA, 0xFF4A4A52}));
        return addToggleRow(toggle, label, subtitle, withDot, checked, onToggle);
    }

    /** Add a row with a checkbox, toggled by tapping anywhere on the row. */
    Row addCheckRow(String label, @Nullable String subtitle, boolean checked, OnToggle onToggle) {
        CheckBox toggle = new CheckBox(mContext);
        int[][] states = {{android.R.attr.state_checked}, {}};
        toggle.setButtonTintList(new ColorStateList(states, new int[]{ExtraKeysStyle.ACCENT_COLOR, 0xFF8A8A92}));
        return addToggleRow(toggle, label, subtitle, false, checked, onToggle);
    }

    private Row addToggleRow(CompoundButton toggle, String label, @Nullable String subtitle, boolean withDot,
                             boolean checked, OnToggle onToggle) {
        toggle.setChecked(checked);
        toggle.setClickable(false);
        toggle.setFocusable(false);
        toggle.setOnCheckedChangeListener((button, isChecked) -> {
            if (button.getTag() == null && onToggle != null) onToggle.onToggle(isChecked);
        });
        return buildRow(label, subtitle, withDot, toggle, toggle, toggle::toggle, null);
    }

    /** Add a section label, like an account name or a settings group. */
    void addHeader(String text) {
        endGroup();
        TextView header = text(text.toUpperCase(java.util.Locale.ROOT), 11, ExtraKeysStyle.ACCENT_COLOR);
        header.setTypeface(null, Typeface.BOLD);
        header.setLetterSpacing(0.06f);
        header.setSingleLine(true);
        header.setEllipsize(TextUtils.TruncateAt.END);
        header.setPadding(dp(6), dp(12), dp(6), dp(0));
        mList.addView(header);
    }

    /** Add a message that wraps, with an optional action chip, like an error and how to fix it. */
    void addMessage(Kind kind, String message, @Nullable String action, @Nullable Runnable onAction) {
        endGroup();
        int color = kind == Kind.ERROR ? DOT_RED : kind == Kind.WARN ? DOT_AMBER : ExtraKeysStyle.ACCENT_COLOR;
        String icon = kind == Kind.ERROR ? "✕" : kind == Kind.WARN ? "!" : "i";

        LinearLayout box = new LinearLayout(mContext);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor((color & 0x00FFFFFF) | 0x1F000000);
        shape.setCornerRadius(dp(10));
        box.setBackground(shape);

        TextView iconView = text(icon, 12, ExtraKeysStyle.ON_ACCENT_TEXT_COLOR);
        iconView.setTypeface(null, Typeface.BOLD);
        iconView.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(color);
        iconView.setBackground(circle);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(18), dp(18));
        iconParams.rightMargin = dp(10);
        iconParams.topMargin = dp(1);
        box.addView(iconView, iconParams);

        LinearLayout texts = new LinearLayout(mContext);
        texts.setOrientation(LinearLayout.VERTICAL);
        box.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView messageView = text(message, 13, ExtraKeysStyle.KEY_TEXT_COLOR);
        texts.addView(messageView);
        if (action != null) {
            TextView chip = chip(action, onAction);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(8);
            texts.addView(chip, params);
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(6);
        mList.addView(box, params);
    }

    /** Add a block of monospace text, like the last lines of a command's output. */
    void addOutput(String output) {
        endGroup();
        TextView view = text(output, 11, ExtraKeysStyle.KEY_HINT_TEXT_COLOR);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(GROUP_COLOR);
        shape.setCornerRadius(dp(8));
        view.setBackground(shape);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(6);
        mList.addView(view, params);
    }

    /** Add a row with a label, a percent and a bar showing that percent. */
    void addMeterRow(String label, int percent, @Nullable String subtitle, int color) {
        int clamped = Math.max(0, Math.min(100, percent));

        LinearLayout row = new LinearLayout(mContext);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(9), dp(12), dp(9));

        LinearLayout top = new LinearLayout(mContext);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView labelView = text(label, 13, ExtraKeysStyle.KEY_TEXT_COLOR);
        labelView.setSingleLine(true);
        labelView.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(labelView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (!TextUtils.isEmpty(subtitle)) {
            TextView sub = text(subtitle, 11, ExtraKeysStyle.KEY_HINT_TEXT_COLOR);
            sub.setSingleLine(true);
            sub.setPadding(dp(8), 0, dp(8), 0);
            top.addView(sub);
        }
        TextView percentView = text(clamped + "%", 13, color);
        percentView.setTypeface(null, Typeface.BOLD);
        percentView.setMinWidth(dp(38));
        percentView.setGravity(Gravity.END);
        top.addView(percentView);
        row.addView(top);

        // Bar: a track with a filled part sized by weight
        LinearLayout track = new LinearLayout(mContext);
        track.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable trackShape = new GradientDrawable();
        trackShape.setColor(ExtraKeysStyle.KEY_COLOR);
        trackShape.setCornerRadius(dp(3));
        track.setBackground(trackShape);
        track.setWeightSum(100f);
        if (clamped > 0) {
            View fill = new View(mContext);
            GradientDrawable fillShape = new GradientDrawable();
            fillShape.setColor(color);
            fillShape.setCornerRadius(dp(3));
            fill.setBackground(fillShape);
            track.addView(fill, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, clamped));
        }
        LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6));
        trackParams.topMargin = dp(7);
        row.addView(track, trackParams);

        group().addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    /** Add small centered text at the end, like when the numbers were updated. */
    /** A grid of tappable cells, like a row of quick-pick emojis. Each tap runs {@code onPick}. */
    void addGrid(java.util.List<String> items, int columns, java.util.function.Consumer<String> onPick) {
        endGroup();
        LinearLayout grid = new LinearLayout(mContext);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(0, dp(4), 0, dp(4));
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(mContext);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
            }
            String item = items.get(i);
            TextView cell = text(item, 22, ExtraKeysStyle.KEY_TEXT_COLOR);
            cell.setGravity(Gravity.CENTER);
            cell.setBackground(pressable(0, dp(8)));
            cell.setOnClickListener(v -> onPick.accept(item));
            row.addView(cell, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        }
        // Keep the last row's cells the same size as the others
        int missing = items.isEmpty() ? 0 : (columns - items.size() % columns) % columns;
        for (int i = 0; i < missing && row != null; i++)
            row.addView(new View(mContext), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        mList.addView(grid, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    /** A row of choices where one is selected, like Key / Macro / Text. */
    void addSegments(String[] labels, int selected, java.util.function.IntConsumer onSelect) {
        endGroup();
        LinearLayout bar = new LinearLayout(mContext);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(0, dp(4), 0, dp(6));
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            boolean on = i == selected;
            TextView segment = text(labels[i], 13, on ? ExtraKeysStyle.ON_ACCENT_TEXT_COLOR : ExtraKeysStyle.KEY_TEXT_COLOR);
            segment.setGravity(Gravity.CENTER);
            segment.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
            segment.setPadding(dp(6), dp(9), dp(6), dp(9));
            segment.setBackground(pressable(on ? ExtraKeysStyle.ACCENT_COLOR : GROUP_COLOR, dp(8)));
            segment.setOnClickListener(v -> onSelect.accept(index));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) params.leftMargin = dp(6);
            bar.addView(segment, params);
        }
        mList.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    void addFooter(String text) {
        endGroup();
        TextView footer = text(text, 11, ExtraKeysStyle.KEY_HINT_TEXT_COLOR);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(6), dp(10), dp(6), dp(2));
        mList.addView(footer, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    /**
     * Add a text field. With {@code password} the text is masked and an eye toggles showing it.
     * {@code onDone} runs on the keyboard's done key.
     */
    EditText addInput(@Nullable String hint, @Nullable String text, boolean password, @Nullable Runnable onDone) {
        endGroup();
        LinearLayout box = new LinearLayout(mContext);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);

        EditText input = new EditText(mContext);
        input.setSingleLine(true);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        input.setTextColor(ExtraKeysStyle.KEY_TEXT_COLOR);
        input.setHintTextColor(0xFF6E6E78);
        input.setHint(hint);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setInputType(password ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT);
        if (Build.VERSION.SDK_INT >= 29) {
            GradientDrawable cursor = new GradientDrawable();
            cursor.setColor(ExtraKeysStyle.ACCENT_COLOR);
            cursor.setSize(dp(2), dp(18));
            input.setTextCursorDrawable(cursor);
        }
        input.setHighlightColor(0x6680DEEA);
        if (text != null) {
            input.setText(text);
            input.setSelection(input.getText().length());
        }
        if (onDone != null) {
            input.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    onDone.run();
                    return true;
                }
                return false;
            });
        }
        box.addView(input, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (password) {
            TextView eye = text("show", 12, ExtraKeysStyle.ACCENT_COLOR);
            eye.setGravity(Gravity.CENTER);
            eye.setPadding(dp(10), dp(8), dp(12), dp(8));
            eye.setOnClickListener(v -> {
                boolean masked = (input.getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0;
                input.setInputType(masked ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                input.setTypeface(Typeface.DEFAULT);
                input.setSelection(input.getText().length());
                eye.setText(masked ? "hide" : "show");
            });
            box.addView(eye);
        }

        StateListDrawable states = new StateListDrawable();
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(GROUP_COLOR);
        focused.setCornerRadius(dp(10));
        focused.setStroke(Math.max(1, dp(1.5f)), ExtraKeysStyle.ACCENT_COLOR);
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(GROUP_COLOR);
        normal.setCornerRadius(dp(10));
        normal.setStroke(Math.max(1, dp(1)), BORDER_COLOR);
        input.setBackground(null);
        box.setBackground(normal);
        input.setOnFocusChangeListener((v, hasFocus) -> box.setBackground(hasFocus ? focused : normal));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        mList.addView(box, params);
        return input;
    }

    /** Add a row of buttons, aligned to the end. Buttons dismiss the popup unless their action is null. */
    void addButtons(Button... buttons) {
        endGroup();
        LinearLayout bar = new LinearLayout(mContext);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(10), 0, 0);

        for (Button button : buttons) {
            TextView view = text(button.label, 14, ExtraKeysStyle.KEY_TEXT_COLOR);
            view.setTypeface(null, Typeface.BOLD);
            view.setGravity(Gravity.CENTER);
            view.setMinHeight(dp(40));
            view.setMinWidth(dp(64));
            view.setPadding(dp(14), 0, dp(14), 0);
            int fill;
            switch (button.style) {
                case Button.PRIMARY:
                    fill = ExtraKeysStyle.ACCENT_COLOR;
                    view.setTextColor(ExtraKeysStyle.ON_ACCENT_TEXT_COLOR);
                    break;
                case Button.DANGER:
                    fill = DOT_RED;
                    view.setTextColor(0xFF1A0606);
                    break;
                default:
                    fill = 0;
                    view.setTextColor(ExtraKeysStyle.ACCENT_COLOR);
                    break;
            }
            StateListDrawable states = new StateListDrawable();
            GradientDrawable pressed = new GradientDrawable();
            pressed.setColor(fill == 0 ? ExtraKeysStyle.KEY_PRESSED_COLOR : (fill & 0x00FFFFFF) | 0xB3000000);
            pressed.setCornerRadius(dp(20));
            states.addState(new int[]{android.R.attr.state_pressed}, pressed);
            GradientDrawable normal = new GradientDrawable();
            normal.setColor(fill);
            normal.setCornerRadius(dp(20));
            states.addState(new int[]{}, normal);
            view.setBackground(states);
            view.setOnClickListener(v -> {
                if (button.action != null) button.action.run();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.leftMargin = dp(6);
            bar.addView(view, params);
        }

        mList.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    /** Remove everything below the title, so the content can be rebuilt while showing. */
    void clearRows() {
        mList.removeAllViews();
        endGroup();
    }

    private int screenWidth() {
        return mContext.getResources().getDisplayMetrics().widthPixels;
    }

    private int screenHeight() {
        return mContext.getResources().getDisplayMetrics().heightPixels;
    }

    /** Measure the content: a width between the min and max, and a height capped at {@code maxHeight}. */
    private int[] measure(int minWidth, int maxWidth, int maxHeight) {
        mScroll.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        mRoot.measure(View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int width = Math.max(minWidth, Math.min(maxWidth, mRoot.getMeasuredWidth()));
        mRoot.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = mRoot.getMeasuredHeight();
        if (height > maxHeight) {
            int scrollHeight = mScroll.getMeasuredHeight() - (height - maxHeight);
            mScroll.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(dp(80), scrollHeight)));
            height = maxHeight;
        }
        return new int[]{width, height};
    }

    /**
     * Remember the window's root view and where the anchor is. Tabs are rebuilt whenever a
     * session title changes, which detaches the anchor of an open tab menu; a detached view is
     * its own root and sits at 0,0, so the last known values are kept until it is attached again.
     */
    private void trackAnchor() {
        if (mAnchor == null || !mAnchor.isAttachedToWindow()) return;
        mRootView = mAnchor.getRootView();
        int[] location = new int[2];
        mAnchor.getLocationInWindow(location);
        mAnchorCenterX = location[0] + mAnchor.getWidth() / 2;
        mAnchorTop = location[1];
    }

    private int[] popupSize() {
        int margin = dp(6);
        int maxWidth = Math.min(dp(MAX_WIDTH_DP), mRootView.getWidth() - 2 * margin);
        int minWidth = Math.min(dp(MIN_WIDTH_DP), maxWidth);
        return measure(minWidth, maxWidth, maxPopupHeight());
    }

    /** At most 55% of the screen, and never higher than the space above the anchor. */
    private int maxPopupHeight() {
        int max = Math.round(mRootView.getHeight() * 0.55f);
        android.view.WindowInsets insets = mRootView.getRootWindowInsets();
        int top = insets == null ? 0 : insets.getSystemWindowInsetTop();
        int above = mAnchorTop - dp(4) - top - dp(8);
        if (above > dp(120)) max = Math.min(max, above);
        return max;
    }

    private int[] popupPosition(int width) {
        int margin = dp(6);
        int x = mAnchorCenterX - width / 2;
        x = Math.max(margin, Math.min(x, mRootView.getWidth() - width - margin));
        int y = mRootView.getHeight() - mAnchorTop + dp(4);
        return new int[]{x, y};
    }

    /** Show the popup above {@code anchor}, centered on it and kept on screen. */
    void show(View anchor) {
        // The tap on the key that just closed this key's popup should only close it
        if (anchor == sLastOutsideAnchor && android.os.SystemClock.uptimeMillis() - sLastOutsideAt < RETAP_GUARD_MS) {
            sLastOutsideAnchor = null;
            return;
        }
        mAnchor = anchor;
        trackAnchor();
        if (mRootView == null) return;
        int[] size = popupSize();
        // Not focusable, so the keyboard stays up and keeps typing into the terminal. A tap
        // outside closes the popup and still reaches what is under it.
        mWindow = new PopupWindow(mRoot, size[0], size[1], false);
        mWindow.setBackgroundDrawable(new ColorDrawable(0));
        mWindow.setOutsideTouchable(true);
        mWindow.setElevation(dp(10));
        mWindow.setTouchInterceptor((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_OUTSIDE) {
                sLastOutsideAnchor = mAnchor;
                sLastOutsideAt = android.os.SystemClock.uptimeMillis();
            }
            return false;
        });
        mWindow.setOnDismissListener(() -> {
            sShowing.remove(this);
            if (mOnDismiss != null) mOnDismiss.run();
        });
        sShowing.add(this);
        int[] position = popupPosition(size[0]);
        try {
            mWindow.showAtLocation(anchor, Gravity.BOTTOM | Gravity.START, position[0], position[1]);
        } catch (Exception ignored) {
            // Activity going away
            sShowing.remove(this);
        }
    }

    /**
     * Show the popup as a dialog in the middle of the screen, dimming what is behind.
     *
     * @param focusInput An input to focus with the keyboard shown, or {@code null}.
     */
    void showCentered(boolean cancelable, @Nullable EditText focusInput) {
        mDialog = new Dialog(mContext);
        mDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        int width = Math.min(dp(DIALOG_WIDTH_DP), screenWidth() - dp(32));
        int[] size = measure(width, width, Math.round(screenHeight() * 0.7f));
        if (mRoot.getParent() != null) ((ViewGroup) mRoot.getParent()).removeView(mRoot);
        mDialog.setContentView(mRoot);
        mDialog.setCancelable(cancelable);
        mDialog.setCanceledOnTouchOutside(cancelable);
        mDialog.setOnDismissListener(d -> {
            sShowing.remove(this);
            if (mOnDismiss != null) mOnDismiss.run();
        });
        sShowing.add(this);
        Window window = mDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0));
            window.setLayout(size[0], ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setDimAmount(0.8f);
            if (focusInput != null) window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        try {
            mDialog.show();
        } catch (Exception ignored) {
            sShowing.remove(this);
            return;
        }
        if (focusInput != null) {
            // Asking for the keyboard right away is often ignored while the dialog window is
            // still being attached, so focus and ask again once it is on screen
            focusInput.requestFocus();
            focusInput.postDelayed(() -> {
                if (!focusInput.isAttachedToWindow()) return;
                focusInput.requestFocus();
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                    mContext.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(focusInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }, 150);
        }
    }

    /** Fit the size and position to new content after it was rebuilt while showing. */
    void relayout() {
        if (mWindow != null && mWindow.isShowing() && mRootView != null) {
            trackAnchor();
            int[] size = popupSize();
            int[] position = popupPosition(size[0]);
            // The window keeps the BOTTOM gravity it was shown with, so y stays measured from the bottom
            mWindow.update(position[0], position[1], size[0], size[1]);
        } else if (mDialog != null && mDialog.isShowing()) {
            int width = Math.min(dp(DIALOG_WIDTH_DP), screenWidth() - dp(32));
            measure(width, width, Math.round(screenHeight() * 0.7f));
            mRoot.requestLayout();
        }
    }

    boolean isShowing() {
        return (mWindow != null && mWindow.isShowing()) || (mDialog != null && mDialog.isShowing());
    }

    /** Close every popup and panel, e.g. when the activity stops, so none outlives it. */
    static void dismissAll() {
        for (TabBarPopup popup : new java.util.ArrayList<>(sShowing)) popup.dismiss();
        sShowing.clear();
        sLastOutsideAnchor = null;
    }

    void dismiss() {
        try {
            if (mWindow != null) mWindow.dismiss();
            if (mDialog != null) mDialog.dismiss();
        } catch (Exception ignored) {}
    }

}
