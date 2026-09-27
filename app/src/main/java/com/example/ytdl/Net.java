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
import android.os.StatFs;
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
     *
     * That workaround turned out to buy pause/resume almost for free: the transfer is already
     * a sequence of ranged requests, so stopping between them and later restarting from the
     * partial file's length costs nothing extra.
     */
    private static final long CHUNK_BYTES = 4L * 1024 * 1024;
    private static final int CHUNK_RETRIES = 3;
    private static final long STORAGE_RESERVE_BYTES = 32L * 1024 * 1024;

    public static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private Net() {
    }

    private static boolean extractorReady;

    /**
     * NewPipe.init sets static state and must run once before any extraction. Both the
     * activity and the service extract now — the service resolves playlist items and
     * re-derives expired URLs — so neither can assume the other got here first.
     */
    public static synchronized void ensureExtractor() {
        if (!extractorReady) {
            org.schabi.newpipe.extractor.NewPipe.init(new OkHttpDownloader(),
                    new org.schabi.newpipe.extractor.localization.Localization("en", "US"));
            extractorReady = true;
        }
    }

    /** Thrown to unwind a transfer cleanly. Partial files are kept for resuming. */
    public static final class Paused extends IOException {
        public Paused() {
            super("paused");
        }
    }

    /** Thrown when Wi-Fi-only mode is enabled and Wi-Fi is unavailable. */
    public static final class NetworkBlocked extends IOException {
        public NetworkBlocked() {
            super("waiting for Wi-Fi");
        }
    }

    /** A resumed response that cannot be safely appended to the existing partial file. */
    private static final class UnsafeResume extends IOException {
        UnsafeResume(final String message) {
            super(message);
        }
    }

    /** Thrown to unwind a transfer for good. Partial files are discarded. */
    public static final class Cancelled extends IOException {
        public Cancelled() {
            super("cancelled");
        }
    }

    /** Bytes so far and total, or -1 total while it's still unknown. */
    public interface Progress {
        void onProgress(long done, long total);
    }

    /** Checked between chunks and between buffer reads. Throws to stop. */
    public interface Control {
        void checkpoint() throws Paused, Cancelled, NetworkBlocked;
    }

    /**
     * A stream URL that's no longer valid. YouTube's expire after a few hours, so this is
     * what a download paused overnight hits on resume — the URL is stale, not the file.
     */
    public static boolean isStale(final IOException e) {
        final String message = e.getMessage();
        return message != null && (message.contains("HTTP 403") || message.contains("HTTP 401"));
    }

    /**
     * Downloads to dest, resuming automatically if a partial file is already there.
     *
     * Resume works by trusting the file on disk: whatever length it has is the offset to ask
     * for next. That holds only because the file is append-only and is deleted outright on
     * cancel or failure — it's never left in a state where its length lies about what's in it.
     *
     * expectedTotal is the other half of that guarantee, and it matters once URLs can be
     * re-derived after expiry. A fresh URL is supposed to serve identical bytes for the same
     * itag, and in practice does — but if it ever didn't, appending to the old partial would
     * produce a corrupt file with no error anywhere. Pass the length recorded on the first
     * attempt and a mismatch becomes a loud failure instead of a broken video. Pass -1 when
     * nothing is known yet.
     */
    public static void fetchToFile(final String url, final File dest, final long expectedTotal,
                                   final Progress progress, final Control control)
            throws IOException {

        long written = dest.exists() ? dest.length() : 0;
        long total = -1;
        ensureWritableSpace(dest, CHUNK_BYTES);

        // Append, never truncate — the existing bytes are the resume point.
        try (OutputStream out = new FileOutputStream(dest, true)) {
            while (total < 0 || written < total) {
                control.checkpoint();

                final long chunkStart = written;
                ensureWritableSpace(dest, CHUNK_BYTES);
                int attempt = 0;
                while (true) {
                    control.checkpoint();
                    final long requestStart = written;
                    final okhttp3.Request request = new okhttp3.Request.Builder()
                            .url(url)
                            .addHeader("User-Agent", USER_AGENT)
                            .addHeader("Range", "bytes=" + requestStart + "-"
                                    + (requestStart + CHUNK_BYTES - 1))
                            .build();

                    try (okhttp3.Response response = HTTP.newCall(request).execute()) {
                        final int code = response.code();

                        // 416 means we asked to start past the end. On a resume that's just the
                        // file already being complete — which is how a finished stage reports
                        // itself when a paused task gets re-run from the top.
                        if (code == 416 && written > 0) {
                            return;
                        }
                        if (code != 200 && code != 206) {
                            if (isTransientHttp(code) && attempt < CHUNK_RETRIES) {
                                attempt++;
                                retryDelay(control, attempt);
                                continue;
                            }
                            throw new IOException("HTTP " + code);
                        }

                        // Once a partial exists, appending is only safe when the server
                        // explicitly honors the requested offset with a matching 206 range.
                        if (requestStart > 0) {
                            if (code != 206) {
                                throw new UnsafeResume("server ignored resume range at "
                                        + requestStart + " bytes");
                            }
                            final long contentRangeStart =
                                    contentRangeStart(response.header("Content-Range"));
                            if (contentRangeStart != requestStart) {
                                throw new UnsafeResume("resume range mismatch (requested "
                                        + requestStart + ", got " + contentRangeStart + ")");
                            }
                        }

                        final ResponseBody body = response.body();
                        if (body == null) {
                            throw new IOException("empty response body");
                        }

                        if (total < 0) {
                            total = totalLength(response.header("Content-Range"),
                                    body.contentLength(), written);

                            // A re-derived URL that reports a different length isn't the stream
                            // we already have bytes from. Appending would corrupt silently.
                            if (expectedTotal > 0 && total > 0 && total != expectedTotal) {
                                throw new IOException("stream changed since it was queued ("
                                        + expectedTotal + " -> " + total + " bytes)");
                            }
                        }

                        try (InputStream in = body.byteStream()) {
                            final byte[] buffer = new byte[64 * 1024];
                            int read;
                            while ((read = in.read(buffer)) > 0) {
                                control.checkpoint();
                                out.write(buffer, 0, read);
                                written += read;
                                progress.onProgress(written, total);
                            }
                        }

                        // 200 rather than 206 means the server ignored Range and sent the lot.
                        if (code == 200) {
                            return;
                        }
                        break;
                    } catch (final Paused | Cancelled | NetworkBlocked e) {
                        throw e;
                    } catch (final UnsafeResume e) {
                        throw e;
                    } catch (final IOException e) {
                        if (isStale(e) || attempt >= CHUNK_RETRIES) {
                            throw e;
                        }
                        attempt++;
                        retryDelay(control, attempt);
                    }
                }

                // Without this, a chunk that yields nothing loops forever.
                if (written == chunkStart) {
                    throw new IOException("stalled at " + written + " bytes");
                }
            }
        }
    }

    private static void ensureWritableSpace(final File dest, final long upcomingBytes)
            throws IOException {
        final File parent = dest.getParentFile();
        if (parent == null) return;
        final StatFs stat = new StatFs(parent.getAbsolutePath());
        final long available = stat.getAvailableBytes();
        final long required = Math.max(CHUNK_BYTES, upcomingBytes) + STORAGE_RESERVE_BYTES;
        if (available < required) {
            throw new IOException("not enough storage space (need about "
                    + required / (1024 * 1024) + " MB free)");
        }
    }

    private static boolean isTransientHttp(final int code) {
        return code == 408 || code == 425 || code == 500 || code == 502
                || code == 503 || code == 504;
    }

    private static void retryDelay(final Control control, final int attempt)
            throws Paused, Cancelled, NetworkBlocked {
        final long delayMs = 500L * (1L << Math.min(attempt - 1, 2));
        final long until = System.currentTimeMillis() + delayMs;
        while (true) {
            control.checkpoint();
            final long remaining = until - System.currentTimeMillis();
            if (remaining <= 0) break;
            try {
                Thread.sleep(Math.min(200L, remaining));
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static long contentRangeStart(final String contentRange) {
        if (contentRange == null) return -1;
        final int space = contentRange.indexOf(' ');
        final int dash = contentRange.indexOf('-');
        if (space < 0 || dash <= space + 1) return -1;
        try {
            return Long.parseLong(contentRange.substring(space + 1, dash).trim());
        } catch (final NumberFormatException ignored) {
            return -1;
        }
    }

    /**
     * Content-Range reads "bytes 4194304-8388607/52428800" — the tail is the full length,
     * which is what's wanted, not the length of this one chunk. A plain 200 carries no
     * Content-Range, and there the body is everything from where we already are.
     */
    private static long totalLength(final String contentRange, final long bodyLength,
                                    final long alreadyWritten) {
        if (contentRange != null) {
            final int slash = contentRange.indexOf('/');
            if (slash >= 0) {
                try {
                    return Long.parseLong(contentRange.substring(slash + 1).trim());
                } catch (final NumberFormatException ignored) {
                    // Unparseable — fall through.
                }
            }
        }
        return bodyLength < 0 ? -1 : alreadyWritten + bodyLength;
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
