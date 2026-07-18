package com.example.ytdl;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

/**
 * The whole app in one file — the Downloader, the muxer and the UI are folded in here
 * because every extra file is another thing to hand-create in a browser.
 *
 * Note the import of android.media.MediaFormat. NewPipe has a MediaFormat class too, so
 * that one is spelled out in full at every use site. Importing both is a compile error.
 */
public class MainActivity extends Activity {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    /**
     * YouTube throttles a single long-lived GET on a stream URL to about playback speed —
     * the point is to stop a player buffering the whole video, and a downloader eats the
     * same limit. Each new ranged request gets a fresh budget, so the fix is to never ask
     * for very much at once. Smaller means more resets but more request overhead; 4 MB is
     * a reasonable middle. Tune it if you're curious.
     */
    private static final long CHUNK_BYTES = 4L * 1024 * 1024;

    private static boolean extractorReady = false;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Option> options = new ArrayList<>();
    /** Merge-eligible audio tracks, best-ranked first. Index matches the spinner. */
    private final List<AudioStream> audioTracks = new ArrayList<>();

    private EditText urlInput;
    private TextView status;
    private Spinner audioSpinner;
    private ArrayAdapter<String> adapter;
    private ArrayAdapter<String> audioAdapter;
    private String videoTitle = "video";

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!extractorReady) {
            NewPipe.init(new OkHttpDownloader(), new Localization("en", "US"));
            extractorReady = true;
        }

        setContentView(buildUi());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    // ---------------------------------------------------------------- ui

    private LinearLayout buildUi() {
        final float density = getResources().getDisplayMetrics().density;
        final int pad = (int) (16 * density);
        final int gap = (int) (12 * density);

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

        // Which audio track a merge pairs with is a guess the app shouldn't be making
        // silently — YouTube's own metadata about it isn't always there. Show the ranking's
        // answer, let it be overridden.
        audioSpinner = new Spinner(this);
        audioAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new ArrayList<>());
        audioAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        audioSpinner.setAdapter(audioAdapter);
        audioSpinner.setVisibility(View.GONE);
        root.addView(audioSpinner);

        final ListView list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,
                new ArrayList<>());
        list.setAdapter(adapter);
        list.setOnItemClickListener(
                (parent, view, position, id) -> download(options.get(position)));
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    private void say(final String message) {
        main.post(() -> status.setText(message));
    }

    // ---------------------------------------------------------------- extraction

    private void fetch(final String url) {
        if (url.isEmpty()) {
            status.setText("Paste a YouTube link above, then tap Fetch.");
            return;
        }

        status.setText("Fetching…");
        adapter.clear();
        options.clear();

        executor.execute(() -> {
            try {
                final StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, url);
                main.post(() -> show(info));
            } catch (final Exception e) {
                say("Couldn't read that video: " + e.getMessage());
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
                + "\n\nTap a format to save it.");

        // High resolutions live here: video with no audio track at all. Each gets merged
        // with whichever track is selected in the spinner at download time.
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
                        vs.getContent(),
                        true,
                        "mp4"));
            }
        }

        // Muxed: video and audio already in one file. Tops out at 360p on most videos,
        // occasionally 720p. No merge step, so it goes straight to DownloadManager.
        for (final VideoStream vs : info.getVideoStreams()) {
            if (vs.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) {
                continue;
            }
            options.add(new Option(
                    vs.getResolution() + " · direct, no merge",
                    vs.getContent(),
                    false,
                    suffixOf(vs.getFormat(), "mp4")));
        }

        for (final AudioStream as : info.getAudioStreams()) {
            if (as.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) {
                continue;
            }
            options.add(new Option(
                    "Audio only · " + trackLabel(as) + " · " + nameOf(as.getFormat()),
                    as.getContent(),
                    false,
                    suffixOf(as.getFormat(), "m4a")));
        }

        if (options.isEmpty()) {
            status.setText("No directly downloadable streams here. Live streams and some "
                    + "videos are served as manifests this app can't fetch.");
            return;
        }

        for (final Option o : options) {
            adapter.add(o.label);
        }
        adapter.notifyDataSetChanged();
    }

    /**
     * Bitrate alone isn't enough. YouTube ships dubbed audio tracks next to the original,
     * often at identical bitrates, so ranking on kbps would pick whichever happened to come
     * first — a coin flip between English and a dub. The original wins outright here;
     * bitrate only breaks ties. If YouTube didn't tag the tracks, everything scores equal
     * and the spinner is the only thing standing between you and a random language.
     */
    private static long rank(final AudioStream stream) {
        final AudioTrackType type = stream.getAudioTrackType();
        final long originalBonus = type == AudioTrackType.ORIGINAL ? 1_000_000L : 0L;
        return originalBonus + Math.max(0, stream.getAverageBitrate());
    }

    /**
     * Deliberately spells out the track type. If every entry reads "untagged", YouTube sent
     * no track metadata, rank() had nothing to work with, and the choice is genuinely yours
     * to make — that's worth being able to see rather than infer from a wrong download.
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

    // ---------------------------------------------------------------- download

    private void download(final Option option) {
        if (!option.needsMerge) {
            downloadDirect(option);
            return;
        }

        // Read the spinner here rather than baking a URL into the Option at fetch time,
        // so changing the track after fetching actually changes what gets downloaded.
        final int selected = audioSpinner.getSelectedItemPosition();
        if (selected < 0 || selected >= audioTracks.size()) {
            status.setText("Pick an audio track first.");
            return;
        }
        final AudioStream track = audioTracks.get(selected);
        executor.execute(() -> downloadAndMerge(option, track));
    }

    /** Single file, nothing to combine — hand it to the system and forget about it. */
    private void downloadDirect(final Option option) {
        final DownloadManager.Request request =
                new DownloadManager.Request(Uri.parse(option.videoUrl));

        request.addRequestHeader("User-Agent", USER_AGENT);
        request.setTitle(videoTitle);
        request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS, videoTitle + "." + option.extension);

        final DownloadManager dm =
                (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        dm.enqueue(request);

        Toast.makeText(this, "Saving to Downloads", Toast.LENGTH_SHORT).show();
    }

    /**
     * Two downloads and a mux. This runs on the activity's executor rather than in a
     * foreground service, which means backgrounding the app kills it. Fine for tap-and-wait;
     * if that starts annoying you, a foreground service is the fix.
     */
    private void downloadAndMerge(final Option option, final AudioStream track) {
        final File video = new File(getCacheDir(), "video.part");
        final File audio = new File(getCacheDir(), "audio.part");
        final File merged = new File(getCacheDir(), "merged.mp4");

        try {
            fetchToFile(option.videoUrl, video, "Video");
            fetchToFile(track.getContent(), audio, "Audio");

            say("Merging…");
            mux(video, audio, merged);

            say("Saving…");
            saveToDownloads(merged, videoTitle + ".mp4");

            say("Saved " + videoTitle + ".mp4 to Downloads.");
            main.post(() -> Toast.makeText(this, "Done", Toast.LENGTH_SHORT).show());
        } catch (final Exception e) {
            say("Failed: " + e.getMessage());
        } finally {
            video.delete();
            audio.delete();
            merged.delete();
        }
    }

    private void fetchToFile(final String url, final File dest, final String label)
            throws IOException {

        long total = -1;
        long written = 0;
        int lastShown = -1;

        try (OutputStream out = new FileOutputStream(dest)) {
            while (total < 0 || written < total) {
                final long chunkStart = written;

                final okhttp3.Request request = new okhttp3.Request.Builder()
                        .url(url)
                        .addHeader("User-Agent", USER_AGENT)
                        .addHeader("Range", "bytes=" + chunkStart + "-"
                                + (chunkStart + CHUNK_BYTES - 1))
                        .build();

                try (okhttp3.Response response = HTTP.newCall(request).execute()) {
                    final int code = response.code();
                    if (code != 200 && code != 206) {
                        throw new IOException(label + " download returned HTTP " + code);
                    }
                    final ResponseBody body = response.body();
                    if (body == null) {
                        throw new IOException(label + " download returned an empty body");
                    }

                    if (total < 0) {
                        total = totalLength(response.header("Content-Range"),
                                body.contentLength());
                    }

                    try (InputStream in = body.byteStream()) {
                        final byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = in.read(buffer)) > 0) {
                            out.write(buffer, 0, read);
                            written += read;

                            if (total > 0) {
                                final int percent = (int) (written * 100 / total);
                                // Throttled: setText per 64KB chunk would flood the main thread.
                                if (percent != lastShown && percent % 2 == 0) {
                                    lastShown = percent;
                                    say(label + " " + percent + "%");
                                }
                            }
                        }
                    }

                    // 200 rather than 206 means the server ignored the Range header and
                    // sent the lot — we already have everything.
                    if (code == 200) {
                        break;
                    }
                }

                // Without this, a chunk that yields nothing loops forever.
                if (written == chunkStart) {
                    throw new IOException(label + " download stalled at " + written + " bytes");
                }
            }
        }
    }

    /** Content-Range comes back as "bytes 0-4194303/52428800" — the tail is what we want. */
    private static long totalLength(final String contentRange, final long bodyLength) {
        if (contentRange != null) {
            final int slash = contentRange.indexOf('/');
            if (slash >= 0) {
                try {
                    return Long.parseLong(contentRange.substring(slash + 1).trim());
                } catch (final NumberFormatException ignored) {
                    // Unparseable — fall back to the body length below.
                }
            }
        }
        return bodyLength;
    }

    // ---------------------------------------------------------------- muxing

    /**
     * Copies the compressed video and audio samples into one MP4. No decoding, no
     * re-encoding — the bytes are lifted straight across, so a 1080p merge takes a second
     * or two and loses nothing. This is why ffmpeg isn't needed.
     */
    private static void mux(final File videoFile, final File audioFile, final File output)
            throws IOException {

        final MediaExtractor videoExtractor = new MediaExtractor();
        final MediaExtractor audioExtractor = new MediaExtractor();
        MediaMuxer muxer = null;

        try {
            videoExtractor.setDataSource(videoFile.getAbsolutePath());
            audioExtractor.setDataSource(audioFile.getAbsolutePath());

            final int videoTrack = firstTrack(videoExtractor, "video/");
            final int audioTrack = firstTrack(audioExtractor, "audio/");
            if (videoTrack < 0 || audioTrack < 0) {
                throw new IOException("Couldn't find both a video and an audio track");
            }

            videoExtractor.selectTrack(videoTrack);
            audioExtractor.selectTrack(audioTrack);

            final MediaFormat videoFormat = videoExtractor.getTrackFormat(videoTrack);
            final MediaFormat audioFormat = audioExtractor.getTrackFormat(audioTrack);

            muxer = new MediaMuxer(output.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            // Throws if the codec can't live in an MP4 — surface that rather than writing
            // a broken file.
            final int outVideo = muxer.addTrack(videoFormat);
            final int outAudio = muxer.addTrack(audioFormat);

            if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                muxer.setOrientationHint(videoFormat.getInteger(MediaFormat.KEY_ROTATION));
            }

            muxer.start();

            final ByteBuffer buffer = ByteBuffer.allocate(
                    Math.max(bufferSizeFor(videoFormat), bufferSizeFor(audioFormat)));
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            copyTrack(videoExtractor, muxer, outVideo, buffer, info);
            copyTrack(audioExtractor, muxer, outAudio, buffer, info);

            muxer.stop();
        } finally {
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (final Exception ignored) {
                    // release() after a failed stop() throws; the real error matters more.
                }
            }
            videoExtractor.release();
            audioExtractor.release();
        }
    }

    private static void copyTrack(final MediaExtractor extractor, final MediaMuxer muxer,
                                  final int trackIndex, final ByteBuffer buffer,
                                  final MediaCodec.BufferInfo info) {
        while (true) {
            final int size = extractor.readSampleData(buffer, 0);
            if (size < 0) {
                break;
            }
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = extractor.getSampleTime();
            // MediaExtractor and MediaCodec use different flag constants for the same idea,
            // so translate rather than passing the raw value through.
            info.flags = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;

            muxer.writeSampleData(trackIndex, buffer, info);
            extractor.advance();
        }
    }

    private static int firstTrack(final MediaExtractor extractor, final String prefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            final String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    /** readSampleData throws if the buffer can't hold a whole sample — 4K keyframes are big. */
    private static int bufferSizeFor(final MediaFormat format) {
        final int floor = 1024 * 1024;
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            return Math.max(floor, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
        }
        return floor;
    }

    // ---------------------------------------------------------------- saving

    /**
     * DownloadManager isn't involved in the merged path, so the finished file has to be
     * published by hand. IS_PENDING hides it from the gallery until the copy is complete.
     */
    private void saveToDownloads(final File source, final String displayName)
            throws IOException {

        final ContentResolver resolver = getContentResolver();
        final ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
        values.put(MediaStore.Downloads.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        final Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("MediaStore wouldn't accept the file");
        }

        try (InputStream in = new FileInputStream(source);
             OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) {
                throw new IOException("Couldn't open the destination for writing");
            }
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }

        values.clear();
        values.put(MediaStore.Downloads.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
    }

    // ---------------------------------------------------------------- helpers

    /** One entry in the format list. needsMerge means it has no audio of its own. */
    private static final class Option {
        final String label;
        final String videoUrl;
        final boolean needsMerge;
        final String extension;

        Option(final String label, final String videoUrl, final boolean needsMerge,
               final String extension) {
            this.label = label;
            this.videoUrl = videoUrl;
            this.needsMerge = needsMerge;
            this.extension = extension;
        }
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

    private static String sanitize(final String name) {
        final String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (cleaned.isEmpty()) {
            return "video";
        }
        return cleaned.length() > 100 ? cleaned.substring(0, 100) : cleaned;
    }

    // ---------------------------------------------------------------- downloader

    /**
     * NewPipeExtractor never makes a network call itself — Downloader is abstract and you
     * supply the transport. Nothing works until this is registered via NewPipe.init.
     */
    private static final class OkHttpDownloader extends Downloader {

        @Override
        public Response execute(final Request request) throws IOException, ReCaptchaException {
            final byte[] dataToSend = request.dataToSend();

            RequestBody body = null;
            if (dataToSend != null) {
                body = RequestBody.create(dataToSend, (MediaType) null);
            }

            final okhttp3.Request.Builder builder = new okhttp3.Request.Builder()
                    .method(request.httpMethod(), body)
                    .url(request.url())
                    .addHeader("User-Agent", USER_AGENT);

            for (final Map.Entry<String, List<String>> pair : request.headers().entrySet()) {
                builder.removeHeader(pair.getKey());
                for (final String value : pair.getValue()) {
                    builder.addHeader(pair.getKey(), value);
                }
            }

            final okhttp3.Response response = HTTP.newCall(builder.build()).execute();

            if (response.code() == 429) {
                response.close();
                throw new ReCaptchaException("reCaptcha challenge requested", request.url());
            }

            final ResponseBody responseBody = response.body();
            final String bodyString = responseBody == null ? null : responseBody.string();

            return new Response(
                    response.code(),
                    response.message(),
                    response.headers().toMultimap(),
                    bodyString,
                    response.request().url().toString());
        }
    }
}
