package com.example.ytdl;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

/**
 * The whole app in one file. Normally the Downloader, the Application subclass and the
 * layout would each live separately — they're folded in here because every extra file is
 * another thing to hand-create in GitHub's web editor on a phone.
 */
public class MainActivity extends Activity {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";

    private static boolean extractorReady = false;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Option> options = new ArrayList<>();

    private EditText urlInput;
    private TextView status;
    private ArrayAdapter<String> adapter;
    private String videoTitle = "video";

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // NewPipe.init sets static state, so it only needs to happen once per process.
        // An Application subclass is the tidy home for this, but that's another file and
        // another manifest attribute to get wrong.
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

    // ---------------------------------------------------------------- extraction

    private void fetch(final String url) {
        if (url.isEmpty()) {
            status.setText("Paste a YouTube link above, then tap Fetch.");
            return;
        }

        status.setText("Fetching…");
        adapter.clear();
        options.clear();

        // StreamInfo.getInfo does real network I/O — calling it on the UI thread throws
        // NetworkOnMainThreadException.
        executor.execute(() -> {
            try {
                final StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, url);
                main.post(() -> show(info));
            } catch (final Exception e) {
                main.post(() -> status.setText("Couldn't read that video: "
                        + e.getMessage()));
            }
        });
    }

    private void show(final StreamInfo info) {
        videoTitle = sanitize(info.getName());
        status.setText(info.getName() + "\n" + info.getUploaderName()
                + "\n\nTap a format to save it.");

        // getVideoStreams() is muxed video+audio — one file, directly playable.
        // getVideoOnlyStreams() has the high resolutions but no sound, and merging those
        // with an audio track needs ffmpeg. Not attempted here.
        for (final VideoStream vs : info.getVideoStreams()) {
            if (vs.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) {
                continue;
            }
            options.add(new Option(
                    "Video · " + vs.getResolution() + " · " + nameOf(vs.getFormat()),
                    vs.getContent(),
                    suffixOf(vs.getFormat(), "mp4")));
        }

        for (final AudioStream as : info.getAudioStreams()) {
            if (as.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) {
                continue;
            }
            options.add(new Option(
                    "Audio · " + as.getAverageBitrate() + " kbps · " + nameOf(as.getFormat()),
                    as.getContent(),
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

    // ---------------------------------------------------------------- download

    private void download(final Option option) {
        final DownloadManager.Request request =
                new DownloadManager.Request(Uri.parse(option.url));

        // Same UA the extractor used to resolve the URL. YouTube's CDN can reject the
        // transfer otherwise.
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

    // ---------------------------------------------------------------- helpers

    /** One downloadable stream, flattened to what DownloadManager needs. */
    private static final class Option {
        final String label;
        final String url;
        final String extension;

        Option(final String label, final String url, final String extension) {
            this.label = label;
            this.url = url;
            this.extension = extension;
        }
    }

    private static String nameOf(final MediaFormat format) {
        return format == null ? "unknown" : format.getName();
    }

    private static String suffixOf(final MediaFormat format, final String fallback) {
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

    // ---------------------------------------------------------------- downloader

    /**
     * NewPipeExtractor never makes a network call itself — Downloader is abstract and you
     * supply the transport. Nothing works until this is registered via NewPipe.init.
     */
    private static final class OkHttpDownloader extends Downloader {

        private final OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

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

            // Headers arrive as a multimap — a name can legitimately repeat, so clear then
            // re-add rather than using header() and silently dropping values.
            for (final Map.Entry<String, List<String>> pair : request.headers().entrySet()) {
                builder.removeHeader(pair.getKey());
                for (final String value : pair.getValue()) {
                    builder.addHeader(pair.getKey(), value);
                }
            }

            final okhttp3.Response response = client.newCall(builder.build()).execute();

            // 429 means YouTube wants a captcha. The extractor has a dedicated exception so
            // callers can tell "rate limited" apart from "genuinely broken".
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
