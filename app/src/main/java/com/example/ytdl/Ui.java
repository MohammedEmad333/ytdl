package com.example.ytdl;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.widget.TextView;

/** Central visual system shared by every screen. */
public final class Ui {

    /** Deep neutral background with slightly lifted cards for clearer hierarchy. */
    public static final int BG = Color.parseColor("#090D13");
    public static final int SURFACE = Color.parseColor("#121922");
    public static final int SURFACE_RAISED = Color.parseColor("#18222E");
    public static final int LINE = Color.parseColor("#263445");
    public static final int TEXT = Color.parseColor("#F4F7FB");
    public static final int MUTED = Color.parseColor("#98A6B8");

    /** Primary action and transfer state. */
    public static final int ACCENT = Color.parseColor("#70A7FF");
    public static final int ACCENT_SOFT = Color.parseColor("#1A2A40");
    public static final int OK = Color.parseColor("#62C995");
    public static final int ERR = Color.parseColor("#FF7474");

    /** Keep text comfortably readable without making dense technical rows oversized. */
    public static final float TYPE_SCALE = 1.10f;

    private static final float MIN_RADIUS_DP = 12f;

    public static float size(final float baseSp) {
        return baseSp * TYPE_SCALE;
    }

    private Ui() {
    }

    public static int dp(final Context context, final float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    public static int sp(final Context context, final float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                context.getResources().getDisplayMetrics()));
    }

    /**
     * Rounded surface used by fields, cards and controls. Small legacy radii are promoted to
     * a consistent modern radius so every screen gains the same visual language.
     */
    public static GradientDrawable box(final Context context, final int fill,
                                       final int stroke, final float radiusDp) {
        final GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(context, Math.max(radiusDp, MIN_RADIUS_DP)));
        if (stroke != Color.TRANSPARENT) {
            d.setStroke(Math.max(1, dp(context, 1f)), stroke);
        }
        return d;
    }

    /** Tappable surfaces keep their outline and gain a visible raised pressed state. */
    public static StateListDrawable pressable(final Context context, final int resting,
                                              final int pressed, final float radiusDp) {
        final StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                box(context, pressed, LINE, radiusDp));
        states.addState(new int[]{android.R.attr.state_focused},
                box(context, SURFACE_RAISED, ACCENT, radiusDp));
        states.addState(new int[]{}, box(context, resting, LINE, radiusDp));
        return states;
    }

    /** Machine data: resolutions, bitrates, byte counts and container names. */
    public static TextView mono(final Context context, final float sizeSp, final int color) {
        final TextView t = new TextView(context);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextSize(size(sizeSp));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Prose: titles, uploader names and instructions. */
    public static TextView sans(final Context context, final float sizeSp, final int color) {
        final TextView t = new TextView(context);
        t.setTextSize(size(sizeSp));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    public static String bytes(final long count) {
        if (count < 1024) {
            return count + " B";
        }
        if (count < 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.0f KB", count / 1024.0);
        }
        if (count < 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f MB", count / (1024.0 * 1024));
        }
        return String.format(java.util.Locale.US, "%.2f GB", count / (1024.0 * 1024 * 1024));
    }
}
