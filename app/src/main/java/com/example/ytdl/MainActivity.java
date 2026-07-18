package com.example.ytdl;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TabHost;
import android.widget.TabWidget;
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

    /** Only used for extraction now — downloading belongs to the service. */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private final List<Option> options = new ArrayList<>();
    /** Merge-eligible audio tracks, best-ranked first. Index matches the spinner. */
    private final List<AudioStream> audioTracks = new ArrayList<>();

    private EditText urlInput;
    private TextView status;
    private Spinner audioSpinner;
    private ArrayAdapter<String> formatAdapter;
    private ArrayAdapter<String> audioAdapter;

    private ArrayAdapter<String> queueAdapter;
    private List<DownloadService.Task> queueSnapshot = new ArrayList<>();

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

        // Android 13 made notifications opt-in. Without this the service still runs, but
        // its progress notification is invisible — which looks exactly like a broken queue.
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

    // ---------------------------------------------------------------- ui

    private View buildUi() {
        final TabHost tabs = new TabHost(this, null);
        tabs.setId(android.R.id.tabhost);

        final LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);

        // TabHost insists on finding children with these exact framework ids. Built by hand
        // because there's no layout XML to inflate.
        final TabWidget tabWidget = new TabWidget(this);
        tabWidget.setId(android.R.id.tabs);
        inner.addView(tabWidget, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final FrameLayout tabContent = new FrameLayout(this);
        tabContent.setId(android.R.id.tabcontent);
        inner.addView(tabContent, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        tabs.addView(inner, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        tabs.setup();

        final View fetchTab = buildFetchTab();
        final View queueTab = buildQueueTab();

        tabs.addTab(tabs.newTabSpec("fetch").setIndicator("Fetch")
                .setContent(tag -> fetchTab));
        tabs.addTab(tabs.newTabSpec("queue").setIndicator("Downloads")
                .setContent(tag -> queueTab));

        return tabs;
    }

    private View buildFetchTab() {
        final int pad = dp(16);
        final int gap = dp(12);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        urlInput = new EditText(this);
        urlInput.setHint("youtube.com/watch?v=…");
        urlInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setMaxLines(2);
        root.addView(urlInput);

        final Button fetch = new Button(this);
        fetch.setText("Fetch");
        fetch.setOnClickListener(v -> fetch(urlInput.getText().toString().trim()));
        root.addView(fetch);

        status = new TextView(this);
        status.setText("Paste a YouTube link above, then tap Fetch.");
        status.setPadding(0, gap, 0, gap);
        root.addView(status);

        // Which audio track a merge pairs with is a guess the app shouldn't make silently —
        // YouTube's metadata about it isn't always there. Show the ranking's answer, let it
        // be overridden.
        audioSpinner = new Spinner(this);
        audioAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new ArrayList<>());
        audioAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        audioSpinner.setAdapter(audioAdapter);
        audioSpinner.setVisibility(View.GONE);
        root.addView(audioSpinner);

        final ListView list = new ListView(this);
        formatAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,
                new ArrayList<>());
        list.setAdapter(formatAdapter);
        list.setOnItemClickListener(
                (parent, view, position, id) -> enqueue(options.get(position)));
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    private View buildQueueTab() {
        final int pad = dp(16);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        final ListView list = new ListView(this);
        queueAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,
                new ArrayList<>());
        list.setAdapter(queueAdapter);
        // Tapping a live task cancels it; the worker notices between chunks.
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < queueSnapshot.size()) {
                final DownloadService.Task task = queueSnapshot.get(position);
                if (!task.state.finished()) {
                    DownloadService.cancel(task);
                    refreshQueue();
                }
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        final Button clear = new Button(this);
        clear.setText("Clear finished");
        clear.setOnClickListener(v -> {
            DownloadService.clearFinished();
            refreshQueue();
        });
        root.addView(clear);

        return root;
    }

    private int dp(final int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- queue tab

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
        queueAdapter.clear();

        if (queueSnapshot.isEmpty()) {
            queueAdapter.add("Nothing queued.\nPick a format on the Fetch tab.");
        } else {
            for (final DownloadService.Task task : queueSnapshot) {
                final StringBuilder line = new StringBuilder()
                        .append(task.title).append('\n')
                        .append(task.formatLabel).append(" · ").append(task.state.label);

                if (!task.state.finished()) {
                    line.append(' ').append(task.percent).append('%');
                }
                if (task.error != null) {
                    line.append(" — ").append(task.error);
                }
                queueAdapter.add(line.toString());
            }
        }
        queueAdapter.notifyDataSetChanged();
    }

    // ---------------------------------------------------------------- extraction

    private void fetch(final String url) {
        if (url.isEmpty()) {
            status.setText("Paste a YouTube link above, then tap Fetch.");
            return;
        }

        status.setText("Fetching…");
        formatAdapter.clear();
        options.clear();

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
                + (audioTracks.isEmpty() ? "" : "\n\nAudio for merges ↓")
                + "\n\nTap a format to queue it.");

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
                options.add(new Option(
                        vs.getResolution() + " · merge with audio",
                        vs.getContent(), true, "mp4", "video/mp4"));
            }
        }

        // Muxed: video and audio already in one file. Tops out at 360p on most videos,
        // occasionally 720p.
        for (final VideoStream vs : info.getVideoStreams()) {
            if (vs.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) {
                continue;
            }
            options.add(new Option(
                    vs.getResolution() + " · direct, no merge",
                    vs.getContent(), false, suffixOf(vs.getFormat(), "mp4"), "video/mp4"));
        }

        for (final AudioStream as : info.getAudioStreams()) {
            if (as.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) {
                continue;
            }
            options.add(new Option(
                    "Audio only · " + trackLabel(as) + " · " + nameOf(as.getFormat()),
                    as.getContent(), false, suffixOf(as.getFormat(), "m4a"), "audio/mp4"));
        }

        if (options.isEmpty()) {
            status.setText("No directly downloadable streams here. Live streams and some "
                    + "videos are served as manifests this app can't fetch.");
            return;
        }

        for (final Option o : options) {
            formatAdapter.add(o.label);
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
                option.extension, option.mimeType, option.label));

        Toast.makeText(this, "Queued", Toast.LENGTH_SHORT).show();
        refreshQueue();
    }

    // ---------------------------------------------------------------- helpers

    /** One entry in the format list. needsMerge means it has no audio of its own. */
    private static final class Option {
        final String label;
        final String videoUrl;
        final boolean needsMerge;
        final String extension;
        final String mimeType;

        Option(final String label, final String videoUrl, final boolean needsMerge,
               final String extension, final String mimeType) {
            this.label = label;
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

        return who + " (" + kind + ") · " + stream.getAverageBitrate() + " kbps";
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
