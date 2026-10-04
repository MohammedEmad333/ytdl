package com.example.ytdl;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.WeakHashMap;

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
    private static final WeakHashMap<Activity, Boolean> POLISHED = new WeakHashMap<>();

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
        t.setTypeface(Typeface.MONOSPACE);
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

    /**
     * Applies the second-generation layout without coupling download logic to presentation.
     * MainActivity intentionally builds views in Java, so this layer reorganizes those existing
     * controls after creation rather than duplicating or replacing their listeners/state.
     */
    public static void polishActivity(final Activity activity) {
        if (activity == null || POLISHED.containsKey(activity)) return;
        if (!(activity instanceof MainActivity) && !(activity instanceof HistoryActivity)) return;
        POLISHED.put(activity, Boolean.TRUE);
        final View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;
        ((ViewGroup) content).post(() -> {
            if (activity instanceof MainActivity) polishMain(activity, (ViewGroup) content);
            else polishHistory(activity, (ViewGroup) content);
        });
    }

    private static void polishMain(final Activity activity, final ViewGroup content) {
        final View rootView = firstChild(content);
        if (!(rootView instanceof LinearLayout)) return;
        final LinearLayout root = (LinearLayout) rootView;
        if (root.getChildCount() < 3) return;

        replaceMainHeader(activity, root);
        polishTabs(activity, root.getChildAt(1));

        final View pagerView = root.getChildAt(2);
        if (!(pagerView instanceof FrameLayout)) return;
        final FrameLayout pager = (FrameLayout) pagerView;
        if (pager.getChildCount() >= 1 && pager.getChildAt(0) instanceof LinearLayout) {
            polishFetchPane(activity, (LinearLayout) pager.getChildAt(0));
        }
        if (pager.getChildCount() >= 2 && pager.getChildAt(1) instanceof LinearLayout) {
            polishQueuePane(activity, (LinearLayout) pager.getChildAt(1));
        }
    }

    private static void replaceMainHeader(final Activity activity, final LinearLayout root) {
        final View old = root.getChildAt(0);
        if (old instanceof LinearLayout) return;
        root.removeViewAt(0);

        final LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(activity, 20), dp(activity, 20), dp(activity, 20), dp(activity, 14));

        final TextView title = sans(activity, 25, TEXT);
        title.setText("Downloader");
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title);

        final TextView subtitle = sans(activity, 12, MUTED);
        subtitle.setText("Video, audio and playlists — one clean queue");
        subtitle.setPadding(0, dp(activity, 5), 0, 0);
        header.addView(subtitle);

        root.addView(header, 0, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private static void polishTabs(final Activity activity, final View tabsView) {
        if (!(tabsView instanceof LinearLayout)) return;
        final LinearLayout wrap = (LinearLayout) tabsView;
        if (wrap.getChildCount() == 0 || !(wrap.getChildAt(0) instanceof LinearLayout)) return;
        final LinearLayout bar = (LinearLayout) wrap.getChildAt(0);
        wrap.setPadding(dp(activity, 20), 0, dp(activity, 20), dp(activity, 4));
        bar.setPadding(dp(activity, 4), dp(activity, 4), dp(activity, 4), dp(activity, 4));
        bar.setBackground(box(activity, SURFACE, LINE, 16));
        for (int i = 0; i < bar.getChildCount(); i++) {
            final View tab = bar.getChildAt(i);
            final LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(activity, 42), 1f);
            if (i > 0) p.leftMargin = dp(activity, 4);
            tab.setLayoutParams(p);
            if (tab instanceof TextView) {
                final TextView text = (TextView) tab;
                text.setTextSize(size(12));
                text.setLetterSpacing(.04f);
                text.setGravity(Gravity.CENTER);
                text.setBackground(pressable(activity, Color.TRANSPARENT, ACCENT_SOFT, 12));
            }
        }
        if (wrap.getChildCount() > 1) wrap.getChildAt(1).setVisibility(View.GONE);
    }

    private static void polishFetchPane(final Activity activity, final LinearLayout pane) {
        pane.setPadding(dp(activity, 20), dp(activity, 16), dp(activity, 20), 0);
        if (pane.getChildCount() < 11) return;

        final View input = pane.getChildAt(0);
        if (input instanceof EditText) {
            final EditText field = (EditText) input;
            field.setTextSize(size(15));
            field.setTypeface(Typeface.DEFAULT);
            field.setMinHeight(dp(activity, 58));
            field.setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 14));
            field.setBackground(box(activity, SURFACE_RAISED, LINE, 16));
        }

        final View clipboard = pane.getChildAt(1);
        if (clipboard instanceof LinearLayout) {
            final LinearLayout row = (LinearLayout) clipboard;
            equalizeChildren(activity, row, 42);
        }

        // Collapse three vertically stacked search filters into one compact horizontal strip.
        final View source = pane.getChildAt(2);
        final View duration = pane.getChildAt(3);
        final View sort = pane.getChildAt(4);
        pane.removeView(source);
        pane.removeView(duration);
        pane.removeView(sort);
        final LinearLayout filters = new LinearLayout(activity);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        addWeighted(activity, filters, source, 1.15f, 0);
        addWeighted(activity, filters, duration, 1f, 8);
        addWeighted(activity, filters, sort, 1f, 8);
        final LinearLayout.LayoutParams filterParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 46));
        filterParams.topMargin = dp(activity, 10);
        pane.addView(filters, 2, filterParams);

        // Recent + primary action now read as a single action row instead of another stack.
        final View recent = pane.getChildAt(3);
        final View fetch = pane.getChildAt(4);
        pane.removeView(recent);
        pane.removeView(fetch);
        final LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        addWeighted(activity, actions, recent, .85f, 0);
        addWeighted(activity, actions, fetch, 1.65f, 8);
        final LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 48));
        actionParams.topMargin = dp(activity, 10);
        pane.addView(actions, 3, actionParams);

        if (fetch instanceof Button) {
            final Button b = (Button) fetch;
            b.setText("SEARCH / INSPECT");
            b.setTextSize(size(13));
            b.setLetterSpacing(.04f);
            b.setBackground(pressable(activity, ACCENT, Color.parseColor("#5C91E8"), 14));
        }
        if (recent instanceof Button) {
            ((Button) recent).setText("RECENT");
        }

        // Updated indexes after grouping: preview=4, status=5, audio=6, list=7.
        if (pane.getChildCount() >= 8) {
            final View preview = pane.getChildAt(4);
            if (preview instanceof ImageView) {
                preview.setBackground(box(activity, SURFACE, LINE, 18));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) preview.setClipToOutline(true);
                final ViewGroup.LayoutParams raw = preview.getLayoutParams();
                if (raw instanceof LinearLayout.LayoutParams) {
                    final LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) raw;
                    p.height = dp(activity, 184);
                    p.topMargin = dp(activity, 14);
                    preview.setLayoutParams(p);
                }
            }

            final View status = pane.getChildAt(5);
            if (status instanceof TextView) {
                final TextView text = (TextView) status;
                text.setTextSize(size(14));
                text.setTextColor(TEXT);
                text.setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12));
                text.setBackground(box(activity, SURFACE, LINE, 14));
                final LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                p.topMargin = dp(activity, 10);
                p.bottomMargin = dp(activity, 8);
                text.setLayoutParams(p);
            }

            final View list = pane.getChildAt(7);
            if (list instanceof ListView) {
                ((ListView) list).setClipToPadding(false);
                list.setPadding(0, 0, 0, dp(activity, 12));
            }
        }
    }

    private static void polishQueuePane(final Activity activity, final LinearLayout pane) {
        pane.setPadding(dp(activity, 20), dp(activity, 14), dp(activity, 20), dp(activity, 12));
        if (pane.getChildCount() < 10) return;

        final View summary = pane.getChildAt(0);
        if (summary instanceof TextView) {
            final TextView text = (TextView) summary;
            text.setTextColor(TEXT);
            text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            text.setTextSize(size(12));
            text.setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12));
            text.setBackground(box(activity, SURFACE_RAISED, LINE, 14));
            final LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            p.bottomMargin = dp(activity, 10);
            text.setLayoutParams(p);
        }

        // Filter and sort live on one row on modern phone widths.
        final View filter = pane.getChildAt(2);
        final View sort = pane.getChildAt(3);
        pane.removeView(filter);
        pane.removeView(sort);
        final LinearLayout filterRow = new LinearLayout(activity);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        addWeighted(activity, filterRow, filter, 1f, 0);
        addWeighted(activity, filterRow, sort, 1f, 8);
        final LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 46));
        fp.bottomMargin = dp(activity, 10);
        pane.addView(filterRow, 2, fp);

        // After grouping: batch=3, pause=4, retry=5, history=6, list=7, clear=8.
        if (pane.getChildCount() >= 9) {
            final View batch = pane.getChildAt(3);
            if (batch instanceof LinearLayout) equalizeChildren(activity, (LinearLayout) batch, 40);

            final View pause = pane.getChildAt(4);
            final View retry = pane.getChildAt(5);
            final View history = pane.getChildAt(6);
            pane.removeView(pause);
            pane.removeView(retry);
            pane.removeView(history);
            final LinearLayout utilities = new LinearLayout(activity);
            utilities.setOrientation(LinearLayout.HORIZONTAL);
            addWeighted(activity, utilities, pause, 1.25f, 0);
            addWeighted(activity, utilities, retry, 1f, 8);
            addWeighted(activity, utilities, history, .9f, 8);
            final LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 44));
            up.topMargin = dp(activity, 10);
            up.bottomMargin = dp(activity, 12);
            pane.addView(utilities, 4, up);

            final View list = pane.getChildAt(5);
            if (list instanceof ListView) {
                ((ListView) list).setClipToPadding(false);
                list.setPadding(0, 0, 0, dp(activity, 8));
            }
        }
    }

    private static void polishHistory(final Activity activity, final ViewGroup content) {
        final View rootView = firstChild(content);
        if (!(rootView instanceof LinearLayout)) return;
        final LinearLayout root = (LinearLayout) rootView;
        root.setPadding(dp(activity, 20), dp(activity, 22), dp(activity, 20), dp(activity, 14));
        if (root.getChildCount() > 0 && root.getChildAt(0) instanceof TextView) {
            final TextView title = (TextView) root.getChildAt(0);
            title.setText("Download history");
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            title.setTextSize(size(24));
            title.setLetterSpacing(0f);
        }
        if (root.getChildCount() > 1 && root.getChildAt(1) instanceof TextView) {
            ((TextView) root.getChildAt(1)).setTextSize(size(13));
        }
    }

    private static View firstChild(final ViewGroup group) {
        return group.getChildCount() == 0 ? null : group.getChildAt(0);
    }

    private static void addWeighted(final Context context, final LinearLayout row, final View child,
                                    final float weight, final int startMarginDp) {
        final ViewGroup parent = (ViewGroup) child.getParent();
        if (parent != null) parent.removeView(child);
        final LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, weight);
        p.leftMargin = dp(context, startMarginDp);
        child.setLayoutParams(p);
        row.addView(child);
    }

    private static void equalizeChildren(final Context context, final LinearLayout row,
                                         final int heightDp) {
        if (row.getChildCount() == 0) return;
        for (int i = 0; i < row.getChildCount(); i++) {
            final View child = row.getChildAt(i);
            final LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    0, dp(context, heightDp), 1f);
            if (i > 0) p.leftMargin = dp(context, 8);
            child.setLayoutParams(p);
        }
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
