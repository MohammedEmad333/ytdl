package com.example.ytdl;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

/** HTTP shared between the extractor and the download service. */
public final class Net {

    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";

    /**
     * YouTube throttles a single long-lived GET on a stream URL to about playback speed —
     * the point is to stop a player buffering a whole video, and a downloader eats the same
     * limit. Each new ranged request gets a fresh budget, so never ask for much at once.
     */
    private static final long CHUNK_BYTES = 4L * 1024 * 1024;

    public static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private Net() {
    }

    /** Reports 0-100. Called often — keep implementations cheap. */
    public interface Progress {
        void onProgress(int percent);
    }

    /** True while the work should continue; lets a cancelled task stop mid-file. */
    public interface Live {
        boolean isLive();
    }

    public static void fetchToFile(final String url, final File dest,
                                   final Progress progress, final Live live)
            throws IOException {

        long total = -1;
        long written = 0;
        int lastShown = -1;

        try (OutputStream out = new FileOutputStream(dest)) {
            while (total < 0 || written < total) {
                if (!live.isLive()) {
                    throw new IOException("cancelled");
                }

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
                        throw new IOException("HTTP " + code);
                    }
                    final ResponseBody body = response.body();
                    if (body == null) {
                        throw new IOException("empty response body");
                    }

                    if (total < 0) {
                        total = totalLength(response.header("Content-Range"),
                                body.contentLength());
                    }

                    try (InputStream in = body.byteStream()) {
                        final byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = in.read(buffer)) > 0) {
                            if (!live.isLive()) {
                                throw new IOException("cancelled");
                            }
                            out.write(buffer, 0, read);
                            written += read;

                            if (total > 0) {
                                final int percent = (int) (written * 100 / total);
                                if (percent != lastShown) {
                                    lastShown = percent;
                                    progress.onProgress(percent);
                                }
                            }
                        }
                    }

                    // 200 rather than 206 means the server ignored Range and sent the lot.
                    if (code == 200) {
                        break;
                    }
                }

                // Without this, a chunk that yields nothing loops forever.
                if (written == chunkStart) {
                    throw new IOException("stalled at " + written + " bytes");
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

    /**
     * NewPipeExtractor never makes a network call itself — Downloader is abstract and you
     * supply the transport. Nothing works until this is registered via NewPipe.init.
     */
    public static final class OkHttpDownloader extends Downloader {

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

            final okhttp3.Response response = HTTP.newCall(builder.build()).execute();

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
