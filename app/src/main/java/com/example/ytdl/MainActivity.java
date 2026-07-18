package com.example.ytdl;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static boolean extractorReady = false;

    /** Extraction only — downloading belongs to the service. */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private final List<Option> options = new ArrayList<>();
    /** Merge-eligible audio tracks, best-ranked first. Index matches the spinner. */
    private final List<AudioStream> audioTracks = new ArrayList<>();
    private List<DownloadService.Task> queueSnapshot = new ArrayList<>();

    private EditText urlInput;
    private TextView status;
    private Spinner audioSpinner;
    private TextView fetchTabLabel;
    private TextView queueTabLabel;
    private View fetchPane;
    private View queuePane;

    private FormatAdapter formatAdapter;
    private QueueAdapter queueAdapter;
    private ArrayAdapter<String> audioAdapter;

    private String videoTitle = "video";

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!extractorReady) {
            NewPipe.init(new Net.OkHttpDownloader(), new Localization("en", "US"));
            extractorReady = true;
        }

        setContentView(buildUi());
        selectTab(true);

        // Android 13 made notifications opt-in. Without this the service still runs but its
        // progress notification is invisible — which reads exactly like a broken queue.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.post(queuePoll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(queuePoll);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    // ---------------------------------------------------------------- chrome

    private View buildUi() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);

        root.addView(buildHeader());
        root.addView(buildTabs());

        fetchPane = buildFetchPane();
        queuePane = buildQueuePane();

        final SwipePager content = new SwipePager(this);
        content.addView(fetchPane);
        content.addView(queuePane);
        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    /**
     * Horizontal flings switch tabs.
     *
     * The detector is fed from dispatchTouchEvent and never consumes anything — it only
     * watches. Intercepting would mean fighting the ListViews for the gesture, and a
     * ListView calls requestDisallowInterceptTouchEvent the moment it starts scrolling, so
     * an interception-based version drops swipes that begin on a scrolled list. Observing
     * sidesteps the argument entirely: vertical flings are ignored by the angle check, and
     * ListView has no use for horizontal ones.
     */
    private final class SwipePager extends FrameLayout {

        private final GestureDetector detector;

        SwipePager(final Context context) {
            super(context);
            final float minVelocity = ViewConfiguration.get(context)
                    .getScaledMinimumFlingVelocity() * 1.5f;

            detector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onFling(final MotionEvent down, final MotionEvent up,
                                       final float vx, final float vy) {
                    // down is nullable from API 33, and a fling with no start isn't one.
                    if (down == null || up == null) {
                        return false;
                    }
                    if (Math.abs(vx) < minVelocity || Math.abs(vx) < Math.abs(vy) * 1.5f) {
                        return false;
                    }
                    // Left drags the next tab in; right drags the previous one back.
                    animateTo(vx < 0);
                    return true;
                }
            });
        }

        @Override
        public boolean dispatchTouchEvent(final MotionEvent event) {
            detector.onTouchEvent(event);
            return super.dispatchTouchEvent(event);
        }
    }

    private void animateTo(final boolean toQueue) {
        final boolean showingQueue = queuePane.getVisibility() == View.VISIBLE;
        if (showingQueue == toQueue) {
            return;
        }

        selectTab(!toQueue);

        final View incoming = toQueue ? queuePane : fetchPane;
        final int width = incoming.getWidth() > 0 ? incoming.getWidth() : Ui.dp(this, 320);
        incoming.setTranslationX(toQueue ? width : -width);
        incoming.animate().translationX(0).setDuration(170).start();
    }

    private View buildHeader() {
        final TextView title = Ui.mono(this, 12, Ui.MUTED);
        title.setText("YT DOWNLOADER");
        title.setLetterSpacing(0.24f);
        title.setPadding(Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 20), Ui.dp(this, 14));
        return title;
    }

    /**
     * Two labels over a FrameLayout rather than TabHost. TabHost looks like 2011 and resists
     * styling at every turn; this is the same behaviour with none of the argument.
     */
    private View buildTabs() {
        final LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);

        fetchTabLabel = tabLabel("FETCH", true);
        queueTabLabel = tabLabel("DOWNLOADS", false);

        bar.addView(fetchTabLabel);
        bar.addView(queueTabLabel);

        final LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(bar);

        final View rule = new View(this);
        rule.setBackgroundColor(Ui.LINE);
        wrap.addView(rule, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 0.5f))));

        return wrap;
    }

    private TextView tabLabel(final String text, final boolean fetch) {
        final TextView t = Ui.mono(this, 13, Ui.MUTED);
        t.setText(text);
        t.setLetterSpacing(0.12f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        t.setOnClickListener(v -> selectTab(fetch));
        return t;
    }

    private void selectTab(final boolean fetch) {
        fetchPane.setVisibility(fetch ? View.VISIBLE : View.GONE);
        queuePane.setVisibility(fetch ? View.GONE : View.VISIBLE);

        fetchTabLabel.setTextColor(fetch ? Ui.TEXT : Ui.MUTED);
        queueTabLabel.setTextColor(fetch ? Ui.MUTED : Ui.TEXT);
        fetchTabLabel.setTypeface(Typeface.MONOSPACE, fetch ? Typeface.BOLD : Typeface.NORMAL);
        queueTabLabel.setTypeface(Typeface.MONOSPACE, fetch ? Typeface.NORMAL : Typeface.BOLD);
    }

    // ---------------------------------------------------------------- fetch pane

    private View buildFetchPane() {
        final int pad = Ui.dp(this, 20);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, Ui.dp(this, 16), pad, 0);

        urlInput = new EditText(this);
        urlInput.setHint("youtube.com/watch?v=…");
        urlInput.setHintTextColor(Ui.MUTED);
        urlInput.setTextColor(Ui.TEXT);
        urlInput.setTextSize(Ui.size(14));
        urlInput.setTypeface(Typeface.MONOSPACE);
        urlInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setMaxLines(2);
        urlInput.setBackground(Ui.box(this, Ui.SURFACE, Ui.LINE, 6));
        urlInput.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        root.addView(urlInput);

        final Button fetch = new Button(this);
        fetch.setText("FETCH");
        fetch.setTextColor(Ui.BG);
        fetch.setTextSize(Ui.size(14));
        fetch.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        fetch.setLetterSpacing(0.12f);
        fetch.setAllCaps(false);
        fetch.setStateListAnimator(null);
        fetch.setBackground(Ui.pressable(this, Ui.ACCENT, Color.parseColor("#C8862A"), 6));
        fetch.setOnClickListener(v -> fetch(urlInput.getText().toString().trim()));
        final LinearLayout.LayoutParams fetchParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 44));
        fetchParams.topMargin = Ui.dp(this, 10);
        root.addView(fetch, fetchParams);

        status = Ui.sans(this, 14, Ui.MUTED);
        status.setText("Paste a YouTube link above, then tap Fetch.");
        status.setLineSpacing(Ui.dp(this, 3), 1f);
        status.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 4));
        root.addView(status);

        // Which audio track a merge pairs with is a guess the app shouldn't make silently —
        // YouTube's metadata about it isn't always there. Show the ranking's answer, let it
        // be overridden.
        audioSpinner = new Spinner(this);
        audioAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, new ArrayList<>()) {
            @Override
            public View getView(final int position, final View convertView,
                                final ViewGroup parent) {
                final TextView v = (TextView) super.getView(position, convertView, parent);
                v.setTextColor(Ui.TEXT);
                v.setTypeface(Typeface.MONOSPACE);
                v.setTextSize(Ui.size(13));
                return v;
            }
        };
        audioAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        audioSpinner.setAdapter(audioAdapter);
        audioSpinner.setVisibility(View.GONE);
        audioSpinner.setBackground(Ui.box(this, Ui.SURFACE, Ui.LINE, 6));
        root.addView(audioSpinner);

        final ListView list = new ListView(this);
        formatAdapter = new FormatAdapter();
        list.setAdapter(formatAdapter);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setBackgroundColor(Color.TRANSPARENT);
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setOnItemClickListener((p, v, position, id) -> enqueue(options.get(position)));
        final LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        listParams.topMargin = Ui.dp(this, 12);
        root.addView(list, listParams);

        return root;
    }

    /** Two lines: the figure that matters, then the machine detail under it, both mono. */
    private final class FormatAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return options.size();
        }

        @Override
        public Object getItem(final int position) {
            return options.get(position);
        }

        @Override
        public long getItemId(final int position) {
            return position;
        }

        @Override
        public View getView(final int position, final View convertView, final ViewGroup parent) {
            final Context c = MainActivity.this;
            final LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackground(Ui.pressable(c, Ui.SURFACE, Ui.LINE, 6));
            row.setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12));

            final Option option = options.get(position);

            final TextView primary = Ui.mono(c, 17, Ui.TEXT);
            primary.setText(option.primary);
            primary.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            row.addView(primary);

            final TextView detail = Ui.mono(c, 12, Ui.MUTED);
            detail.setText(option.detail);
            detail.setPadding(0, Ui.dp(c, 3), 0, 0);
            row.addView(detail);

            final LinearLayout wrap = new LinearLayout(c);
            wrap.setPadding(0, 0, 0, Ui.dp(c, 8));
            wrap.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            return wrap;
        }
    }

    // ---------------------------------------------------------------- queue pane

    private View buildQueuePane() {
        final int pad = Ui.dp(this, 20);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, Ui.dp(this, 16), pad, Ui.dp(this, 12));

        final ListView list = new ListView(this);
        queueAdapter = new QueueAdapter();
        list.setAdapter(queueAdapter);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setBackgroundColor(Color.TRANSPARENT);
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        final Button clear = new Button(this);
        clear.setText("Clear finished");
        clear.setAllCaps(false);
        clear.setTextColor(Ui.MUTED);
        clear.setTextSize(Ui.size(13));
        clear.setTypeface(Typeface.MONOSPACE);
        clear.setStateListAnimator(null);
        clear.setBackground(Ui.box(this, Color.TRANSPARENT, Ui.LINE, 6));
        clear.setOnClickListener(v -> {
            DownloadService.clearFinished();
            refreshQueue();
        });
        root.addView(clear, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 40)));

        return root;
    }

    /**
     * A row is: title, a mono status line, and the progress rule. The rule is the one
     * flourish — a hairline that fills rather than a bar widget, so a queue of five reads as
     * five lines advancing at different rates.
     */
    private final class QueueAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return Math.max(1, queueSnapshot.size());
        }

        @Override
        public Object getItem(final int position) {
            return position < queueSnapshot.size() ? queueSnapshot.get(position) : null;
        }

        @Override
        public long getItemId(final int position) {
            return position;
        }

        @Override
        public View getView(final int position, final View convertView, final ViewGroup parent) {
            final Context c = MainActivity.this;

            if (queueSnapshot.isEmpty()) {
                final TextView empty = Ui.sans(c, 14, Ui.MUTED);
                empty.setText("Nothing queued.\nPick a format on the Fetch tab.");
                empty.setPadding(Ui.dp(c, 4), Ui.dp(c, 24), 0, 0);
                return empty;
            }

            final DownloadService.Task task = queueSnapshot.get(position);

            final LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Ui.box(c, Ui.SURFACE, Ui.LINE, 6));
            card.setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12));

            final TextView title = Ui.sans(c, 14, Ui.TEXT);
            title.setText(task.title);
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(title);

            final TextView meta = Ui.mono(c, 12, stateColor(task.state));
            meta.setText(metaLine(task));
            meta.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 10));
            card.addView(meta);

            card.addView(progressRule(c, task));

            if (!task.state.finished()) {
                card.addView(controls(c, task));
            }

            final LinearLayout wrap = new LinearLayout(c);
            wrap.setPadding(0, 0, 0, Ui.dp(c, 8));
            wrap.addView(card, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            return wrap;
        }
    }

    /**
     * Two weighted views rather than a ProgressBar. A weight of 0 collapses to nothing, so
     * 0% and 100% both land correctly without special-casing, and the colours are ours.
     */
    private View progressRule(final Context c, final DownloadService.Task task) {
        final int percent = task.state == DownloadService.State.DONE ? 100 : task.percent();

        final LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setWeightSum(100f);

        final View filled = new View(c);
        filled.setBackgroundColor(stateColor(task.state));
        bar.addView(filled, new LinearLayout.LayoutParams(0,
                Math.max(2, Ui.dp(c, 2)), percent));

        final View rest = new View(c);
        rest.setBackgroundColor(Ui.LINE);
        bar.addView(rest, new LinearLayout.LayoutParams(0,
                Math.max(2, Ui.dp(c, 2)), 100 - percent));

        return bar;
    }

    private View controls(final Context c, final DownloadService.Task task) {
        final LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Ui.dp(c, 10), 0, 0);

        final boolean paused = task.state == DownloadService.State.PAUSED;

        row.addView(smallButton(c, paused ? "Resume" : "Pause", Ui.ACCENT, v -> {
            if (paused) {
                DownloadService.resume(MainActivity.this, task);
            } else {
                DownloadService.pause(task);
            }
            refreshQueue();
        }));

        row.addView(smallButton(c, "Cancel", Ui.MUTED, v -> {
            DownloadService.cancel(MainActivity.this, task);
            refreshQueue();
        }));

        return row;
    }

    private View smallButton(final Context c, final String text, final int color,
                             final View.OnClickListener click) {
        final TextView b = Ui.mono(c, 12, color);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Ui.dp(c, 14), Ui.dp(c, 7), Ui.dp(c, 14), Ui.dp(c, 7));
        b.setBackground(Ui.box(c, Color.TRANSPARENT, Ui.LINE, 4));
        b.setOnClickListener(click);

        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.rightMargin = Ui.dp(c, 8);
        b.setLayoutParams(params);
        return b;
    }

    private static String metaLine(final DownloadService.Task task) {
        final StringBuilder s = new StringBuilder()
                .append(task.formatLabel).append("  ")
                .append(task.state.label.toUpperCase(Locale.US));

        if (task.state.active() && task.total > 0) {
            s.append("  ").append(Ui.bytes(task.done))
                    .append(" / ").append(Ui.bytes(task.total));
        } else if (task.state == DownloadService.State.PAUSED && task.done > 0) {
            s.append("  ").append(Ui.bytes(task.done)).append(" so far");
        }
        if (task.error != null) {
            s.append("  ").append(task.error);
        }
        return s.toString();
    }

    private static int stateColor(final DownloadService.State state) {
        if (state == DownloadService.State.DONE) {
            return Ui.OK;
        }
        if (state == DownloadService.State.FAILED) {
            return Ui.ERR;
        }
        if (state.active()) {
            return Ui.ACCENT;
        }
        return Ui.MUTED;
    }

    private final Runnable queuePoll = new Runnable() {
        @Override
        public void run() {
            refreshQueue();
            // Polling rather than a listener: no registration to leak, and the service can
            // outlive this activity entirely.
            main.postDelayed(this, 500);
        }
    };

    private void refreshQueue() {
        queueSnapshot = DownloadService.snapshot();
        if (queueAdapter != null) {
            queueAdapter.notifyDataSetChanged();
        }
    }

    // ---------------------------------------------------------------- extraction

    private void fetch(final String url) {
        if (url.isEmpty()) {
            status.setText("Paste a YouTube link above, then tap Fetch.");
            return;
        }

        status.setText("Fetching…");
        options.clear();
        formatAdapter.notifyDataSetChanged();

        executor.execute(() -> {
            try {
                final StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, url);
                main.post(() -> show(info));
            } catch (final Exception e) {
                main.post(() -> status.setText("Couldn't read that video: " + e.getMessage()));
            }
        });
    }

    private void show(final StreamInfo info) {
        videoTitle = sanitize(info.getName());

        // Merge-eligible audio: AAC in an .m4a container, because that's what MediaMuxer
        // accepts alongside H.264 in an MP4. Ranked best-first so the spinner defaults well.
        audioTracks.clear();
        audioAdapter.clear();
        for (final AudioStream as : info.getAudioStreams()) {
            if (as.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) {
                continue;
            }
            if (as.getFormat() != org.schabi.newpipe.extractor.MediaFormat.M4A) {
                continue;
            }
            audioTracks.add(as);
        }
        Collections.sort(audioTracks, (a, b) -> Long.compare(rank(b), rank(a)));

        for (final AudioStream as : audioTracks) {
            audioAdapter.add(trackLabel(as));
        }
        audioAdapter.notifyDataSetChanged();
        audioSpinner.setVisibility(audioTracks.isEmpty() ? View.GONE : View.VISIBLE);
        if (!audioTracks.isEmpty()) {
            audioSpinner.setSelection(0);
        }

        status.setText(info.getName() + "\n" + info.getUploaderName()
                + (audioTracks.isEmpty() ? "" : "\n\nAudio for merges ↓"));

        // High resolutions live here: video with no audio track at all. Each gets merged
        // with whichever track is selected in the spinner when it's queued.
        final List<VideoStream> videoOnly = new ArrayList<>();
        for (final VideoStream vs : info.getVideoOnlyStreams()) {
            if (vs.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) {
                continue;
            }
            // MPEG-4 only. YouTube also serves WebM/VP9 at these resolutions, but pairing
            // VP9 with AAC needs a WebM muxer and a different audio choice — two containers
            // to reason about instead of one, for no visible gain.
            if (vs.getFormat() != org.schabi.newpipe.extractor.MediaFormat.MPEG_4) {
                continue;
            }
            videoOnly.add(vs);
        }
        Collections.sort(videoOnly,
                (a, b) -> heightOf(b.getResolution()) - heightOf(a.getResolution()));

        if (!audioTracks.isEmpty()) {
            for (final VideoStream vs : videoOnly) {
                options.add(new Option(vs.getResolution(),
                        nameOf(vs.getFormat()) + " · merge with audio",
                        vs.getContent(), true, "mp4", "video/mp4"));
            }
        }

        // Muxed: video and audio already in one file. Tops out at 360p on most videos,
        // occasionally 720p.
        for (final VideoStream vs : info.getVideoStreams()) {
            if (vs.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) {
                continue;
            }
            options.add(new Option(vs.getResolution(),
                    nameOf(vs.getFormat()) + " · direct, no merge",
                    vs.getContent(), false, suffixOf(vs.getFormat(), "mp4"), "video/mp4"));
        }

        for (final AudioStream as : info.getAudioStreams()) {
            if (as.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) {
                continue;
            }
            options.add(new Option("Audio", trackLabel(as) + " · " + nameOf(as.getFormat()),
                    as.getContent(), false, suffixOf(as.getFormat(), "m4a"), "audio/mp4"));
        }

        if (options.isEmpty()) {
            status.setText("No directly downloadable streams here. Live streams and some "
                    + "videos are served as manifests this app can't fetch.");
        }
        formatAdapter.notifyDataSetChanged();
    }

    // ---------------------------------------------------------------- enqueue

    private void enqueue(final Option option) {
        String audioUrl = null;

        if (option.needsMerge) {
            // Read the spinner here rather than at fetch time, so changing the track
            // actually changes what gets queued.
            final int selected = audioSpinner.getSelectedItemPosition();
            if (selected < 0 || selected >= audioTracks.size()) {
                status.setText("Pick an audio track first.");
                return;
            }
            audioUrl = audioTracks.get(selected).getContent();
        }

        DownloadService.enqueue(this, new DownloadService.Task(
                videoTitle, option.videoUrl, audioUrl,
                option.extension, option.mimeType, option.primary));

        Toast.makeText(this, "Queued", Toast.LENGTH_SHORT).show();
        refreshQueue();
        selectTab(false);
    }

    // ---------------------------------------------------------------- helpers

    /** One entry in the format list. needsMerge means it has no audio of its own. */
    private static final class Option {
        final String primary;
        final String detail;
        final String videoUrl;
        final boolean needsMerge;
        final String extension;
        final String mimeType;

        Option(final String primary, final String detail, final String videoUrl,
               final boolean needsMerge, final String extension, final String mimeType) {
            this.primary = primary;
            this.detail = detail;
            this.videoUrl = videoUrl;
            this.needsMerge = needsMerge;
            this.extension = extension;
            this.mimeType = mimeType;
        }
    }

    /**
     * Bitrate alone isn't enough. YouTube ships dubbed audio tracks next to the original,
     * often at identical bitrates, so ranking on kbps would pick whichever came first — a
     * coin flip between English and a dub. The original wins outright; bitrate breaks ties.
     * If YouTube didn't tag the tracks, everything scores equal and the spinner is the only
     * thing standing between you and a random language.
     */
    private static long rank(final AudioStream stream) {
        final AudioTrackType type = stream.getAudioTrackType();
        final long originalBonus = type == AudioTrackType.ORIGINAL ? 1_000_000L : 0L;
        return originalBonus + Math.max(0, stream.getAverageBitrate());
    }

    /**
     * Deliberately spells out the track type. If every entry reads "untagged", YouTube sent
     * no track metadata, rank() had nothing to work with, and the choice is genuinely yours.
     */
    private static String trackLabel(final AudioStream stream) {
        final String name = stream.getAudioTrackName();
        final Locale locale = stream.getAudioLocale();

        final String who;
        if (name != null && !name.isEmpty()) {
            who = name;
        } else if (locale != null) {
            who = locale.getDisplayName();
        } else {
            who = "Track";
        }

        final AudioTrackType type = stream.getAudioTrackType();
        final String kind = type == null ? "untagged" : type.name().toLowerCase(Locale.US);
        final String rate = " · " + stream.getAverageBitrate() + " kbps";

        // YouTube's track names frequently already contain the word — "English (US) original"
        // becoming "English (US) original (original)" is just a stutter. Only add the marker
        // when it says something the name doesn't, which is exactly when it matters most:
        // an untagged track the ranking couldn't reason about.
        if (who.toLowerCase(Locale.US).contains(kind)) {
            return who + rate;
        }
        return who + " (" + kind + ")" + rate;
    }

    /** "1080p60" -> 1080, for sorting. */
    private static int heightOf(final String resolution) {
        if (resolution == null) {
            return 0;
        }
        int end = 0;
        while (end < resolution.length() && Character.isDigit(resolution.charAt(end))) {
            end++;
        }
        return end == 0 ? 0 : Integer.parseInt(resolution.substring(0, end));
    }

    private static String nameOf(final org.schabi.newpipe.extractor.MediaFormat format) {
        return format == null ? "unknown" : format.getName();
    }

    private static String suffixOf(final org.schabi.newpipe.extractor.MediaFormat format,
                                   final String fallback) {
        return format == null ? fallback : format.getSuffix();
    }

    /** Video titles routinely contain characters that are illegal in filenames. */
    private static String sanitize(final String name) {
        final String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (cleaned.isEmpty()) {
            return "video";
        }
        return cleaned.length() > 100 ? cleaned.substring(0, 100) : cleaned;
    }
}
