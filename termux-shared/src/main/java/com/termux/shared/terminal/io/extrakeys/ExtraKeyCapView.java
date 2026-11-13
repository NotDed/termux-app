package com.termux.shared.terminal.io.extrakeys;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;

import androidx.annotation.Nullable;

/**
 * A single key of the {@link ExtraKeysView} drawn as a rounded keycap. It can show a small hint
 * for its swipe-up popup key and an active/locked state for modifier keys.
 */
@SuppressLint("ViewConstructor")
public class ExtraKeyCapView extends Button {

    private final Paint mHintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Nullable private String mHintText;
    private boolean mPressedVisual;
    private boolean mModifierActive;
    private boolean mModifierLocked;

    public ExtraKeyCapView(Context context) {
        super(context, null, 0);

        setPadding(0, 0, 0, 0);
        setMinHeight(0);
        setMinWidth(0);
        setMinimumHeight(0);
        setMinimumWidth(0);
        setGravity(Gravity.CENTER);
        setSingleLine(true);
        setEllipsize(null);
        setStateListAnimator(null);
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ExtraKeysStyle.KEY_TEXT_SIZE_SP);
        setIncludeFontPadding(false);
        if (Build.VERSION.SDK_INT >= 26) {
            setAutoSizeTextTypeUniformWithConfiguration(ExtraKeysStyle.KEY_MIN_TEXT_SIZE_SP,
                ExtraKeysStyle.KEY_TEXT_SIZE_SP, 1, TypedValue.COMPLEX_UNIT_SP);
        }

        mHintPaint.setColor(ExtraKeysStyle.KEY_HINT_TEXT_COLOR);
        mHintPaint.setTextAlign(Paint.Align.RIGHT);
        mHintPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
            ExtraKeysStyle.KEY_HINT_TEXT_SIZE_SP, context.getResources().getDisplayMetrics()));

        mLockPaint.setColor(ExtraKeysStyle.ON_ACCENT_TEXT_COLOR);

        updateVisual();
    }

    /** Set the label of the swipe-up popup key to show in the top right corner. */
    public void setHintText(@Nullable String hintText) {
        mHintText = TextUtils.isEmpty(hintText) ? null : hintText;
        invalidate();
    }

    /** Show the key as pressed or not. */
    public void setPressedVisual(boolean pressed) {
        if (mPressedVisual == pressed) return;
        mPressedVisual = pressed;
        updateVisual();
    }

    /** Show the active/locked state of a modifier key like CTRL. */
    public void setModifierState(boolean active, boolean locked) {
        mModifierActive = active;
        mModifierLocked = active && locked;
        updateVisual();
    }

    private void updateVisual() {
        Context context = getContext();
        if (mModifierActive) {
            int fill = mPressedVisual ? ExtraKeysStyle.KEY_PRESSED_COLOR : ExtraKeysStyle.ACCENT_COLOR;
            setBackground(ExtraKeysStyle.keyBackground(context, fill,
                mModifierLocked ? ExtraKeysStyle.KEY_TEXT_COLOR : 0));
            setTextColor(mPressedVisual ? ExtraKeysStyle.KEY_TEXT_COLOR : ExtraKeysStyle.ON_ACCENT_TEXT_COLOR);
        } else {
            setBackground(ExtraKeysStyle.keyBackground(context,
                mPressedVisual ? ExtraKeysStyle.KEY_PRESSED_COLOR : ExtraKeysStyle.KEY_COLOR, 0));
            setTextColor(ExtraKeysStyle.KEY_TEXT_COLOR);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        Context context = getContext();
        int insetH = ExtraKeysStyle.insetHorizontalPx(context);
        int insetV = ExtraKeysStyle.insetVerticalPx(context);

        if (mHintText != null && !mModifierActive) {
            float x = getWidth() - insetH - ExtraKeysStyle.dp(context, 3);
            float y = insetV + ExtraKeysStyle.dp(context, 1) - mHintPaint.ascent();
            canvas.drawText(mHintText, x, y, mHintPaint);
        }

        if (mModifierLocked) {
            float radius = ExtraKeysStyle.dp(context, 2.5f);
            float cx = insetH + ExtraKeysStyle.dp(context, 5);
            float cy = insetV + ExtraKeysStyle.dp(context, 5);
            canvas.drawCircle(cx, cy, radius, mLockPaint);
        }
    }

}
