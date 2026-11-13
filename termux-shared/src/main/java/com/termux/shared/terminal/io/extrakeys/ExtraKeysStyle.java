package com.termux.shared.terminal.io.extrakeys;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;

/**
 * Design tokens for the keycap look shared by the {@link ExtraKeysView} and the session tab bar.
 */
public final class ExtraKeysStyle {

    /** Background of the panel behind the keys. */
    public static final int PANEL_COLOR = 0xFF0E0E10;
    /** Fill of a key at rest. */
    public static final int KEY_COLOR = 0xFF2B2B30;
    /** Fill of a key while pressed. */
    public static final int KEY_PRESSED_COLOR = 0xFF45454D;
    /** Accent used for active modifiers and the active session tab. */
    public static final int ACCENT_COLOR = 0xFF80DEEA;
    /** Text on top of an accent fill. */
    public static final int ON_ACCENT_TEXT_COLOR = 0xFF0E0E10;
    /** Key label color. */
    public static final int KEY_TEXT_COLOR = 0xFFECECEC;
    /** Dimmed label color, for swipe-up hints and inactive tabs. */
    public static final int KEY_HINT_TEXT_COLOR = 0xFF9A9AA2;

    /** Key label size in sp. */
    public static final int KEY_TEXT_SIZE_SP = 13;
    /** Minimum key label size in sp when auto-shrinking long labels. */
    public static final int KEY_MIN_TEXT_SIZE_SP = 8;
    /** Swipe-up hint label size in sp. */
    public static final int KEY_HINT_TEXT_SIZE_SP = 9;

    public static final float CORNER_RADIUS_DP = 6f;
    public static final float INSET_HORIZONTAL_DP = 2f;
    public static final float INSET_VERTICAL_DP = 2.5f;
    public static final float STROKE_WIDTH_DP = 1f;

    private ExtraKeysStyle() {}

    public static float dp(Context context, float dp) {
        return dp * context.getResources().getDisplayMetrics().density;
    }

    public static int insetHorizontalPx(Context context) {
        return Math.round(dp(context, INSET_HORIZONTAL_DP));
    }

    public static int insetVerticalPx(Context context) {
        return Math.round(dp(context, INSET_VERTICAL_DP));
    }

    /**
     * A rounded keycap background. The key is visually inset inside its bounds so that keys appear
     * separated while the whole cell stays touchable.
     *
     * @param fillColor The fill color.
     * @param strokeColor The outline color, or {@code 0} for no outline.
     */
    public static Drawable keyBackground(Context context, int fillColor, int strokeColor) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(dp(context, CORNER_RADIUS_DP));
        shape.setColor(fillColor);
        if (strokeColor != 0)
            shape.setStroke(Math.max(1, Math.round(dp(context, STROKE_WIDTH_DP))), strokeColor);
        int h = insetHorizontalPx(context);
        int v = insetVerticalPx(context);
        return new InsetDrawable(shape, h, v, h, v);
    }

}
