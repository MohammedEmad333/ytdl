package com.example.ytdl;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/** Runtime polish for rows that adapters create after the activity has already been laid out. */
public final class DynamicUiPolish {
    private static final WeakHashMap<ListView, Boolean> INSTALLED = new WeakHashMap<>();

    private DynamicUiPolish() {}

    public static void install(final Activity activity) {
        if (activity == null) return;
        final View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;
        content.post(() -> {
            final List<ListView> lists = new ArrayList<>();
            collectLists(content, lists);
            for (int i = 0; i < lists.size(); i++) {
                final ListView list = lists.get(i);
                final boolean history = activity instanceof HistoryActivity;
                final boolean queue = !history && i > 0;
                installList(activity, list, queue, history);
            }
        });
    }

    private static void collectLists(final View view, final List<ListView> out) {
        if (view instanceof ListView) {
            out.add((ListView) view);
            return;
        }
        if (!(view instanceof ViewGroup)) return;
        final ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) collectLists(group.getChildAt(i), out);
    }

    private static void installList(final Activity activity, final ListView list,
                                    final boolean queue, final boolean history) {
        if (INSTALLED.containsKey(list)) {
            polishVisibleRows(activity, list, queue, history);
            return;
        }
        INSTALLED.put(list, Boolean.TRUE);
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        list.setCacheColorHint(Color.TRANSPARENT);
        polishVisibleRows(activity, list, queue, history);
        list.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override public void onChildViewAdded(final View parent, final View child) {
                polishRow(activity, child, queue, history);
            }
            @Override public void onChildViewRemoved(final View parent, final View child) {}
        });
    }

    private static void polishVisibleRows(final Activity activity, final ListView list,
                                          final boolean queue, final boolean history) {
        for (int i = 0; i < list.getChildCount(); i++) {
            polishRow(activity, list.getChildAt(i), queue, history);
        }
    }

    private static void polishRow(final Activity activity, final View row,
                                  final boolean queue, final boolean history) {
        if (row instanceof TextView) {
            final TextView empty = (TextView) row;
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(Ui.MUTED);
            empty.setTextSize(Ui.size(14));
            empty.setMinHeight(Ui.dp(activity, 132));
            empty.setPadding(Ui.dp(activity, 18), Ui.dp(activity, 24), Ui.dp(activity, 18), Ui.dp(activity, 24));
            empty.setBackground(Ui.box(activity, Ui.SURFACE, Ui.LINE, 18));
            return;
        }
        if (!(row instanceof LinearLayout)) return;

        final LinearLayout outer = (LinearLayout) row;
        final LinearLayout card = findCard(outer);
        if (card == null) return;

        card.setPadding(Ui.dp(activity, 15), Ui.dp(activity, 13), Ui.dp(activity, 15), Ui.dp(activity, 13));
        card.setBackground(queue || history
                ? Ui.box(activity, Ui.SURFACE_RAISED, Ui.LINE, 18)
                : Ui.pressable(activity, Ui.SURFACE_RAISED, Ui.ACCENT_SOFT, 18));
        card.setMinimumHeight(Ui.dp(activity, queue || history ? 84 : 76));

        if (history) polishHistoryCard(activity, card);
        else if (queue) polishQueueCard(activity, card);
        else polishResultCard(activity, card);
    }

    private static LinearLayout findCard(final LinearLayout row) {
        if (row.getChildCount() == 1 && row.getChildAt(0) instanceof LinearLayout) {
            return (LinearLayout) row.getChildAt(0);
        }
        return row;
    }

    private static void polishResultCard(final Activity activity, final LinearLayout card) {
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription("Tap to choose this format or result");

        for (int i = 0; i < card.getChildCount(); i++) {
            final View child = card.getChildAt(i);
            if (child instanceof ImageView) {
                final ImageView image = (ImageView) child;
                image.setBackground(Ui.box(activity, Ui.SURFACE, Ui.LINE, 14));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) image.setClipToOutline(true);
                final ViewGroup.LayoutParams raw = image.getLayoutParams();
                if (raw instanceof LinearLayout.LayoutParams) {
                    final LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) raw;
                    p.width = Ui.dp(activity, 104);
                    p.height = Ui.dp(activity, 62);
                    p.rightMargin = Ui.dp(activity, 13);
                    image.setLayoutParams(p);
                }
            } else if (child instanceof LinearLayout) {
                final LinearLayout text = (LinearLayout) child;
                if (text.getChildCount() > 0 && text.getChildAt(0) instanceof TextView) {
                    final TextView title = (TextView) text.getChildAt(0);
                    title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                    title.setTextSize(Ui.size(15));
                    title.setTextColor(Ui.TEXT);
                    title.setMaxLines(2);
                }
                if (text.getChildCount() > 1 && text.getChildAt(1) instanceof TextView) {
                    final TextView meta = (TextView) text.getChildAt(1);
                    meta.setTextSize(Ui.size(11));
                    meta.setTextColor(Ui.ACCENT);
                    meta.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                    meta.setLineSpacing(Ui.dp(activity, 1), 1f);
                    meta.setPadding(0, Ui.dp(activity, 4), 0, 0);
                }
            }
        }
    }

    private static void polishQueueCard(final Activity activity, final LinearLayout card) {
        if (card.getChildCount() > 0 && card.getChildAt(0) instanceof TextView) {
            final TextView title = (TextView) card.getChildAt(0);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            title.setTextSize(Ui.size(15));
            title.setTextColor(Ui.TEXT);
            title.setMaxLines(2);
        }
        if (card.getChildCount() > 1 && card.getChildAt(1) instanceof TextView) {
            final TextView meta = (TextView) card.getChildAt(1);
            meta.setTextSize(Ui.size(11));
            meta.setLineSpacing(Ui.dp(activity, 1), 1f);
            meta.setPadding(0, Ui.dp(activity, 5), 0, Ui.dp(activity, 9));
            applyStateAppearance(activity, card, meta);
        }
        if (card.getChildCount() > 2 && card.getChildAt(2) instanceof LinearLayout) {
            final LinearLayout progress = (LinearLayout) card.getChildAt(2);
            progress.setMinimumHeight(Ui.dp(activity, 6));
            for (int i = 0; i < progress.getChildCount(); i++) {
                final View segment = progress.getChildAt(i);
                final ViewGroup.LayoutParams raw = segment.getLayoutParams();
                if (raw instanceof LinearLayout.LayoutParams) {
                    final LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) raw;
                    p.height = Ui.dp(activity, 6);
                    segment.setLayoutParams(p);
                }
            }
        }
        for (int i = 3; i < card.getChildCount(); i++) {
            if (card.getChildAt(i) instanceof LinearLayout) polishActionRow(activity, (LinearLayout) card.getChildAt(i));
        }
    }

    private static void polishHistoryCard(final Activity activity, final LinearLayout card) {
        if (card.getChildCount() > 0 && card.getChildAt(0) instanceof TextView) {
            final TextView title = (TextView) card.getChildAt(0);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            title.setTextSize(Ui.size(15));
            title.setTextColor(Ui.TEXT);
            title.setMaxLines(2);
        }
        if (card.getChildCount() > 1 && card.getChildAt(1) instanceof TextView) {
            final TextView state = (TextView) card.getChildAt(1);
            state.setTextSize(Ui.size(11));
            state.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            state.setPadding(0, Ui.dp(activity, 6), 0, Ui.dp(activity, 2));
            applyStateAppearance(activity, card, state);
        }
        for (int i = 2; i < card.getChildCount(); i++) {
            if (card.getChildAt(i) instanceof LinearLayout) polishActionRow(activity, (LinearLayout) card.getChildAt(i));
        }
    }

    private static void applyStateAppearance(final Activity activity, final LinearLayout card,
                                             final TextView stateView) {
        final String text = stateView.getText() == null ? ""
                : stateView.getText().toString().toLowerCase(java.util.Locale.US);
        final boolean done = text.contains("done") || text.contains("complete") || text.contains("saved");
        final boolean failed = text.contains("failed") || text.contains("error");
        final boolean cancelled = text.contains("cancelled") || text.contains("canceled");

        if (done) {
            stateView.setTextColor(Ui.OK);
            card.setBackground(Ui.box(activity, Ui.SURFACE_RAISED, Color.parseColor("#315D4A"), 18));
            card.setContentDescription("Download complete. Open or share this file.");
        } else if (failed) {
            stateView.setTextColor(Ui.ERR);
            card.setBackground(Ui.box(activity, Ui.SURFACE_RAISED, Color.parseColor("#67383D"), 18));
            card.setContentDescription("Download failed. Retry is available.");
        } else if (cancelled) {
            stateView.setTextColor(Ui.MUTED);
            card.setBackground(Ui.box(activity, Ui.SURFACE_RAISED, Color.parseColor("#4A515C"), 18));
            card.setContentDescription("Download cancelled.");
        }
    }

    private static void polishActionRow(final Activity activity, final LinearLayout row) {
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < row.getChildCount(); i++) {
            final View child = row.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            final TextView action = (TextView) child;
            final String label = action.getText() == null ? "" : action.getText().toString().trim().toLowerCase(java.util.Locale.US);
            final boolean open = label.contains("open");
            final boolean retry = label.contains("retry");
            final boolean resume = label.contains("resume");
            final boolean share = label.contains("share");
            final boolean primary = open || retry || resume;

            action.setMinHeight(Ui.dp(activity, 40));
            action.setGravity(Gravity.CENTER);
            action.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            action.setTextSize(Ui.size(10.5f));
            action.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 7), Ui.dp(activity, 12), Ui.dp(activity, 7));

            if (retry) {
                action.setBackground(Ui.pressable(activity, Color.parseColor("#392229"), Color.parseColor("#4B2931"), 12));
                action.setTextColor(Ui.ERR);
            } else if (open || resume) {
                action.setBackground(Ui.pressable(activity, Ui.ACCENT_SOFT, Ui.SURFACE_RAISED, 12));
                action.setTextColor(Ui.ACCENT);
            } else if (share) {
                action.setBackground(Ui.pressable(activity, Ui.SURFACE, Ui.SURFACE_RAISED, 12));
                action.setTextColor(Ui.OK);
            } else {
                action.setBackground(Ui.pressable(activity, Ui.SURFACE, Ui.SURFACE_RAISED, 12));
            }

            if (primary) action.setMinWidth(Ui.dp(activity, 82));
        }
    }
}
