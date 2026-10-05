package com.example.ytdl;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.WeakHashMap;

/** Interaction-level polish for state that changes after the static layout pass. */
public final class InteractionUiPolish {
    private static final WeakHashMap<Activity, Boolean> INSTALLED = new WeakHashMap<>();

    private InteractionUiPolish() {}

    public static void install(final Activity activity) {
        if (!(activity instanceof MainActivity) || INSTALLED.containsKey(activity)) return;
        INSTALLED.put(activity, Boolean.TRUE);
        final View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;
        content.post(() -> installMain(activity, (ViewGroup) content));
    }

    private static void installMain(final Activity activity, final ViewGroup content) {
        if (content.getChildCount() == 0 || !(content.getChildAt(0) instanceof LinearLayout)) return;
        final LinearLayout root = (LinearLayout) content.getChildAt(0);
        if (root.getChildCount() < 3) return;

        final TextView[] tabs = findTabs(root.getChildAt(1));
        final View pagerView = root.getChildAt(2);
        if (tabs == null || !(pagerView instanceof FrameLayout)) return;
        final FrameLayout pager = (FrameLayout) pagerView;
        if (pager.getChildCount() < 2) return;
        final View fetchPane = pager.getChildAt(0);
        final View queuePane = pager.getChildAt(1);

        final Runnable updateTabs = () -> updateTabs(activity, tabs[0], tabs[1], fetchPane, queuePane);
        tabs[0].setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) v.post(updateTabs);
            return false;
        });
        tabs[1].setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) v.post(updateTabs);
            return false;
        });
        fetchPane.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> v.post(updateTabs));
        queuePane.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> v.post(updateTabs));
        updateTabs.run();

        if (fetchPane instanceof LinearLayout) installFetchFeedback(activity, (LinearLayout) fetchPane);
    }

    private static TextView[] findTabs(final View tabsView) {
        if (!(tabsView instanceof LinearLayout)) return null;
        final LinearLayout wrap = (LinearLayout) tabsView;
        if (wrap.getChildCount() == 0 || !(wrap.getChildAt(0) instanceof LinearLayout)) return null;
        final LinearLayout bar = (LinearLayout) wrap.getChildAt(0);
        if (bar.getChildCount() < 2 || !(bar.getChildAt(0) instanceof TextView)
                || !(bar.getChildAt(1) instanceof TextView)) return null;
        return new TextView[]{(TextView) bar.getChildAt(0), (TextView) bar.getChildAt(1)};
    }

    private static void updateTabs(final Activity activity, final TextView fetchTab,
                                   final TextView queueTab, final View fetchPane,
                                   final View queuePane) {
        final boolean fetchActive = fetchPane.getVisibility() == View.VISIBLE
                && queuePane.getVisibility() != View.VISIBLE;
        styleTab(activity, fetchTab, fetchActive);
        styleTab(activity, queueTab, !fetchActive);
    }

    private static void styleTab(final Activity activity, final TextView tab, final boolean active) {
        tab.setTextColor(active ? Ui.TEXT : Ui.MUTED);
        tab.setTypeface(Typeface.MONOSPACE, active ? Typeface.BOLD : Typeface.NORMAL);
        tab.setBackground(active
                ? Ui.box(activity, Ui.ACCENT_SOFT, Ui.ACCENT, 12)
                : Ui.pressable(activity, Color.TRANSPARENT, Ui.SURFACE_RAISED, 12));
    }

    private static void installFetchFeedback(final Activity activity, final LinearLayout pane) {
        final Button action = findPrimaryAction(pane);
        final TextView status = findStatus(pane);
        if (status == null) return;

        final String idleAction = action == null ? "" : action.getText().toString();
        final boolean[] lastBusy = new boolean[]{false};
        status.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(final CharSequence s, final int start,
                                                    final int count, final int after) {}
            @Override public void onTextChanged(final CharSequence s, final int start,
                                                final int before, final int count) {
                final String value = s == null ? "" : s.toString();
                final boolean busy = value.startsWith("Fetching") || value.startsWith("Loading more");
                final boolean error = value.startsWith("Couldn't") || value.startsWith("Pick an audio");
                final boolean empty = value.startsWith("No ") || value.contains("came back empty");

                if (busy) {
                    status.setTextColor(Ui.ACCENT);
                    status.setBackground(Ui.box(activity, Ui.ACCENT_SOFT, Ui.ACCENT, 14));
                } else if (error) {
                    status.setTextColor(Ui.ERR);
                    status.setBackground(Ui.box(activity, Ui.SURFACE, Ui.ERR, 14));
                } else if (empty) {
                    status.setTextColor(Ui.MUTED);
                    status.setBackground(Ui.box(activity, Ui.SURFACE, Ui.LINE, 14));
                } else {
                    status.setTextColor(Ui.TEXT);
                    status.setBackground(Ui.box(activity, Ui.SURFACE, Ui.LINE, 14));
                }

                if (action != null) {
                    action.setText(busy ? "WORKING…" : idleAction);
                    action.setAlpha(busy ? .72f : 1f);
                    action.setEnabled(!busy);
                    action.setClickable(!busy);
                    action.setFocusable(!busy);
                }

                if (busy != lastBusy[0] || error) {
                    status.setContentDescription(value);
                    status.sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT);
                    lastBusy[0] = busy;
                }
            }
            @Override public void afterTextChanged(final Editable s) {}
        });
    }

    private static Button findPrimaryAction(final View view) {
        if (view instanceof Button) {
            final Button button = (Button) view;
            final String text = button.getText() == null ? "" : button.getText().toString();
            if (text.contains("SEARCH") || text.contains("FETCH")) return button;
        }
        if (!(view instanceof ViewGroup)) return null;
        final ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            final Button result = findPrimaryAction(group.getChildAt(i));
            if (result != null) return result;
        }
        return null;
    }

    private static TextView findStatus(final LinearLayout pane) {
        for (int i = 0; i < pane.getChildCount(); i++) {
            final View child = pane.getChildAt(i);
            if (!(child instanceof TextView) || child instanceof Button) continue;
            final TextView text = (TextView) child;
            final CharSequence value = text.getText();
            if (value != null && (value.toString().contains("Paste a link")
                    || value.toString().startsWith("Fetching")
                    || value.toString().startsWith("Search results"))) {
                return text;
            }
        }
        return null;
    }
}
