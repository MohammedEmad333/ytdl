package com.example.ytdl;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * The design, such as it is.
 *
 * Direction: this is a transfer monitor, not a consumer app. Its subject matter is codecs,
 * containers, bitrates and byte counts — tabular technical data — so it's built to read like
 * an instrument panel rather than a feed. Dark, dense, monospaced where the content is
 * actually machine data, with one signal colour reserved for "in transit". Amber is a state,
 * not decoration: nothing else in the app is allowed to use it.
 *
 * The one deliberate flourish is progress. Not a chunky bar widget — a hairline rule under
 * each row that fills left to right, so a queue of five reads as five lines advancing at
 * different rates. That's the thing worth remembering, so everything around it stays quiet.
 *
 * Custom fonts would be the obvious next lever, and they're deliberately absent: font files
 * are binary, and this repo doesn't have any. Monospace is the system face, which is exactly
 * why it's carrying the personality here — it's the only characterful thing available.
 */
public final class Ui {

    public static final int BG = Color.parseColor("#0B0E13");
    public static final int SURFACE = Color.parseColor("#151A21");
    public static final int LINE = Color.parseColor("#262D38");
    public static final int TEXT = Color.parseColor("#E8EDF2");
    public static final int MUTED = Color.parseColor("#8B96A5");

    /** In transit. Reserved — using it for anything else spends the one loud thing. */
    public static final int ACCENT = Color.parseColor("#F0A030");
    public static final int OK = Color.parseColor("#4FB477");
    public static final int ERR = Color.parseColor("#E5534B");

    /**
     * One knob for the whole type scale. Every text size in the app multiplies through here,
     * so "make it bigger" is one number rather than thirty edits scattered across two files.
     */
    public static final float TYPE_SCALE = 1.15f;

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

    /** Flat filled rectangle with an optional hairline border. */
    public static GradientDrawable box(final Context context, final int fill,
                                       final int stroke, final float radiusDp) {
        final GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(context, radiusDp));
        if (stroke != Color.TRANSPARENT) {
            d.setStroke(Math.max(1, dp(context, 0.5f)), stroke);
        }
        return d;
    }

    /** Gives a tappable row a visible pressed state without a ripple resource. */
    public static StateListDrawable pressable(final Context context, final int resting,
                                              final int pressed, final float radiusDp) {
        final StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                box(context, pressed, Color.TRANSPARENT, radiusDp));
        states.addState(new int[]{}, box(context, resting, Color.TRANSPARENT, radiusDp));
        return states;
    }

    /** Machine data: resolutions, bitrates, byte counts, container names. */
    public static TextView mono(final Context context, final float sizeSp, final int color) {
        final TextView t = new TextView(context);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextSize(size(sizeSp));
        t.setTextColor(color);
        return t;
    }

    /** Prose: titles, uploader names, instructions. */
    public static TextView sans(final Context context, final float sizeSp, final int color) {
        final TextView t = new TextView(context);
        t.setTextSize(size(sizeSp));
        t.setTextColor(color);
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
