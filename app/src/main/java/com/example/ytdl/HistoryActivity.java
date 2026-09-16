package com.example.ytdl;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shows completed/failed/cancelled tasks kept by the durable queue. */
public final class HistoryActivity extends Activity {
    private final List<DownloadService.Task> items = new ArrayList<>();
    private HistoryAdapter adapter;

    @Override
    protected void onCreate(final Bundle state) {
        super.onCreate(state);
        DownloadService.ensureLoaded(this);
        setContentView(buildUi());
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private View buildUi() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 20), Ui.dp(this, 12));

        final TextView title = Ui.mono(this, 15, Ui.TEXT);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setText("HISTORY");
        title.setLetterSpacing(0.15f);
        root.addView(title);

        final TextView help = Ui.sans(this, 13, Ui.MUTED);
        help.setText("Tap a failed or cancelled item to queue it again.");
        help.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 12));
        root.addView(help);

        final ListView list = new ListView(this);
        adapter = new HistoryAdapter();
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setBackgroundColor(Color.TRANSPARENT);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= items.size()) return;
            final DownloadService.Task task = items.get(position);
            if (task.state == DownloadService.State.FAILED
                    || task.state == DownloadService.State.CANCELLED) {
                DownloadService.retry(this, task);
                Toast.makeText(this, "Queued again", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private void refresh() {
        items.clear();
        final List<DownloadService.Task> all = DownloadService.snapshot();
        Collections.reverse(all);
        for (final DownloadService.Task task : all) {
            if (task.state.finished()) items.add(task);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private final class HistoryAdapter extends BaseAdapter {
        @Override public int getCount() { return Math.max(1, items.size()); }
        @Override public Object getItem(final int p) { return p < items.size() ? items.get(p) : null; }
        @Override public long getItemId(final int p) { return p; }

        @Override
        public View getView(final int position, final View convertView, final android.view.ViewGroup parent) {
            final Context c = HistoryActivity.this;
            if (items.isEmpty()) {
                final TextView empty = Ui.sans(c, 14, Ui.MUTED);
                empty.setText("No download history yet.");
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(0, Ui.dp(c, 36), 0, 0);
                return empty;
            }

            final DownloadService.Task task = items.get(position);
            final LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Ui.box(c, Ui.SURFACE, Ui.LINE, 6));
            card.setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12));

            final TextView name = Ui.sans(c, 14, Ui.TEXT);
            name.setText(task.title);
            name.setMaxLines(2);
            card.addView(name);

            final TextView state = Ui.mono(c, 12,
                    task.state == DownloadService.State.DONE ? Ui.OK
                            : task.state == DownloadService.State.FAILED ? Ui.ERR : Ui.MUTED);
            state.setText(task.formatLabel() + " · " + task.state.label);
            state.setPadding(0, Ui.dp(c, 4), 0, 0);
            card.addView(state);

            final LinearLayout wrap = new LinearLayout(c);
            wrap.setPadding(0, 0, 0, Ui.dp(c, 8));
            wrap.addView(card, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            return wrap;
        }
    }
}
