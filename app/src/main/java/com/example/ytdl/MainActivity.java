package com.example.ytdl;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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

import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    /** Shared text is usually "Title\nhttps://youtu.be/…", so take the first URL in it. */
    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://\\S+");

    /** Playlists can run to thousands; pagination is a request per page. */
    private static final int PLAYLIST_CAP = 200;

    /** Extraction only — downloading belongs to the service. */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private final List<Option> options = new ArrayList<>();
    /** Merge-eligible audio tracks, best-ranked first. Index matches the spinner. */
    private final List<AudioStream> audioTracks = new ArrayList<>();
    /** Non-empty means the fetched URL was a playlist and options are target qualities. */
    private final List<Entry> playlist = new ArrayList<>();
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

    private String pageUrl = "";
    private String videoTitle = "video";

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Net.ensureExtractor();
        // Either this or the service can be first alive in a fresh process.
        DownloadService.ensureLoaded(this);

        setContentView(buildUi());
        selectTab(true);

        // Android 13 made notifications opt-in. Without this the service still runs but its
        // progress notification is invisible — which reads exactly like a broken queue.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        handleShare(getIntent());
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        // singleTop in the manifest: a second share arrives here rather than as a new
        // activity stacked on the first.
        setIntent(intent);
        handleShare(intent);
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

    /** Share → YT Downloader, and it fetches on open. */
    private void handleShare(final Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) {
            return;
        }
        final String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null) {
            return;
        }
        final Matcher matcher = URL_IN_TEXT.matcher(text);
        if (!matcher.find()) {
            return;
        }
        final String url = matcher.group();
        urlInput.setText(url);
        selectTab(true);
        fetch(url);
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
     * sidesteps the argument: vertical flings fail the angle check, and ListView has no use
     * for horizontal ones.
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
        if ((queuePane.getVisibility() == View.VISIBLE) == toQueue) {
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
        urlInput.setHint("youtube.com/watch?v=… or a playlist");
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
        status.setText("Paste a link, or share one to this app.");
        status.setLineSpacing(Ui.dp(this, 3), 1f);
        status.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 4));
        root.addView(status);

        // Which audio track a merge pairs with is a guess the app shouldn't make silently —
        // YouTube's metadata about it isn't always there. Show the ranking's answer, let it
        // be overridden. Hidden for playlists: forty videos don't share track ids.
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
            DownloadService.clearFinished(this);
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
            } else if (task.state == DownloadService.State.FAILED) {
                card.addView(retryControls(c, task));
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
        bar.addView(filled, new LinearLayout.LayoutParams(0, Math.max(2, Ui.dp(c, 2)), percent));

        final View rest = new View(c);
        rest.setBackgroundColor(Ui.LINE);
        bar.addView(rest, new LinearLayout.LayoutParams(0, Math.max(2, Ui.dp(c, 2)),
                100 - percent));

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
                DownloadService.pause(MainActivity.this, task);
            }
            refreshQueue();
        }));

        row.addView(smallButton(c, "Cancel", Ui.MUTED, v -> {
            DownloadService.cancel(MainActivity.this, task);
            refreshQueue();
        }));

        return row;
    }

    /** A failed task keeps its page URL and spec, so retrying is just re-queuing it. */
    private View retryControls(final Context c, final DownloadService.Task task) {
        final LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Ui.dp(c, 10), 0, 0);
        row.addView(smallButton(c, "Retry", Ui.ACCENT, v -> {
            DownloadService.retry(MainActivity.this, task);
            refreshQueue();
        }));
        return row;
    }

    private static String size(final long bytes) {
        return bytes < 0 ? "" : " · " + Ui.bytes(bytes);
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
                .append(task.formatLabel()).append("  ")
                .append(task.state.label.toUpperCase(Locale.US));

        if (task.state.transferring() && task.total > 0) {
            s.append("  ").append(Ui.bytes(task.done))
                    .append(" / ").append(Ui.bytes(task.total));
            if (task.bytesPerSecond > 0) {
                s.append("  ").append(Ui.bytes(task.bytesPerSecond)).append("/s");
            }
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
            status.setText("Paste a link, or share one to this app.");
            return;
        }

        pageUrl = url;
        status.setText("Fetching…");
        options.clear();
        playlist.clear();
        audioTracks.clear();
        audioAdapter.clear();
        audioSpinner.setVisibility(View.GONE);
        formatAdapter.notifyDataSetChanged();

        executor.execute(() -> {
            try {
                // A /watch?v=X&list=Y URL is a video that happens to sit in a playlist, and
                // the link handler agrees — only a real playlist URL comes back PLAYLIST.
                if (linkType(url) == StreamingService.LinkType.PLAYLIST) {
                    fetchPlaylist(url);
                } else {
                    final StreamInfo firstInfo = StreamInfo.getInfo(ServiceList.YouTube, url);
                    final boolean missingFormats =
                            (firstInfo.getVideoOnlyStreams() == null || firstInfo.getVideoOnlyStreams().isEmpty())
                            && (firstInfo.getAudioStreams() == null || firstInfo.getAudioStreams().isEmpty());
                    final boolean serverUp = missingFormats && PoToken.isServerUp();

                    // On 0/0 with a server, re-extract with a poToken. Done in a helper that
                    // RETURNS the result rather than reassigning a captured variable — a lambda
                    // can only capture effectively-final locals, so the retry can't just
                    // overwrite `info` in place.
                    final StreamInfo shown = serverUp
                            ? withPoToken(url, firstInfo)
                            : firstInfo;

                    main.post(() -> showVideo(shown, serverUp));
                }
            } catch (final Exception e) {
                main.post(() -> status.setText("Couldn't read that link: " + e.getMessage()));
            }
        });
    }

    /**
     * Re-extract with a poToken from the local bgutil server, returning the richer result —
     * or the original if anything goes wrong. Kept as a returning helper so the caller's
     * lambda captures only final locals: a lambda can't reassign what it captures, so the
     * retry can't overwrite the first result in place.
     *
     * The provider is a static on the extractor and is cleared in finally, so it never leaks
     * to the next fetch. Fetches run on a single-thread executor, so two can't race it.
     */
    private StreamInfo withPoToken(final String url, final StreamInfo fallback) {
        final String videoId = extractVideoId(fallback, url);
        if (videoId == null) {
            return fallback;
        }
        main.post(() -> status.setText("Token server up — fetching a poToken and retrying…"));
        YoutubeStreamExtractor.setPoTokenProvider(new PoToken.Provider(videoId));
        try {
            return StreamInfo.getInfo(ServiceList.YouTube, url);
        } catch (final Exception e) {
            return fallback;
        } finally {
            YoutubeStreamExtractor.setPoTokenProvider(null);
        }
    }

    /**
     * The video id for the poToken request. StreamInfo.getId() already holds it for YouTube;
     * the URL is only a fallback in case a future extractor leaves it blank.
     */
    private static String extractVideoId(final StreamInfo info, final String url) {
        final String id = info.getId();
        if (id != null && !id.isEmpty()) {
            return id;
        }
        final Matcher m = Pattern.compile("(?:v=|youtu\\.be/|/shorts/)([A-Za-z0-9_-]{11})").matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private static StreamingService.LinkType linkType(final String url) {
        try {
            return ServiceList.YouTube.getLinkTypeByUrl(url);
        } catch (final Exception e) {
            return StreamingService.LinkType.NONE;
        }
    }

    private void fetchPlaylist(final String url) throws Exception {
        final PlaylistInfo info = PlaylistInfo.getInfo(ServiceList.YouTube, url);
        final List<Entry> entries = new ArrayList<>();

        for (final StreamInfoItem item : info.getRelatedItems()) {
            entries.add(new Entry(item.getUrl(), Streams.sanitize(item.getName())));
        }

        // Each page is another round trip, so there's a cap. A 3000-video playlist isn't
        // something to sit through on a phone.
        Page next = info.getNextPage();
        while (next != null && entries.size() < PLAYLIST_CAP) {
            main.post(() -> status.setText("Fetching playlist… " + entries.size() + " so far"));
            final ListExtractor.InfoItemsPage<StreamInfoItem> more =
                    PlaylistInfo.getMoreItems(ServiceList.YouTube, url, next);
            for (final StreamInfoItem item : more.getItems()) {
                entries.add(new Entry(item.getUrl(), Streams.sanitize(item.getName())));
            }
            next = more.getNextPage();
        }

        main.post(() -> showPlaylist(info.getName(), entries));
    }

    /**
     * A playlist offers target qualities rather than actual streams: forty videos don't
     * share a format list, so the choice has to be an intention that each task resolves for
     * itself when its turn comes.
     */
    private void showPlaylist(final String name, final List<Entry> entries) {
        playlist.clear();
        playlist.addAll(entries);

        if (entries.isEmpty()) {
            status.setText("That playlist came back empty.");
            formatAdapter.notifyDataSetChanged();
            return;
        }

        status.setText(name + "\n" + entries.size() + " videos"
                + (entries.size() >= PLAYLIST_CAP ? " (capped)" : "")
                + "\n\nPick a target quality — each video gets the best at or below it.");

        for (final int height : new int[]{2160, 1080, 720, 480, 360}) {
            options.add(new Option(height + "p",
                    "merge with audio · queue " + entries.size(),
                    DownloadService.Spec.merge(height, null, height + "p"),
                    null, null, "mp4", "video/mp4"));
        }
        options.add(new Option("Audio", "best track · queue " + entries.size(),
                DownloadService.Spec.audio(null, "Audio"),
                null, null, "m4a", "audio/mp4"));

        formatAdapter.notifyDataSetChanged();
    }

    private void showVideo(final StreamInfo info, final boolean tokenServerUp) {
        videoTitle = Streams.sanitize(info.getName());

        audioTracks.addAll(Streams.mergeAudio(info));
        for (final AudioStream as : audioTracks) {
            audioAdapter.add(Streams.trackLabel(as));
        }
        audioAdapter.notifyDataSetChanged();
        audioSpinner.setVisibility(audioTracks.isEmpty() ? View.GONE : View.VISIBLE);
        if (!audioTracks.isEmpty()) {
            audioSpinner.setSelection(0);
        }

        final StringBuilder header = new StringBuilder(info.getName())
                .append('\n').append(info.getUploaderName());
        if (audioTracks.isEmpty()) {
            // No audio means no merges, so the list collapses to whatever muxed stream
            // exists. That looks identical whether YouTube withheld the formats or these
            // filters dropped them, and the two need opposite fixes.
            header.append("\n\n").append(Streams.diagnostics(info, tokenServerUp));
        } else {
            header.append("\n\nAudio for merges ↓");
        }
        status.setText(header.toString());

        // Merged size is the video plus the audio it'll be paired with, so the number in
        // the list is what actually lands on disk rather than half of it.
        final long audioBytes = audioTracks.isEmpty() ? -1 : Streams.sizeOf(audioTracks.get(0));

        if (!audioTracks.isEmpty()) {
            for (final VideoStream vs : Streams.videoOnly(info)) {
                final int height = Streams.heightOf(vs.getResolution());
                final long videoBytes = Streams.sizeOf(vs);
                final long combined = videoBytes < 0 || audioBytes < 0
                        ? -1 : videoBytes + audioBytes;
                options.add(new Option(vs.getResolution(),
                        Streams.formatName(vs.getFormat()) + " · merge with audio"
                                + size(combined),
                        DownloadService.Spec.merge(height, null, vs.getResolution()),
                        vs.getContent(), null, "mp4", "video/mp4"));
            }
        }

        for (final VideoStream vs : Streams.muxed(info)) {
            options.add(new Option(vs.getResolution(),
                    Streams.formatName(vs.getFormat()) + " · direct, no merge"
                            + size(Streams.sizeOf(vs)),
                    DownloadService.Spec.muxed(Streams.heightOf(vs.getResolution()),
                            vs.getResolution()),
                    vs.getContent(), null, Streams.suffix(vs.getFormat(), "mp4"), "video/mp4"));
        }

        for (final AudioStream as : Streams.allAudio(info)) {
            options.add(new Option("Audio",
                    Streams.trackLabel(as) + " · " + Streams.formatName(as.getFormat())
                            + size(Streams.sizeOf(as)),
                    DownloadService.Spec.audio(as.getAudioTrackId(), "Audio"),
                    as.getContent(), null, Streams.suffix(as.getFormat(), "m4a"), "audio/mp4"));
        }

        for (final SubtitlesStream sub : Streams.subtitles(info)) {
            options.add(new Option("Subtitles",
                    Streams.subtitleLabel(sub) + " · " + Streams.formatName(sub.getFormat()),
                    DownloadService.Spec.subtitle(sub.getLanguageTag(),
                            "Subtitles " + sub.getLanguageTag()),
                    sub.getContent(), null,
                    sub.getLanguageTag() + "." + Streams.suffix(sub.getFormat(), "ttml"),
                    sub.getFormat() == null ? "text/plain" : sub.getFormat().getMimeType()));
        }

        if (options.isEmpty()) {
            status.setText("No directly downloadable streams here. Live streams and some "
                    + "videos are served as manifests this app can't fetch.");
        }
        formatAdapter.notifyDataSetChanged();
    }

    // ---------------------------------------------------------------- enqueue

    private void enqueue(final Option option) {
        if (!playlist.isEmpty()) {
            for (final Entry entry : playlist) {
                DownloadService.enqueue(this,
                        new DownloadService.Task(entry.url, entry.title, option.spec));
            }
            Toast.makeText(this, "Queued " + playlist.size(), Toast.LENGTH_SHORT).show();
            refreshQueue();
            selectTab(false);
            return;
        }

        String audioUrl = option.audioUrl;
        DownloadService.Spec spec = option.spec;

        if (option.spec.kind == DownloadService.Kind.MERGE) {  // NOSONAR — readability
            // Read the spinner here rather than at fetch time, so changing the track
            // actually changes what gets queued.
            final int selected = audioSpinner.getSelectedItemPosition();
            if (selected < 0 || selected >= audioTracks.size()) {
                status.setText("Pick an audio track first.");
                return;
            }
            final AudioStream track = audioTracks.get(selected);
            audioUrl = track.getContent();
            // Carry the id so a re-resolve after expiry lands on the same track.
            spec = DownloadService.Spec.merge(option.spec.height, track.getAudioTrackId(),
                    option.spec.label);
        }

        final DownloadService.Task task =
                new DownloadService.Task(pageUrl, videoTitle, spec);
        task.preResolve(option.videoUrl, audioUrl, option.extension, option.mimeType);
        DownloadService.enqueue(this, task);

        Toast.makeText(this, "Queued", Toast.LENGTH_SHORT).show();
        refreshQueue();
        selectTab(false);
    }

    // ---------------------------------------------------------------- models

    /** A playlist member: enough to queue it, not enough to download it. */
    private static final class Entry {
        final String url;
        final String title;

        Entry(final String url, final String title) {
            this.url = url;
            this.title = title;
        }
    }

    /**
     * One row in the format list. For a single video the URLs are already resolved, since
     * extraction is what produced this list. For a playlist they're null and every task
     * resolves itself.
     */
    private static final class Option {
        final String primary;
        final String detail;
        final DownloadService.Spec spec;
        final String videoUrl;
        final String audioUrl;
        final String extension;
        final String mimeType;

        Option(final String primary, final String detail, final DownloadService.Spec spec,
               final String videoUrl, final String audioUrl, final String extension,
               final String mimeType) {
            this.primary = primary;
            this.detail = detail;
            this.spec = spec;
            this.videoUrl = videoUrl;
            this.audioUrl = audioUrl;
            this.extension = extension;
            this.mimeType = mimeType;
        }
    }
}
