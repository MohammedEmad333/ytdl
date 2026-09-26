package com.example.ytdl;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.widget.ImageView;
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
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends Activity {
    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://\\S+");
    private static final int PLAYLIST_CAP = 200;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Option> options = new ArrayList<>();
    private final List<AudioStream> audioTracks = new ArrayList<>();
    private final List<Entry> playlist = new ArrayList<>();
    private List<DownloadService.Task> queueSnapshot = new ArrayList<>();

    private EditText urlInput;
    private TextView status;
    private ImageView preview;
    private Spinner audioSpinner;
    private Spinner searchSourceSpinner;
    private TextView fetchTabLabel;
    private TextView queueTabLabel;
    private View fetchPane;
    private View queuePane;
    private FormatAdapter formatAdapter;
    private QueueAdapter queueAdapter;
    private ArrayAdapter<String> audioAdapter;

    private String pageUrl = "";
    private String videoTitle = "media";
    private String currentArtist = "";
    private String currentCover = "";

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Net.ensureExtractor();
        DownloadService.ensureLoaded(this);
        setContentView(buildUi());
        selectTab(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        handleShare(getIntent());
    }

    @Override protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShare(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        main.post(queuePoll);
    }

    @Override protected void onPause() {
        super.onPause();
        main.removeCallbacks(queuePoll);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void handleShare(final Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        final String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null) return;
        final Matcher matcher = URL_IN_TEXT.matcher(text);
        if (!matcher.find()) return;
        final String url = matcher.group().replaceAll("[),.;]+$", "");
        urlInput.setText(url);
        selectTab(true);
        fetch(url);
    }

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

    private View buildHeader() {
        final TextView title = Ui.mono(this, 12, Ui.MUTED);
        title.setText("MEDIA DOWNLOADER");
        title.setLetterSpacing(0.22f);
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
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, .5f))));
        return wrap;
    }

    private TextView tabLabel(final String text, final boolean fetch) {
        final TextView t = Ui.mono(this, 13, Ui.MUTED);
        t.setText(text);
        t.setLetterSpacing(.12f);
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

    private final class SwipePager extends FrameLayout {
        private final GestureDetector detector;
        SwipePager(final Context context) {
            super(context);
            final float minVelocity = ViewConfiguration.get(context).getScaledMinimumFlingVelocity() * 1.5f;
            detector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onFling(final MotionEvent down, final MotionEvent up,
                                                 final float vx, final float vy) {
                    if (down == null || up == null || Math.abs(vx) < minVelocity
                            || Math.abs(vx) < Math.abs(vy) * 1.5f) return false;
                    selectTab(vx > 0);
                    return true;
                }
            });
        }
        @Override public boolean dispatchTouchEvent(final MotionEvent e) {
            detector.onTouchEvent(e);
            return super.dispatchTouchEvent(e);
        }
    }

    private View buildFetchPane() {
        final int pad = Ui.dp(this, 20);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, Ui.dp(this, 16), pad, 0);

        urlInput = new EditText(this);
        urlInput.setHint("Paste YouTube / Spotify / SoundCloud link, or search…");
        urlInput.setHintTextColor(Ui.MUTED);
        urlInput.setTextColor(Ui.TEXT);
        urlInput.setTextSize(Ui.size(14));
        urlInput.setTypeface(Typeface.MONOSPACE);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setMaxLines(2);
        urlInput.setBackground(Ui.box(this, Ui.SURFACE, Ui.LINE, 6));
        urlInput.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        root.addView(urlInput);

        searchSourceSpinner = new Spinner(this);
        final ArrayAdapter<String> searchSourceAdapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item,
                new String[]{"All sources", "YouTube", "SoundCloud"}) {
            @Override public View getView(final int position, final View convertView,
                                          final ViewGroup parent) {
                final TextView v = (TextView) super.getView(position, convertView, parent);
                v.setTextColor(Ui.TEXT);
                v.setTypeface(Typeface.MONOSPACE);
                v.setTextSize(Ui.size(12));
                return v;
            }
        };
        searchSourceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        searchSourceSpinner.setAdapter(searchSourceAdapter);
        searchSourceSpinner.setBackground(Ui.box(this, Ui.SURFACE, Ui.LINE, 6));
        final LinearLayout.LayoutParams sourceParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 42));
        sourceParams.topMargin = Ui.dp(this, 8);
        root.addView(searchSourceSpinner, sourceParams);

        final Button fetch = new Button(this);
        fetch.setText("FETCH / SEARCH");
        fetch.setTextColor(Ui.BG);
        fetch.setTextSize(Ui.size(14));
        fetch.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        fetch.setLetterSpacing(.1f);
        fetch.setAllCaps(false);
        fetch.setStateListAnimator(null);
        fetch.setBackground(Ui.pressable(this, Ui.ACCENT, Color.parseColor("#C8862A"), 6));
        fetch.setOnClickListener(v -> fetch(urlInput.getText().toString().trim()));
        final LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 44));
        fp.topMargin = Ui.dp(this, 10);
        root.addView(fetch, fp);

        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setVisibility(View.GONE);
        final LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 150));
        imageParams.topMargin = Ui.dp(this, 12);
        root.addView(preview, imageParams);

        status = Ui.sans(this, 14, Ui.MUTED);
        status.setText("Paste a link, share one to the app, or type a search.");
        status.setLineSpacing(Ui.dp(this, 3), 1f);
        status.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 4));
        root.addView(status);

        audioSpinner = new Spinner(this);
        audioAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item,
                new ArrayList<>()) {
            @Override public View getView(final int position, final View convertView, final ViewGroup parent) {
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
        list.setBackgroundColor(Color.TRANSPARENT);
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setOnItemClickListener((p, v, position, id) -> activate(options.get(position)));
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = Ui.dp(this, 12);
        root.addView(list, lp);
        return root;
    }

    private View buildQueuePane() {
        final int pad = Ui.dp(this, 20);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, Ui.dp(this, 12), pad, Ui.dp(this, 12));

        final LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(smallButton(this, "Pause all", Ui.ACCENT, v -> BatchControls.pauseAll(this)));
        actions.addView(smallButton(this, "Resume all", Ui.OK, v -> BatchControls.resumeAll(this)));
        actions.addView(smallButton(this, "Cancel all", Ui.MUTED, v -> BatchControls.cancelAll(this)));
        root.addView(actions);

        final Button retryFailed = new Button(this);
        retryFailed.setText("RETRY FAILED");
        retryFailed.setAllCaps(false);
        retryFailed.setTextColor(Ui.ERR);
        retryFailed.setTextSize(Ui.size(12));
        retryFailed.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        retryFailed.setStateListAnimator(null);
        retryFailed.setBackground(Ui.box(this, Color.TRANSPARENT, Ui.LINE, 6));
        retryFailed.setOnClickListener(v -> {
            final int count = BatchControls.retryFailed(this);
            Toast.makeText(this, count == 0 ? "No failed downloads"
                    : "Retrying " + count + (count == 1 ? " download" : " downloads"),
                    Toast.LENGTH_SHORT).show();
            refreshQueue();
        });
        final LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 38));
        retryParams.topMargin = Ui.dp(this, 8);
        retryParams.bottomMargin = Ui.dp(this, 8);
        root.addView(retryFailed, retryParams);

        final Button history = new Button(this);
        history.setText("HISTORY");
        history.setAllCaps(false);
        history.setTextColor(Ui.TEXT);
        history.setTextSize(Ui.size(12));
        history.setTypeface(Typeface.MONOSPACE);
        history.setStateListAnimator(null);
        history.setBackground(Ui.box(this, Color.TRANSPARENT, Ui.LINE, 6));
        history.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));
        final LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 38));
        hp.bottomMargin = Ui.dp(this, 10);
        root.addView(history, hp);

        final ListView list = new ListView(this);
        queueAdapter = new QueueAdapter();
        list.setAdapter(queueAdapter);
        list.setDivider(null);
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
        clear.setOnClickListener(v -> { DownloadService.clearFinished(this); refreshQueue(); });
        root.addView(clear, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 40)));
        return root;
    }

    private void fetch(final String input) {
        if (input.isEmpty()) {
            status.setText("Paste a link or type something to search.");
            return;
        }
        resetFetch();
        status.setText("Fetching…");
        executor.execute(() -> {
            try {
                if (Spotify.isSpotifyUrl(input)) {
                    final List<Spotify.Match> matches = Spotify.resolveAll(input);
                    if (matches.size() == 1) {
                        final Spotify.Match match = matches.get(0);
                        final StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, match.youtubeUrl);
                        main.post(() -> {
                            pageUrl = match.youtubeUrl;
                            showMedia(info, "Spotify → YouTube", match.spotifyTitle,
                                    match.artist, match.coverUrl);
                        });
                    } else {
                        final List<Entry> entries = new ArrayList<>();
                        for (final Spotify.Match match : matches) {
                            entries.add(new Entry(match.youtubeUrl, Streams.sanitize(match.spotifyTitle),
                                    match.artist, match.coverUrl));
                        }
                        main.post(() -> showCollection("Spotify collection", entries, true));
                    }
                    return;
                }

                if (!looksLikeUrl(input)) {
                    final List<MediaSearch.Result> results = new ArrayList<>();
                    final int source = searchSourceSpinner == null ? 0
                            : searchSourceSpinner.getSelectedItemPosition();
                    if (source == 0 || source == 1) {
                        results.addAll(MediaSearch.youtube(input));
                    }
                    if (source == 0 || source == 2) {
                        try {
                            results.addAll(MediaSearch.soundCloud(input));
                        } catch (final Exception e) {
                            if (source == 2) throw e;
                        }
                    }
                    main.post(() -> showSearch(results));
                    return;
                }

                final StreamingService service = serviceFor(input);
                if (service == ServiceList.YouTube && linkType(service, input) == StreamingService.LinkType.PLAYLIST) {
                    fetchPlaylist(input);
                    return;
                }
                final StreamInfo info = StreamInfo.getInfo(service, input);
                main.post(() -> { pageUrl = input; showMedia(info,
                        service == ServiceList.SoundCloud ? "SoundCloud" : "YouTube"); });
            } catch (final Exception e) {
                main.post(() -> status.setText("Couldn't read that input: " + safeMessage(e)));
            }
        });
    }

    private void resetFetch() {
        pageUrl = "";
        videoTitle = "media";
        currentArtist = "";
        currentCover = "";
        options.clear();
        playlist.clear();
        audioTracks.clear();
        audioAdapter.clear();
        audioSpinner.setVisibility(View.GONE);
        preview.setImageDrawable(null);
        preview.setVisibility(View.GONE);
        formatAdapter.notifyDataSetChanged();
    }

    private static boolean looksLikeUrl(final String value) {
        return value.startsWith("http://") || value.startsWith("https://");
    }

    private static StreamingService serviceFor(final String url) {
        try {
            if (ServiceList.SoundCloud.getLinkTypeByUrl(url) != StreamingService.LinkType.NONE) {
                return ServiceList.SoundCloud;
            }
        } catch (final Exception ignored) {}
        return ServiceList.YouTube;
    }

    private static StreamingService.LinkType linkType(final StreamingService service, final String url) {
        try { return service.getLinkTypeByUrl(url); }
        catch (final Exception e) { return StreamingService.LinkType.NONE; }
    }

    private void fetchPlaylist(final String url) throws Exception {
        final PlaylistInfo info = PlaylistInfo.getInfo(ServiceList.YouTube, url);
        final List<Entry> entries = new ArrayList<>();
        for (final StreamInfoItem item : info.getRelatedItems()) {
            entries.add(new Entry(item.getUrl(), Streams.sanitize(item.getName())));
        }
        Page next = info.getNextPage();
        while (next != null && entries.size() < PLAYLIST_CAP) {
            final ListExtractor.InfoItemsPage<StreamInfoItem> more =
                    PlaylistInfo.getMoreItems(ServiceList.YouTube, url, next);
            for (final StreamInfoItem item : more.getItems()) {
                if (entries.size() >= PLAYLIST_CAP) break;
                entries.add(new Entry(item.getUrl(), Streams.sanitize(item.getName())));
            }
            next = more.getNextPage();
        }
        main.post(() -> showCollection(info.getName(), entries, false));
    }

    private void showSearch(final List<MediaSearch.Result> results) {
        options.clear();
        playlist.clear();
        status.setText(results.isEmpty() ? "No search results." :
                "Search results · tap one to preview formats before downloading.");
        for (final MediaSearch.Result result : results) {
            final StringBuilder detail = new StringBuilder(result.source);
            if (!result.uploader.isEmpty()) detail.append(" · ").append(result.uploader);
            if (result.duration > 0) detail.append(" · ").append(formatDuration(result.duration));
            detail.append(" · tap to preview");
            options.add(Option.navigate(result.title, detail.toString(), result.url,
                    result.thumbnailUrl));
        }
        formatAdapter.notifyDataSetChanged();
    }

    private void showCollection(final String name, final List<Entry> entries, final boolean audioOnly) {
        options.clear();
        playlist.clear();
        playlist.addAll(entries);
        if (entries.isEmpty()) {
            status.setText("That collection came back empty.");
            formatAdapter.notifyDataSetChanged();
            return;
        }
        status.setText(name + "\n" + entries.size() + (audioOnly ? " tracks" : " videos")
                + "\nPick a format to queue the whole collection.");
        if (!audioOnly) {
            for (final int height : new int[]{2160, 1080, 720, 480, 360}) {
                options.add(new Option(height + "p", "merge with audio · queue " + entries.size(),
                        DownloadService.Spec.merge(height, null, height + "p"), null, null,
                        "mp4", "video/mp4", null, false));
            }
        }
        options.add(new Option("Audio", "best available · queue " + entries.size(),
                DownloadService.Spec.audio(null, "Audio"), null, null,
                "m4a", "audio/mp4", null, false));
        for (final int bitrate : new int[]{320, 192, 128}) {
            options.add(new Option("MP3 " + bitrate,
                    "encode MP3 · queue " + entries.size()
                            + (audioOnly ? " · preserve Spotify metadata" : ""),
                    null, null, null, "mp3", "audio/mpeg", null, true, bitrate));
        }
        formatAdapter.notifyDataSetChanged();
    }

    private void showMedia(final StreamInfo info, final String source) {
        showMedia(info, source, "", "", "");
    }

    private void showMedia(final StreamInfo info, final String source, final String titleOverride,
                           final String artistOverride, final String coverOverride) {
        options.clear();
        playlist.clear();
        audioTracks.clear();
        audioAdapter.clear();
        final String sourceTitle = titleOverride == null || titleOverride.trim().isEmpty()
                ? info.getName() : titleOverride;
        videoTitle = Streams.sanitize(sourceTitle);
        currentArtist = artistOverride == null || artistOverride.trim().isEmpty()
                ? (info.getUploaderName() == null ? "" : info.getUploaderName()) : artistOverride;
        currentCover = coverOverride == null || coverOverride.trim().isEmpty()
                ? firstThumbnail(info) : coverOverride;
        loadThumbnail(currentCover);

        audioTracks.addAll(Streams.mergeAudio(info));
        for (final AudioStream a : audioTracks) audioAdapter.add(Streams.trackLabel(a));
        audioAdapter.notifyDataSetChanged();
        audioSpinner.setVisibility(audioTracks.isEmpty() ? View.GONE : View.VISIBLE);
        if (!audioTracks.isEmpty()) audioSpinner.setSelection(0);

        final long duration = info.getDuration();
        final StringBuilder header = new StringBuilder(source).append("\n")
                .append(sourceTitle);
        if (!currentArtist.isEmpty()) header.append("\n").append(currentArtist);
        if (duration > 0) header.append("\n").append(formatDuration(duration));
        status.setText(header.toString());

        final List<AudioStream> allAudio = Streams.allAudio(info);
        if (!allAudio.isEmpty()) {
            final AudioStream best = allAudio.get(0);
            for (final int bitrate : new int[]{320, 192, 128}) {
                options.add(new Option("MP3 " + bitrate,
                        "metadata + cover art · Music/YTDL",
                        null, best.getContent(), null, "mp3", "audio/mpeg", null, true, bitrate));
            }
        }

        final long mergeAudioBytes = audioTracks.isEmpty() ? -1 : Streams.sizeOf(audioTracks.get(0));
        if (!audioTracks.isEmpty()) {
            for (final VideoStream v : Streams.videoOnly(info)) {
                final long vb = Streams.sizeOf(v);
                final long combined = vb < 0 || mergeAudioBytes < 0 ? -1 : vb + mergeAudioBytes;
                options.add(new Option(v.getResolution(),
                        Streams.formatName(v.getFormat()) + " · merge with audio" + size(combined),
                        DownloadService.Spec.merge(Streams.heightOf(v.getResolution()), null, v.getResolution()),
                        v.getContent(), null, "mp4", "video/mp4", null, false));
            }
        }
        for (final VideoStream v : Streams.muxed(info)) {
            options.add(new Option(v.getResolution(),
                    Streams.formatName(v.getFormat()) + " · direct" + size(Streams.sizeOf(v)),
                    DownloadService.Spec.muxed(Streams.heightOf(v.getResolution()), v.getResolution()),
                    v.getContent(), null, Streams.suffix(v.getFormat(), "mp4"), "video/mp4", null, false));
        }
        for (final AudioStream a : allAudio) {
            options.add(new Option("Audio", Streams.trackLabel(a) + " · "
                    + Streams.formatName(a.getFormat()) + size(Streams.sizeOf(a)),
                    DownloadService.Spec.audio(a.getAudioTrackId(), "Audio"),
                    a.getContent(), null, Streams.suffix(a.getFormat(), "m4a"),
                    a.getFormat() == null ? "audio/*" : a.getFormat().getMimeType(), null, false));
        }
        for (final SubtitlesStream sub : Streams.subtitles(info)) {
            options.add(new Option("Subtitles", Streams.subtitleLabel(sub) + " · "
                    + Streams.formatName(sub.getFormat()),
                    DownloadService.Spec.subtitle(sub.getLanguageTag(), "Subtitles " + sub.getLanguageTag()),
                    sub.getContent(), null, sub.getLanguageTag() + "."
                    + Streams.suffix(sub.getFormat(), "ttml"),
                    sub.getFormat() == null ? "text/plain" : sub.getFormat().getMimeType(), null, false));
        }
        if (options.isEmpty()) status.append("\nNo directly downloadable streams found.");
        formatAdapter.notifyDataSetChanged();
    }

    private static String firstThumbnail(final StreamInfo info) {
        try {
            if (info.getThumbnails() != null && !info.getThumbnails().isEmpty()) {
                return info.getThumbnails().get(0).getUrl();
            }
        } catch (final Exception ignored) {}
        return "";
    }

    private void loadThumbnail(final String url) {
        if (url == null || url.isEmpty()) {
            preview.setVisibility(View.GONE);
            return;
        }
        executor.execute(() -> {
            try (Response response = Net.HTTP.newCall(new Request.Builder()
                    .url(url).header("User-Agent", Net.USER_AGENT).build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) return;
                try (InputStream in = response.body().byteStream()) {
                    final Bitmap bitmap = BitmapFactory.decodeStream(in);
                    if (bitmap != null) main.post(() -> {
                        preview.setImageBitmap(bitmap);
                        preview.setVisibility(View.VISIBLE);
                    });
                }
            } catch (final Exception ignored) {}
        });
    }

    private void loadThumbnailInto(final String url, final ImageView target) {
        if (url == null || url.isEmpty() || target == null) return;
        target.setTag(url);
        executor.execute(() -> {
            try (Response response = Net.HTTP.newCall(new Request.Builder()
                    .url(url).header("User-Agent", Net.USER_AGENT).build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) return;
                try (InputStream in = response.body().byteStream()) {
                    final Bitmap bitmap = BitmapFactory.decodeStream(in);
                    if (bitmap != null) main.post(() -> {
                        if (url.equals(target.getTag())) target.setImageBitmap(bitmap);
                    });
                }
            } catch (final Exception ignored) {}
        });
    }

    private void activate(final Option option) {
        if (option.navigateUrl != null) {
            urlInput.setText(option.navigateUrl);
            fetch(option.navigateUrl);
            return;
        }
        if (option.mp3) {
            enqueueMp3(option);
            return;
        }
        enqueue(option);
    }

    private void enqueueMp3(final Option option) {
        final DownloadService.Spec spec = DownloadService.Spec.mp3(option.mp3Kbps);
        if (!playlist.isEmpty()) {
            int added = 0;
            for (final Entry entry : playlist) {
                final DownloadService.Task task = new DownloadService.Task(
                        entry.url, entry.title, spec).metadata(entry.artist, entry.coverUrl);
                if (DownloadService.enqueue(this, task)) added++;
            }
            Toast.makeText(this, added == 0 ? "All MP3s are already in queue"
                    : "Queued " + added + " MP3" + (added == 1 ? "" : "s"),
                    Toast.LENGTH_SHORT).show();
            refreshQueue();
            selectTab(false);
            return;
        }

        final DownloadService.Task task = new DownloadService.Task(
                pageUrl, videoTitle, spec).metadata(currentArtist, currentCover);
        task.preResolve(option.videoUrl, null, "mp3", "audio/mpeg");
        final boolean added = DownloadService.enqueue(this, task);
        Toast.makeText(this, added ? "MP3 queued" : "MP3 already in queue",
                Toast.LENGTH_SHORT).show();
        refreshQueue();
        selectTab(false);
    }

    private void enqueue(final Option option) {
        if (!playlist.isEmpty()) {
            int added = 0;
            for (final Entry entry : playlist) {
                if (DownloadService.enqueue(this,
                        new DownloadService.Task(entry.url, entry.title, option.spec))) {
                    added++;
                }
            }
            Toast.makeText(this, added == 0 ? "All items are already in queue"
                    : "Queued " + added + (added < playlist.size()
                    ? " · skipped " + (playlist.size() - added) + " duplicates" : ""),
                    Toast.LENGTH_SHORT).show();
            refreshQueue();
            selectTab(false);
            return;
        }
        String audioUrl = option.audioUrl;
        DownloadService.Spec spec = option.spec;
        if (spec != null && spec.kind == DownloadService.Kind.MERGE) {
            final int selected = audioSpinner.getSelectedItemPosition();
            if (selected < 0 || selected >= audioTracks.size()) {
                status.setText("Pick an audio track first.");
                return;
            }
            final AudioStream track = audioTracks.get(selected);
            audioUrl = track.getContent();
            spec = DownloadService.Spec.merge(spec.height, track.getAudioTrackId(), spec.label);
        }
        final DownloadService.Task task = new DownloadService.Task(pageUrl, videoTitle, spec);
        task.preResolve(option.videoUrl, audioUrl, option.extension, option.mimeType);
        final boolean added = DownloadService.enqueue(this, task);
        Toast.makeText(this, added ? "Queued" : "Already in queue",
                Toast.LENGTH_SHORT).show();
        refreshQueue();
        selectTab(false);
    }

    private final class FormatAdapter extends BaseAdapter {
        @Override public int getCount() { return options.size(); }
        @Override public Object getItem(final int p) { return options.get(p); }
        @Override public long getItemId(final int p) { return p; }
        @Override public View getView(final int position, final View convertView, final ViewGroup parent) {
            final LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Ui.pressable(MainActivity.this, Ui.SURFACE, Ui.LINE, 6));
            row.setPadding(Ui.dp(MainActivity.this, 12), Ui.dp(MainActivity.this, 10),
                    Ui.dp(MainActivity.this, 12), Ui.dp(MainActivity.this, 10));
            final Option o = options.get(position);
            if (o.thumbnailUrl != null && !o.thumbnailUrl.isEmpty()) {
                final ImageView thumb = new ImageView(MainActivity.this);
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                final LinearLayout.LayoutParams thumbParams = new LinearLayout.LayoutParams(
                        Ui.dp(MainActivity.this, 96), Ui.dp(MainActivity.this, 54));
                thumbParams.rightMargin = Ui.dp(MainActivity.this, 12);
                row.addView(thumb, thumbParams);
                loadThumbnailInto(o.thumbnailUrl, thumb);
            }
            final LinearLayout text = new LinearLayout(MainActivity.this);
            text.setOrientation(LinearLayout.VERTICAL);
            final TextView primary = Ui.mono(MainActivity.this, 15, Ui.TEXT);
            primary.setText(o.primary);
            primary.setMaxLines(2);
            primary.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            text.addView(primary);
            final TextView detail = Ui.mono(MainActivity.this, 12, Ui.MUTED);
            detail.setText(o.detail);
            detail.setMaxLines(2);
            detail.setPadding(0, Ui.dp(MainActivity.this, 3), 0, 0);
            text.addView(detail);
            row.addView(text, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            final LinearLayout wrap = new LinearLayout(MainActivity.this);
            wrap.setPadding(0, 0, 0, Ui.dp(MainActivity.this, 8));
            wrap.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            return wrap;
        }
    }

    private final class QueueAdapter extends BaseAdapter {
        @Override public int getCount() { return Math.max(1, queueSnapshot.size()); }
        @Override public Object getItem(final int p) { return p < queueSnapshot.size() ? queueSnapshot.get(p) : null; }
        @Override public long getItemId(final int p) { return p; }
        @Override public View getView(final int position, final View convertView, final ViewGroup parent) {
            if (queueSnapshot.isEmpty()) {
                final TextView empty = Ui.sans(MainActivity.this, 14, Ui.MUTED);
                empty.setText("Nothing queued yet.");
                empty.setPadding(Ui.dp(MainActivity.this, 4), Ui.dp(MainActivity.this, 24), 0, 0);
                return empty;
            }
            final DownloadService.Task task = queueSnapshot.get(position);
            final LinearLayout card = new LinearLayout(MainActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Ui.box(MainActivity.this, Ui.SURFACE, Ui.LINE, 6));
            card.setPadding(Ui.dp(MainActivity.this, 14), Ui.dp(MainActivity.this, 12),
                    Ui.dp(MainActivity.this, 14), Ui.dp(MainActivity.this, 12));
            final TextView title = Ui.sans(MainActivity.this, 14, Ui.TEXT);
            title.setText(task.title);
            title.setMaxLines(1);
            card.addView(title);
            final TextView meta = Ui.mono(MainActivity.this, 12, stateColor(task.state));
            meta.setText(metaLine(task));
            meta.setPadding(0, Ui.dp(MainActivity.this, 4), 0, Ui.dp(MainActivity.this, 8));
            card.addView(meta);
            card.addView(progressRule(task));
            if (!task.state.finished()) card.addView(controls(task));
            else if (task.state == DownloadService.State.FAILED) card.addView(retryControls(task));
            else if (task.state == DownloadService.State.DONE && task.outputUri != null) {
                card.addView(doneControls(task));
            }
            final LinearLayout wrap = new LinearLayout(MainActivity.this);
            wrap.setPadding(0, 0, 0, Ui.dp(MainActivity.this, 8));
            wrap.addView(card, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            return wrap;
        }
    }

    private View progressRule(final DownloadService.Task task) {
        final int percent = task.state == DownloadService.State.DONE ? 100 : task.percent();
        final LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setWeightSum(100f);
        final View filled = new View(this);
        filled.setBackgroundColor(stateColor(task.state));
        bar.addView(filled, new LinearLayout.LayoutParams(0, Math.max(2, Ui.dp(this, 2)), percent));
        final View rest = new View(this);
        rest.setBackgroundColor(Ui.LINE);
        bar.addView(rest, new LinearLayout.LayoutParams(0, Math.max(2, Ui.dp(this, 2)), 100 - percent));
        return bar;
    }

    private View controls(final DownloadService.Task task) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Ui.dp(this, 10), 0, 0);
        final boolean paused = task.state == DownloadService.State.PAUSED;
        if (task.state == DownloadService.State.QUEUED || paused) {
            row.addView(smallButton(this, "Up", Ui.MUTED, v -> {
                DownloadService.moveUp(this, task); refreshQueue();
            }));
            row.addView(smallButton(this, "Down", Ui.MUTED, v -> {
                DownloadService.moveDown(this, task); refreshQueue();
            }));
        }
        row.addView(smallButton(this, paused ? "Resume" : "Pause", Ui.ACCENT, v -> {
            if (paused) DownloadService.resume(this, task); else DownloadService.pause(this, task);
            refreshQueue();
        }));
        row.addView(smallButton(this, "Cancel", Ui.MUTED, v -> {
            DownloadService.cancel(this, task); refreshQueue();
        }));
        return row;
    }

    private View retryControls(final DownloadService.Task task) {
        final LinearLayout row = new LinearLayout(this);
        row.setPadding(0, Ui.dp(this, 10), 0, 0);
        row.addView(smallButton(this, "Retry", Ui.ACCENT, v -> {
            DownloadService.retry(this, task); refreshQueue();
        }));
        return row;
    }

    private View doneControls(final DownloadService.Task task) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Ui.dp(this, 10), 0, 0);
        row.addView(smallButton(this, "Open", Ui.OK, v -> openSaved(task)));
        row.addView(smallButton(this, "Share", Ui.ACCENT, v -> shareSaved(task)));
        return row;
    }

    private void openSaved(final DownloadService.Task task) {
        try {
            final android.net.Uri uri = android.net.Uri.parse(task.outputUri);
            final Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, task.mimeType)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (final Exception e) {
            Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareSaved(final DownloadService.Task task) {
        try {
            final android.net.Uri uri = android.net.Uri.parse(task.outputUri);
            final Intent intent = new Intent(Intent.ACTION_SEND)
                    .setType(task.mimeType)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Share file"));
        } catch (final Exception e) {
            Toast.makeText(this, "Couldn't share this file", Toast.LENGTH_SHORT).show();
        }
    }

    private View smallButton(final Context c, final String text, final int color,
                             final View.OnClickListener click) {
        final TextView b = Ui.mono(c, 11, color);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Ui.dp(c, 10), Ui.dp(c, 7), Ui.dp(c, 10), Ui.dp(c, 7));
        b.setBackground(Ui.box(c, Color.TRANSPARENT, Ui.LINE, 4));
        b.setOnClickListener(click);
        final LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.rightMargin = Ui.dp(c, 6);
        b.setLayoutParams(p);
        return b;
    }

    private final Runnable queuePoll = new Runnable() {
        @Override public void run() {
            refreshQueue();
            main.postDelayed(this, 500);
        }
    };

    private void refreshQueue() {
        queueSnapshot = DownloadService.snapshot();
        int remaining = 0;
        for (final DownloadService.Task task : queueSnapshot) {
            if (!task.state.finished()) remaining++;
        }
        if (queueTabLabel != null) {
            queueTabLabel.setText(remaining > 0 ? "DOWNLOADS (" + remaining + ")" : "DOWNLOADS");
        }
        if (queueAdapter != null) queueAdapter.notifyDataSetChanged();
    }

    private static String metaLine(final DownloadService.Task task) {
        final StringBuilder s = new StringBuilder(task.formatLabel()).append("  ")
                .append(task.state.label.toUpperCase(Locale.US));
        if (task.state.transferring() && task.total > 0) {
            s.append("  ").append(Ui.bytes(task.done)).append(" / ").append(Ui.bytes(task.total));
            if (task.bytesPerSecond > 0) {
                s.append("  ").append(Ui.bytes(task.bytesPerSecond)).append("/s");
                if (task.done < task.total) {
                    final long eta = (task.total - task.done + task.bytesPerSecond - 1)
                            / task.bytesPerSecond;
                    s.append("  ETA ").append(formatDuration(eta));
                }
            }
        }
        if (task.error != null) s.append("  ").append(task.error);
        return s.toString();
    }

    private static int stateColor(final DownloadService.State state) {
        if (state == DownloadService.State.DONE) return Ui.OK;
        if (state == DownloadService.State.FAILED) return Ui.ERR;
        if (state.active()) return Ui.ACCENT;
        return Ui.MUTED;
    }

    private static String size(final long bytes) { return bytes < 0 ? "" : " · " + Ui.bytes(bytes); }

    private static String formatDuration(final long seconds) {
        final long h = seconds / 3600;
        final long m = (seconds % 3600) / 60;
        final long s = seconds % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.US, "%d:%02d", m, s);
    }

    private static String safeMessage(final Exception e) {
        return e.getMessage() == null || e.getMessage().trim().isEmpty()
                ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static final class Entry {
        final String url;
        final String title;
        final String artist;
        final String coverUrl;

        Entry(final String url, final String title) {
            this(url, title, "", "");
        }

        Entry(final String url, final String title, final String artist, final String coverUrl) {
            this.url = url;
            this.title = title;
            this.artist = artist == null ? "" : artist;
            this.coverUrl = coverUrl == null ? "" : coverUrl;
        }
    }

    private static final class Option {
        final String primary;
        final String detail;
        final DownloadService.Spec spec;
        final String videoUrl;
        final String audioUrl;
        final String extension;
        final String mimeType;
        final String navigateUrl;
        final boolean mp3;
        final int mp3Kbps;
        final String thumbnailUrl;

        Option(final String primary, final String detail, final DownloadService.Spec spec,
               final String videoUrl, final String audioUrl, final String extension,
               final String mimeType, final String navigateUrl, final boolean mp3) {
            this(primary, detail, spec, videoUrl, audioUrl, extension, mimeType,
                    navigateUrl, mp3, mp3 ? 192 : 0, "");
        }

        Option(final String primary, final String detail, final DownloadService.Spec spec,
               final String videoUrl, final String audioUrl, final String extension,
               final String mimeType, final String navigateUrl, final boolean mp3,
               final int mp3Kbps) {
            this(primary, detail, spec, videoUrl, audioUrl, extension, mimeType,
                    navigateUrl, mp3, mp3Kbps, "");
        }

        Option(final String primary, final String detail, final DownloadService.Spec spec,
               final String videoUrl, final String audioUrl, final String extension,
               final String mimeType, final String navigateUrl, final boolean mp3,
               final int mp3Kbps, final String thumbnailUrl) {
            this.primary = primary;
            this.detail = detail;
            this.spec = spec;
            this.videoUrl = videoUrl;
            this.audioUrl = audioUrl;
            this.extension = extension;
            this.mimeType = mimeType;
            this.navigateUrl = navigateUrl;
            this.mp3 = mp3;
            this.mp3Kbps = mp3Kbps;
            this.thumbnailUrl = thumbnailUrl == null ? "" : thumbnailUrl;
        }

        static Option navigate(final String title, final String detail, final String url,
                               final String thumbnailUrl) {
            return new Option(title, detail, null, null, null, "", "", url,
                    false, 0, thumbnailUrl);
        }
    }
}
