package com.termux.app.terminal;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.termux.shared.terminal.io.extrakeys.ExtraKeysStyle;

/** A keycap-styled list shown above a tab bar key, used for the ssh, snippets and forwards keys. */
final class TabBarPopup {

    static final int DOT_GREY = 0xFF6E6E78;
    static final int DOT_GREEN = 0xFF66BB6A;
    static final int DOT_RED = 0xFFEF5350;
    static final int DOT_AMBER = 0xFFFFC107;

    /** A row whose dot and subtitle can be updated while the popup is showing. */
    static final class Row {
        private final TextView mDot;
        private final TextView mSub;

        private Row(TextView dot, TextView sub) {
            mDot = dot;
            mSub = sub;
        }

        void setDotColor(int color) {
            if (mDot != null) mDot.setTextColor(color);
        }

        void setSubtitle(@Nullable String text) {
            if (mSub == null) return;
            mSub.setText(text);
            mSub.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
        }
    }

    private final Context mContext;
    private final LinearLayout mList;
    private final PopupWindow mWindow;
    private int mRowCount;

    TabBarPopup(Context context, @Nullable String title) {
        mContext = context;

        mList = new LinearLayout(context);
        mList.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(4);
        mList.setPadding(pad, pad, pad, pad);

        if (!TextUtils.isEmpty(title)) {
            TextView titleView = new TextView(context);
            titleView.setText(title);
            titleView.setTextColor(ExtraKeysStyle.KEY_HINT_TEXT_COLOR);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            titleView.setPadding(dp(10), dp(4), dp(10), dp(6));
            mList.addView(titleView);
        }

        ScrollView scroll = new ScrollView(context);
        scroll.addView(mList);

        GradientDrawable background = new GradientDrawable();
        background.setColor(ExtraKeysStyle.PANEL_COLOR);
        background.setCornerRadius(dp(10));
        background.setStroke(Math.max(1, dp(1)), ExtraKeysStyle.KEY_PRESSED_COLOR);
        scroll.setBackground(background);

        mWindow = new PopupWindow(scroll, dp(260), LinearLayout.LayoutParams.WRAP_CONTENT, true);
        mWindow.setBackgroundDrawable(new ColorDrawable(0));
        mWindow.setOutsideTouchable(true);
        mWindow.setElevation(dp(8));
    }

    private int dp(float value) {
        return Math.round(ExtraKeysStyle.dp(mContext, value));
    }

    /**
     * Add a row.
     *
     * @param withDot Show a status dot, initially grey.
     * @param chip Text of a small button at the end of the row, or {@code null}.
     */
    Row addRow(String label, @Nullable String subtitle, boolean withDot, @Nullable String chip,
               @Nullable Runnable onClick, @Nullable Runnable onLongClick, @Nullable Runnable onChipClick) {
        LinearLayout row = new LinearLayout(mContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(46));
        row.setPadding(dp(12), dp(4), dp(8), dp(4));
        if (onClick != null || onLongClick != null) {
            StateListDrawable states = new StateListDrawable();
            states.addState(new int[]{android.R.attr.state_pressed},
                ExtraKeysStyle.keyBackground(mContext, ExtraKeysStyle.KEY_PRESSED_COLOR, 0));
            states.addState(new int[]{}, ExtraKeysStyle.keyBackground(mContext, ExtraKeysStyle.KEY_COLOR, 0));
            row.setBackground(states);
        }

        TextView dot = null;
        if (withDot) {
            dot = new TextView(mContext);
            dot.setText("●");
            dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            dot.setTextColor(DOT_GREY);
            dot.setPadding(0, 0, dp(10), 0);
            row.addView(dot);
        }

        LinearLayout texts = new LinearLayout(mContext);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView labelView = new TextView(mContext);
        labelView.setText(label);
        labelView.setTextColor(onClick != null ? ExtraKeysStyle.KEY_TEXT_COLOR : ExtraKeysStyle.KEY_HINT_TEXT_COLOR);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        labelView.setSingleLine(true);
        labelView.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(labelView);

        TextView sub = new TextView(mContext);
        sub.setTextColor(ExtraKeysStyle.KEY_HINT_TEXT_COLOR);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(sub);

        if (chip != null) {
            TextView chipView = new TextView(mContext);
            chipView.setText(chip);
            chipView.setTextColor(ExtraKeysStyle.ACCENT_COLOR);
            chipView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            chipView.setGravity(Gravity.CENTER);
            chipView.setPadding(dp(12), dp(8), dp(12), dp(8));
            chipView.setBackground(ExtraKeysStyle.keyBackground(mContext, ExtraKeysStyle.KEY_PRESSED_COLOR, ExtraKeysStyle.ACCENT_COLOR));
            if (onChipClick != null) chipView.setOnClickListener(v -> onChipClick.run());
            row.addView(chipView);
        }

        if (onClick != null) row.setOnClickListener(v -> onClick.run());
        if (onLongClick != null) row.setOnLongClickListener(v -> {
            onLongClick.run();
            return true;
        });

        mList.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        mRowCount++;

        Row handle = new Row(dot, sub);
        handle.setSubtitle(subtitle);
        return handle;
    }

    /** Show the popup above {@code anchor}, right-aligned with it and kept on screen. */
    void show(View anchor) {
        View root = anchor.getRootView();
        int[] location = new int[2];
        anchor.getLocationInWindow(location);

        int maxHeight = Math.round(root.getHeight() * 0.55f);
        int rowsHeight = mRowCount * dp(50) + dp(40);
        if (rowsHeight > maxHeight) mWindow.setHeight(maxHeight);

        int width = mWindow.getWidth();
        int margin = dp(6);
        int x = location[0] + anchor.getWidth() - width;
        x = Math.max(margin, Math.min(x, root.getWidth() - width - margin));
        int y = root.getHeight() - location[1] + dp(4);
        try {
            mWindow.showAtLocation(anchor, Gravity.BOTTOM | Gravity.START, x, y);
        } catch (Exception ignored) {
            // Activity going away
        }
    }

    boolean isShowing() {
        return mWindow.isShowing();
    }

    void dismiss() {
        try {
            mWindow.dismiss();
        } catch (Exception ignored) {}
    }

}
