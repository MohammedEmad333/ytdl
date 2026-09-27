package com.example.ytdl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.MediaStore;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs the download queue.
 *
 * A foreground service rather than an executor in the activity, because a queue that dies
 * with the screen isn't a queue and a notification needs something alive to belong to.
 * Everything goes through here, including single-file downloads that once went to
 * DownloadManager — two mechanisms meant two progress models and two backgrounding
 * behaviours, which is worse than either alone.
 */
public class DownloadService extends Service {

    public static final String EXTRA_ACTION = "action";
    public static final String EXTRA_TASK_ID = "taskId";
    public static final String ACTION_PAUSE = "pause";
    public static final String ACTION_RESUME = "resume";
    public static final String ACTION_CANCEL = "cancel";

    /** What kind of file was asked for. */
    public enum Kind {
        MERGE,    // video-only stream + a chosen audio track, muxed together
        MUXED,    // a progressive stream that already has audio
        AUDIO,    // audio track on its own
        MP3,      // audio encoded to MP3 with metadata / optional cover
        SUBTITLE  // caption file on its own
    }

    /**
     * What the user asked for, in terms that survive the stream URLs expiring.
     *
     * This is the point of the whole redesign. A Task used to *be* two URLs; now it's a page
     * plus an intention, and the URLs are a cache. That's what lets a playlist item exist
     * before it's ever been extracted, and lets a task paused overnight re-derive itself
     * rather than fail.
     */
    public static final class Spec {
        public final Kind kind;
        /** Target height for MERGE/MUXED. Nearest at or below wins — see Streams.pickHeight. */
        public final int height;
        /** Explicitly chosen track, or null for whatever ranks best on the day. */
        public final String audioTrackId;
        /** Language tag for SUBTITLE. */
        public final String subtitleTag;
        /** Target MP3 bitrate in kbps. */
        public final int bitrateKbps;
        public final String label;

        private Spec(final Kind kind, final int height, final String audioTrackId,
                     final String subtitleTag, final int bitrateKbps, final String label) {
            this.kind = kind;
            this.height = height;
            this.audioTrackId = audioTrackId;
            this.subtitleTag = subtitleTag;
            this.bitrateKbps = bitrateKbps;
            this.label = label;
        }

        // Factories rather than one wide constructor: most fields are meaningless for most
        // kinds, and a call site passing three nulls says nothing about which kind it is.
        public static Spec merge(final int height, final String audioTrackId,
                                 final String label) {
            return new Spec(Kind.MERGE, height, audioTrackId, null, 0, label);
        }

        public static Spec muxed(final int height, final String label) {
            return new Spec(Kind.MUXED, height, null, null, 0, label);
        }

        public static Spec audio(final String audioTrackId, final String label) {
            return new Spec(Kind.AUDIO, 0, audioTrackId, null, 0, label);
        }

        public static Spec mp3(final int bitrateKbps) {
            final int bitrate = bitrateKbps >= 256 ? 320 : bitrateKbps <= 160 ? 128 : 192;
            return new Spec(Kind.MP3, 0, null, null, bitrate, "MP3 " + bitrate);
        }

        public static Spec subtitle(final String tag, final String label) {
            return new Spec(Kind.SUBTITLE, 0, null, tag, 0, label);
        }

        JSONObject toJson() throws JSONException {
            final JSONObject o = new JSONObject();
            o.put("kind", kind.name());
            o.put("height", height);
            o.putOpt("audioTrackId", audioTrackId);
            o.putOpt("subtitleTag", subtitleTag);
            o.put("bitrateKbps", bitrateKbps);
            o.put("label", label);
            return o;
        }

        static Spec fromJson(final JSONObject o) throws JSONException {
            return new Spec(Kind.valueOf(o.getString("kind")), o.getInt("height"),
                    o.isNull("audioTrackId") ? null : o.getString("audioTrackId"),
                    o.isNull("subtitleTag") ? null : o.getString("subtitleTag"),
                    o.optInt("bitrateKbps", 0), o.getString("label"));
        }
    }

    public enum State {
        QUEUED("Queued"),
        RESOLVING("Resolving"),
        VIDEO("Video"),
        AUDIO("Audio"),
        MERGING("Merging"),
        ENCODING("Encoding"),
        CONVERTING("Converting"),
        SAVING("Saving"),
        PAUSED("Paused"),
        DONE("Saved"),
        FAILED("Failed"),
        CANCELLED("Cancelled");

        public final String label;

        State(final String label) {
            this.label = label;
        }

        public boolean finished() {
            return this == DONE || this == FAILED || this == CANCELLED;
        }

        /** A worker thread is inside this task right now. */
        public boolean active() {
            return this == RESOLVING || this == VIDEO || this == AUDIO
                    || this == MERGING || this == ENCODING || this == CONVERTING
                    || this == SAVING;
        }

        /** Transferring bytes, so a byte count and a rate mean something. */
        public boolean transferring() {
            return this == VIDEO || this == AUDIO;
        }
    }

    /** A queued download. Mutable fields are volatile: the worker writes, the UI polls. */
    public static final class Task {
        public final long id;
        /** The durable identity. Everything else can be re-derived from this. */
        public final String pageUrl;
        public final Spec spec;

        /** Provisional for playlist items until the worker resolves them. */
        public volatile String title;

        // Cache, not identity. Null means "not resolved yet"; stale means "resolve again".
        volatile String videoUrl;
        volatile String audioUrl;
        volatile String extension = "mp4";
        volatile String mimeType = "video/mp4";
        public volatile String outputUri;
        public volatile String artist = "";
        public volatile String coverUrl = "";

        /**
         * Byte lengths from the first successful attempt, so a re-derived URL that serves
         * something different fails loudly instead of corrupting the partial on disk.
         */
        volatile long expectedVideoBytes = -1;
        volatile long expectedAudioBytes = -1;

        public volatile State state = State.QUEUED;
        public volatile long done;
        public volatile long total = -1;
        public volatile long bytesPerSecond;
        public volatile String error;
        public volatile boolean cancelled;
        public volatile boolean pauseRequested;

        long rateAt;
        long rateBytes;

        public Task(final String pageUrl, final String title, final Spec spec) {
            this(NEXT_ID.getAndIncrement(), pageUrl, title, spec);
        }

        private Task(final long id, final String pageUrl, final String title, final Spec spec) {
            this.id = id;
            this.pageUrl = pageUrl;
            this.title = title;
            this.spec = spec;
        }

        JSONObject toJson() throws JSONException {
            final JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("pageUrl", pageUrl);
            o.put("title", title);
            o.put("spec", spec.toJson());
            // Kept even though they may be stale: if they still work, a restored task saves
            // a request; if they don't, the 403 path re-derives them anyway.
            o.putOpt("videoUrl", videoUrl);
            o.putOpt("audioUrl", audioUrl);
            o.put("extension", extension);
            o.put("mimeType", mimeType);
            o.putOpt("outputUri", outputUri);
            o.put("artist", artist);
            o.put("coverUrl", coverUrl);
            o.put("expectedVideoBytes", expectedVideoBytes);
            o.put("expectedAudioBytes", expectedAudioBytes);
            o.put("state", state.name());
            o.put("done", done);
            o.put("total", total);
            o.putOpt("error", error);
            return o;
        }

        static Task fromJson(final JSONObject o) throws JSONException {
            final Task t = new Task(o.getLong("id"), o.getString("pageUrl"),
                    o.getString("title"), Spec.fromJson(o.getJSONObject("spec")));
            t.videoUrl = o.isNull("videoUrl") ? null : o.getString("videoUrl");
            t.audioUrl = o.isNull("audioUrl") ? null : o.getString("audioUrl");
            t.extension = o.optString("extension", "mp4");
            t.mimeType = o.optString("mimeType", "video/mp4");
            t.outputUri = o.isNull("outputUri") ? null : o.optString("outputUri", null);
            t.artist = o.optString("artist", "");
            t.coverUrl = o.optString("coverUrl", "");
            t.expectedVideoBytes = o.optLong("expectedVideoBytes", -1);
            t.expectedAudioBytes = o.optLong("expectedAudioBytes", -1);
            t.done = o.optLong("done", 0);
            t.total = o.optLong("total", -1);
            t.error = o.isNull("error") ? null : o.getString("error");

            final State saved = State.valueOf(o.getString("state"));
            // A task that was mid-transfer when the process died has a valid partial on
            // disk — the file is append-only and is only deleted on cancel or failure. That
            // is exactly the pause invariant, so a killed transfer and a paused one are the
            // same situation and restore identically. Crash recovery comes free.
            t.state = saved.active() ? State.PAUSED : saved;
            return t;
        }

        /** Pre-resolved by the activity, which already extracted to show you the list. */
        public Task metadata(final String artist, final String coverUrl) {
            this.artist = artist == null ? "" : artist;
            this.coverUrl = coverUrl == null ? "" : coverUrl;
            return this;
        }

        public void preResolve(final String videoUrl, final String audioUrl,
                               final String extension, final String mimeType) {
            this.videoUrl = videoUrl;
            this.audioUrl = audioUrl;
            this.extension = extension;
            this.mimeType = mimeType;
        }

        public String formatLabel() {
            return spec.label;
        }

        public int percent() {
            if (total <= 0) {
                return 0;
            }
            return (int) Math.min(100, done * 100 / total);
        }
    }

    private static final AtomicLong NEXT_ID = new AtomicLong(1);
    private static final String CHANNEL_ID = "downloads";
    private static final int NOTIFICATION_ID = 1;

    private static final List<Task> TASKS = Collections.synchronizedList(new ArrayList<>());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    // ---------------------------------------------------------------- persistence

    private static final String QUEUE_FILE = "queue.json";
    private static final Object IO_LOCK = new Object();
    private static boolean loaded;

    /**
     * Reads the queue back from disk. Called from both the activity and the service, since
     * either can be the first thing alive in a fresh process.
     *
     * Without this, expiry-proof resume was a claim rather than a feature: the queue lived
     * in a static list, so swiping the app away or letting Android reclaim the process took
     * every paused task with it and left its partial files orphaned in cache forever. Pausing
     * overnight is precisely the case where the process won't survive.
     */
    public static synchronized void ensureLoaded(final Context context) {
        if (loaded) {
            return;
        }
        loaded = true;

        synchronized (IO_LOCK) {
            final File file = new File(context.getFilesDir(), QUEUE_FILE);
            if (!file.exists()) {
                return;
            }
            try {
                final byte[] bytes = new byte[(int) file.length()];
                try (InputStream in = new FileInputStream(file)) {
                    int read = 0;
                    while (read < bytes.length) {
                        final int n = in.read(bytes, read, bytes.length - read);
                        if (n < 0) {
                            break;
                        }
                        read += n;
                    }
                }

                final JSONArray array = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
                long highest = 0;
                for (int i = 0; i < array.length(); i++) {
                    final Task task = Task.fromJson(array.getJSONObject(i));
                    TASKS.add(task);
                    highest = Math.max(highest, task.id);
                }
                // Ids are the temp-file names, so a restored task colliding with a new one
                // would have them fighting over the same partials.
                NEXT_ID.set(highest + 1);
            } catch (final Exception e) {
                // A corrupt queue file shouldn't brick the app. Losing it costs the queue.
                TASKS.clear();
            }
        }
    }

    static void save(final Context context) {
        synchronized (IO_LOCK) {
            try {
                final JSONArray array = new JSONArray();
                for (final Task task : snapshot()) {
                    array.put(task.toJson());
                }
                final File file = new File(context.getFilesDir(), QUEUE_FILE);
                try (Writer writer = new OutputStreamWriter(
                        new FileOutputStream(file), StandardCharsets.UTF_8)) {
                    writer.write(array.toString());
                }
            } catch (final Exception ignored) {
                // Best-effort. A failed write costs the queue on next launch, not this run.
            }
        }
    }

    // ---------------------------------------------------------------- api

    /** Copy for the UI to read without holding the lock while it draws. */
    public static List<Task> snapshot() {
        synchronized (TASKS) {
            return new ArrayList<>(TASKS);
        }
    }

    public static Task byId(final long id) {
        for (final Task t : snapshot()) {
            if (t.id == id) {
                return t;
            }
        }
        return null;
    }

    public static boolean enqueue(final Context context, final Task task) {
        synchronized (TASKS) {
            for (final Task existing : TASKS) {
                if (!existing.state.finished() && sameRequest(existing, task)) {
                    return false;
                }
            }
            TASKS.add(task);
        }
        save(context);
        wake(context, null, 0);
        return true;
    }

    private static boolean sameRequest(final Task a, final Task b) {
        return eq(a.pageUrl, b.pageUrl)
                && a.spec.kind == b.spec.kind
                && a.spec.height == b.spec.height
                && a.spec.bitrateKbps == b.spec.bitrateKbps
                && eq(a.spec.audioTrackId, b.spec.audioTrackId)
                && eq(a.spec.subtitleTag, b.spec.subtitleTag);
    }

    private static boolean eq(final String a, final String b) {
        return a == null ? b == null : a.equals(b);
    }

    public static boolean moveUp(final Context context, final Task task) {
        return move(context, task, -1);
    }

    public static boolean moveDown(final Context context, final Task task) {
        return move(context, task, 1);
    }

    public static boolean moveToTop(final Context context, final Task task) {
        if (task == null || task.state.active() || task.state.finished()) return false;
        synchronized (TASKS) {
            final int from = TASKS.indexOf(task);
            if (from <= 0) return false;
            int to = 0;
            while (to < from && TASKS.get(to).state.active()) {
                to++;
            }
            if (to >= from) return false;
            TASKS.remove(from);
            TASKS.add(to, task);
        }
        save(context);
        return true;
    }

    private static boolean move(final Context context, final Task task, final int delta) {
        if (task == null || task.state.active() || task.state.finished()) return false;
        synchronized (TASKS) {
            final int from = TASKS.indexOf(task);
            if (from < 0) return false;
            final int to = from + delta;
            if (to < 0 || to >= TASKS.size()) return false;
            final Task other = TASKS.get(to);
            if (other.state.active()) return false;
            Collections.swap(TASKS, from, to);
        }
        save(context);
        return true;
    }

    public static void pause(final Context context, final Task task) {
        task.pauseRequested = true;
        // A queued task has no worker inside it to notice the flag, so move it directly.
        if (task.state == State.QUEUED) {
            task.state = State.PAUSED;
        }
        save(context);
    }

    public static void resume(final Context context, final Task task) {
        task.pauseRequested = false;
        task.state = State.QUEUED;
        save(context);
        wake(context, null, 0);
    }

    /**
     * A failed task already knows its page URL and what was asked for, so retrying is just
     * putting it back in the queue. The cached URLs go, though — whatever failed may well
     * have been a stale one, and re-deriving is the point of keeping the spec.
     */
    public static void retry(final Context context, final Task task) {
        task.error = null;
        task.outputUri = null;
        task.cancelled = false;
        task.pauseRequested = false;
        task.videoUrl = null;
        task.audioUrl = null;
        task.expectedVideoBytes = -1;
        task.expectedAudioBytes = -1;
        task.done = 0;
        task.total = -1;
        task.state = State.QUEUED;
        save(context);
        wake(context, null, 0);
    }

    /**
     * Partial files survive a pause and are discarded on cancel — resume trusts the file's
     * length as its offset, so a stale partial left behind by a cancelled task would corrupt
     * a later download of the same id. Hence the cleanup here for tasks nothing is running.
     */
    public static void cancel(final Context context, final Task task) {
        task.cancelled = true;
        final State current = task.state;
        if (!current.active()) {
            deleteTemps(context, task);
            task.state = State.CANCELLED;
        }
        // If it is active, the worker's next checkpoint throws and cleans up there.
        save(context);
    }

    public static void clearFinished() {
        synchronized (TASKS) {
            final List<Task> keep = new ArrayList<>();
            for (final Task t : TASKS) {
                if (!t.state.finished()) {
                    keep.add(t);
                }
            }
            TASKS.clear();
            TASKS.addAll(keep);
        }
    }

    public static void clearFinished(final Context context) {
        clearFinished();
        save(context);
    }

    private static void wake(final Context context, final String action, final long taskId) {
        final Intent intent = new Intent(context, DownloadService.class);
        if (action != null) {
            intent.putExtra(EXTRA_ACTION, action);
            intent.putExtra(EXTRA_TASK_ID, taskId);
        }
        context.startForegroundService(intent);
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        ensureLoaded(this);
        final NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        // startForegroundService allows roughly five seconds to reach this before the system
        // kills the process, so it happens before anything that might block.
        startForegroundCompat(buildNotification("Downloads", "Starting…", 0, true, null));

        if (intent != null && intent.hasExtra(EXTRA_ACTION)) {
            final Task task = byId(intent.getLongExtra(EXTRA_TASK_ID, -1));
            if (task != null) {
                final String action = intent.getStringExtra(EXTRA_ACTION);
                if (ACTION_PAUSE.equals(action)) {
                    pause(this, task);
                } else if (ACTION_CANCEL.equals(action)) {
                    cancel(this, task);
                } else {
                    task.pauseRequested = false;
                    task.state = State.QUEUED;
                }
            }
        }

        if (RUNNING.compareAndSet(false, true)) {
            WORKER.execute(this::drainQueue);
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }

    private void startForegroundCompat(final Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14 requires the type at start, and it must match the manifest.
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    // ---------------------------------------------------------------- queue

    private void drainQueue() {
        try {
            Task task;
            while ((task = nextQueued()) != null) {
                process(task);
            }
        } finally {
            RUNNING.set(false);
            finish();
        }
    }

    private Task nextQueued() {
        synchronized (TASKS) {
            for (final Task t : TASKS) {
                if (t.state == State.QUEUED && !t.cancelled && !t.pauseRequested) {
                    return t;
                }
            }
        }
        return null;
    }

    private void finish() {
        int saved = 0;
        int failed = 0;
        int paused = 0;
        for (final Task t : snapshot()) {
            if (t.state == State.DONE) {
                saved++;
            } else if (t.state == State.FAILED) {
                failed++;
            } else if (t.state == State.PAUSED) {
                paused++;
            }
        }

        final StringBuilder summary = new StringBuilder()
                .append(saved).append(saved == 1 ? " file saved" : " files saved");
        if (failed > 0) {
            summary.append(" · ").append(failed).append(" failed");
        }
        if (paused > 0) {
            summary.append(" · ").append(paused).append(" paused");
        }

        // DETACH leaves the notification behind as a plain, dismissible summary rather than
        // yanking it the moment the last file lands.
        stopForeground(STOP_FOREGROUND_DETACH);
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
                buildNotification(paused > 0 ? "Downloads paused" : "Downloads finished",
                        summary.toString(), 0, false, null));
        stopSelf();
    }

    // ---------------------------------------------------------------- one task

    private void process(final Task task) {
        final Net.Control control = () -> {
            if (task.cancelled) {
                throw new Net.Cancelled();
            }
            if (task.pauseRequested) {
                throw new Net.Paused();
            }
        };

        try {
            // Playlist items arrive with nothing but a page URL and an intention.
            if (task.videoUrl == null) {
                resolve(task);
            }

            try {
                transfer(task, control);
            } catch (final IOException e) {
                // A paused task can sit for hours and YouTube's URLs don't last that long.
                // The bytes on disk are still good — same itag, same content — so re-derive
                // the URLs and carry on from where it stopped rather than starting over.
                if (Net.isStale(e) && !task.cancelled && !task.pauseRequested) {
                    resolve(task);
                    transfer(task, control);
                } else {
                    throw e;
                }
            }

            task.state = State.DONE;
        } catch (final Net.Paused e) {
            task.state = State.PAUSED;
        } catch (final Net.Cancelled e) {
            task.state = State.CANCELLED;
        } catch (final Exception e) {
            task.state = State.FAILED;
            task.error = e.getMessage() == null ? e.toString() : e.getMessage();
        } finally {
            // The one case where temp files are kept: they're the resume point.
            if (task.state != State.PAUSED) {
                deleteTemps(this, task);
            }
            update(task);
            save(this);
        }
    }

    /**
     * Turns the page URL and the spec back into stream URLs.
     *
     * Runs in two situations that look unrelated but aren't: a playlist item that was queued
     * without ever being extracted, and a task whose URLs expired while it sat paused. Both
     * are the same question — "what should this be, today?" — which is why the Task carries
     * an intention rather than a pair of URLs.
     */
    private static StreamingService serviceFor(final String url) {
        try {
            if (ServiceList.SoundCloud.getLinkTypeByUrl(url) != StreamingService.LinkType.NONE) {
                return ServiceList.SoundCloud;
            }
        } catch (final Exception ignored) {}
        return ServiceList.YouTube;
    }

    private void resolve(final Task task) throws Exception {
        task.state = State.RESOLVING;
        update(task);

        Net.ensureExtractor();
        final StreamInfo info = StreamInfo.getInfo(serviceFor(task.pageUrl), task.pageUrl);
        if (task.spec.kind != Kind.MP3 || task.title == null || task.title.trim().isEmpty()) {
            task.title = Streams.sanitize(info.getName());
        }
        if (task.spec.kind == Kind.MP3) {
            if (task.artist.isEmpty() && info.getUploaderName() != null) {
                task.artist = info.getUploaderName();
            }
            if (task.coverUrl.isEmpty() && info.getThumbnails() != null
                    && !info.getThumbnails().isEmpty()) {
                task.coverUrl = info.getThumbnails().get(0).getUrl();
            }
        }

        switch (task.spec.kind) {
            case SUBTITLE: {
                SubtitlesStream chosen = null;
                for (final SubtitlesStream sub : Streams.subtitles(info)) {
                    if (sub.getLanguageTag() != null
                            && sub.getLanguageTag().equals(task.spec.subtitleTag)) {
                        chosen = sub;
                        break;
                    }
                }
                if (chosen == null) {
                    throw new IOException("that subtitle track is gone");
                }
                // The language rides in the extension so the file lands as
                // "Title.en.ttml" without publish() needing to know about subtitles.
                task.preResolve(chosen.getContent(), null,
                        chosen.getLanguageTag() + "." + Streams.suffix(chosen.getFormat(), "ttml"),
                        chosen.getFormat() == null
                                ? "text/plain" : chosen.getFormat().getMimeType());
                break;
            }
            case AUDIO:
            case MP3: {
                final AudioStream a = Streams.pickAudio(
                        Streams.allAudio(info), task.spec.audioTrackId);
                if (a == null) {
                    throw new IOException("no audio stream available");
                }
                task.preResolve(a.getContent(), null,
                        task.spec.kind == Kind.MP3 ? "mp3" : Streams.suffix(a.getFormat(), "m4a"),
                        task.spec.kind == Kind.MP3 ? "audio/mpeg" : "audio/mp4");
                break;
            }
            case MUXED: {
                final VideoStream v = Streams.pickHeight(Streams.muxed(info), task.spec.height);
                if (v == null) {
                    throw new IOException("no muxed stream available");
                }
                task.preResolve(v.getContent(), null,
                        Streams.suffix(v.getFormat(), "mp4"), "video/mp4");
                break;
            }
            default: {
                final VideoStream v = Streams.pickHeight(
                        Streams.videoOnly(info), task.spec.height);
                final AudioStream a = Streams.pickAudio(
                        Streams.mergeAudio(info), task.spec.audioTrackId);
                if (v == null) {
                    throw new IOException("no muxable video at that quality");
                }
                if (a == null) {
                    throw new IOException("no AAC track to merge with");
                }
                task.preResolve(v.getContent(), a.getContent(), "mp4", "video/mp4");
                break;
            }
        }
    }

    private void transfer(final Task task, final Net.Control control) throws Exception {
        final File video = videoTemp(this, task);
        final File audio = audioTemp(this, task);
        final File merged = mergedTemp(this, task);

        // Both stages re-run from the top on resume. A completed stage costs one request that
        // comes back 416, which fetchToFile reads as "already have it all".
        stage(task, State.VIDEO);
        Net.fetchToFile(task.videoUrl, video, task.expectedVideoBytes, (done, total) -> {
            if (total > 0) {
                task.expectedVideoBytes = total;
            }
            report(task, done, total);
        }, control);

        if (task.spec.kind == Kind.MP3) {
            control.checkpoint();
            encodeMp3(task, video, control);
        } else if (task.spec.kind == Kind.SUBTITLE) {
            control.checkpoint();
            convertSubtitleToSrt(task, video, control);
        } else if (task.audioUrl != null) {
            stage(task, State.AUDIO);
            Net.fetchToFile(task.audioUrl, audio, task.expectedAudioBytes, (done, total) -> {
                if (total > 0) {
                    task.expectedAudioBytes = total;
                }
                report(task, done, total);
            }, control);

            control.checkpoint();
            task.state = State.MERGING;
            update(task);
            mux(video, audio, merged);

            task.state = State.SAVING;
            update(task);
            task.outputUri = publish(merged, task.title + ".mp4", "video/mp4").toString();
        } else {
            control.checkpoint();
            task.state = State.SAVING;
            update(task);
            task.outputUri = publish(video, task.title + "." + task.extension, task.mimeType).toString();
        }
    }

    private void stage(final Task task, final State state) {
        task.state = state;
        task.done = 0;
        task.total = -1;
        task.bytesPerSecond = 0;
        task.rateAt = 0;
        task.rateBytes = 0;
        update(task);
        save(this);
    }

    private static File videoTemp(final Context c, final Task t) {
        return new File(c.getCacheDir(), "v-" + t.id + ".part");
    }

    private static File audioTemp(final Context c, final Task t) {
        return new File(c.getCacheDir(), "a-" + t.id + ".part");
    }

    private static File mergedTemp(final Context c, final Task t) {
        return new File(c.getCacheDir(), "m-" + t.id + ".mp4");
    }

    private static File mp3Temp(final Context c, final Task t) {
        return new File(c.getCacheDir(), "e-" + t.id + ".mp3");
    }

    private static File subtitleTemp(final Context c, final Task t) {
        return new File(c.getCacheDir(), "s-" + t.id + ".srt");
    }

    private static File coverTemp(final Context c, final Task t) {
        return new File(c.getCacheDir(), "c-" + t.id + ".jpg");
    }

    private static void deleteTemps(final Context c, final Task t) {
        videoTemp(c, t).delete();
        audioTemp(c, t).delete();
        mergedTemp(c, t).delete();
        mp3Temp(c, t).delete();
        subtitleTemp(c, t).delete();
        coverTemp(c, t).delete();
    }

    private void convertSubtitleToSrt(final Task task, final File source,
                                      final Net.Control control) throws Exception {
        final File output = subtitleTemp(this, task);
        control.checkpoint();
        task.state = State.CONVERTING;
        task.done = 0;
        task.total = -1;
        update(task);
        save(this);

        final String command = "-y -i " + q(source.getAbsolutePath()) + " "
                + q(output.getAbsolutePath());
        final FFmpegSession session = FFmpegKit.execute(command);
        if (!ReturnCode.isSuccess(session.getReturnCode()) || !output.exists()
                || output.length() == 0) {
            throw new IOException("subtitle conversion to SRT failed");
        }

        control.checkpoint();
        task.state = State.SAVING;
        update(task);
        final String language = task.spec.subtitleTag == null || task.spec.subtitleTag.isEmpty()
                ? "subtitles" : task.spec.subtitleTag;
        task.extension = language + ".srt";
        task.mimeType = "application/x-subrip";
        task.outputUri = publish(output, task.title + "." + task.extension, task.mimeType).toString();
    }

    private void encodeMp3(final Task task, final File source, final Net.Control control)
            throws Exception {
        final File output = mp3Temp(this, task);
        final File cover = coverTemp(this, task);

        control.checkpoint();
        task.state = State.ENCODING;
        task.done = 0;
        task.total = -1;
        update(task);
        save(this);

        final boolean hasCover = downloadCover(task.coverUrl, cover);
        final StringBuilder command = new StringBuilder("-y -i ")
                .append(q(source.getAbsolutePath())).append(' ');
        if (hasCover) {
            command.append("-i ").append(q(cover.getAbsolutePath())).append(' ')
                    .append("-map 0:a:0 -map 1:v:0 -c:v mjpeg -disposition:v attached_pic ");
        } else {
            command.append("-map 0:a:0 -vn ");
        }
        command.append("-c:a libmp3lame -b:a ").append(task.spec.bitrateKbps)
                .append("k -id3v2_version 3 ")
                .append("-metadata title=").append(q(meta(task.title))).append(' ')
                .append("-metadata artist=").append(q(meta(task.artist))).append(' ')
                .append(q(output.getAbsolutePath()));

        final FFmpegSession session = FFmpegKit.execute(command.toString());
        if (!ReturnCode.isSuccess(session.getReturnCode()) || !output.exists()) {
            throw new IOException("MP3 conversion failed");
        }

        control.checkpoint();
        task.state = State.SAVING;
        update(task);
        task.outputUri = publishAudio(output, task.title + ".mp3").toString();
    }

    private boolean downloadCover(final String url, final File out) {
        if (url == null || url.isEmpty()) return false;
        try (okhttp3.Response response = Net.HTTP.newCall(new okhttp3.Request.Builder()
                .url(url).header("User-Agent", Net.USER_AGENT).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return false;
            try (InputStream in = response.body().byteStream();
                 OutputStream dest = new FileOutputStream(out)) {
                final byte[] buffer = new byte[32 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) dest.write(buffer, 0, n);
            }
            return out.length() > 0;
        } catch (final Exception ignored) {
            return false;
        }
    }

    private Uri publishAudio(final File source, final String displayName) throws IOException {
        final ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg");
        values.put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/YTDL");
        values.put(MediaStore.Audio.Media.IS_PENDING, 1);
        final Uri uri = getContentResolver().insert(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Couldn't create MP3 in MediaStore");
        try (InputStream in = new FileInputStream(source);
             OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IOException("Couldn't open MP3 destination");
            final byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (final Exception e) {
            getContentResolver().delete(uri, null, null);
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
        values.clear();
        values.put(MediaStore.Audio.Media.IS_PENDING, 0);
        getContentResolver().update(uri, values, null, null);
        return uri;
    }

    private static String q(final String value) {
        return "'" + (value == null ? "" : value.replace("'", "")) + "'";
    }

    private static String meta(final String value) {
        return value == null ? "" : value.replace("\n", " ").replace("\r", " ");
    }

    // ---------------------------------------------------------------- notification

    private void report(final Task task, final long done, final long total) {
        task.done = done;
        task.total = total;

        // A one-second window: long enough not to jitter, short enough to react. The whole
        // app is a transfer monitor that until now couldn't report a transfer rate.
        final long now = SystemClock.elapsedRealtime();
        if (task.rateAt == 0) {
            task.rateAt = now;
            task.rateBytes = done;
        } else {
            final long elapsed = now - task.rateAt;
            if (elapsed >= 1000) {
                task.bytesPerSecond = (done - task.rateBytes) * 1000 / elapsed;
                task.rateAt = now;
                task.rateBytes = done;
            }
        }

        // The system rate-limits notifications if they're hammered, and the UI polls anyway.
        if (task.percent() % 2 == 0) {
            update(task);
        }
    }

    private void update(final Task task) {
        int waiting = 0;
        for (final Task t : snapshot()) {
            if (t.state == State.QUEUED) {
                waiting++;
            }
        }

        final StringBuilder text = new StringBuilder(task.state.label);
        if (task.state.transferring() && task.total > 0) {
            text.append(' ').append(Ui.bytes(task.done))
                    .append(" / ").append(Ui.bytes(task.total));
            if (task.bytesPerSecond > 0) {
                text.append(" · ").append(Ui.bytes(task.bytesPerSecond)).append("/s");
                if (task.done < task.total) {
                    final long eta = (task.total - task.done + task.bytesPerSecond - 1)
                            / task.bytesPerSecond;
                    text.append(" · ETA ").append(formatEta(eta));
                }
            }
        }
        if (waiting > 0) {
            text.append(" · ").append(waiting).append(" waiting");
        }

        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
                buildNotification(task.title, text.toString(), task.percent(), true, task));
    }

    private static String formatEta(final long seconds) {
        final long h = seconds / 3600;
        final long m = (seconds % 3600) / 60;
        final long s = seconds % 60;
        return h > 0 ? String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
                : String.format(java.util.Locale.US, "%d:%02d", m, s);
    }

    private Notification buildNotification(final String title, final String text,
                                           final int percent, final boolean ongoing,
                                           final Task task) {
        final PendingIntent tap = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        final Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                // A framework icon: a custom one would need a drawable, and the vector
                // launcher icon isn't legible at status-bar size anyway.
                .setSmallIcon(ongoing
                        ? android.R.drawable.stat_sys_download
                        : android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(tap)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing);

        if (ongoing) {
            builder.setProgress(100, percent, percent <= 0);
        }

        // Pausing matters most when you're not in the app, which is exactly when the
        // notification is the only surface you have.
        if (task != null && !task.state.finished()) {
            final boolean paused = task.state == State.PAUSED;
            final Intent action = new Intent(this, DownloadService.class)
                    .putExtra(EXTRA_ACTION, paused ? ACTION_RESUME : ACTION_PAUSE)
                    .putExtra(EXTRA_TASK_ID, task.id);
            // Request code varies per task, or the system reuses one PendingIntent for all.
            final PendingIntent pi = PendingIntent.getService(this, (int) task.id, action,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(new Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this,
                            paused ? android.R.drawable.ic_media_play
                                    : android.R.drawable.ic_media_pause),
                    paused ? "Resume" : "Pause", pi).build());

            final Intent cancelIntent = new Intent(this, DownloadService.class)
                    .putExtra(EXTRA_ACTION, ACTION_CANCEL)
                    .putExtra(EXTRA_TASK_ID, task.id);
            final PendingIntent cancelPi = PendingIntent.getService(this,
                    (int) (task.id ^ 0x5a5a5a5aL), cancelIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(new Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this,
                            android.R.drawable.ic_menu_close_clear_cancel),
                    "Cancel", cancelPi).build());
        }

        return builder.build();
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
                throw new IOException("couldn't find both a video and an audio track");
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

            copyInterleaved(videoExtractor, audioExtractor, muxer, outVideo, outAudio,
                    buffer, info);

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

    /**
     * Writes both tracks in timestamp order, alternating between them.
     *
     * The obvious version — copy the whole video track, then the whole audio track — produces
     * a valid MP4 that plays badly. MediaMuxer writes samples into the file in the order it
     * receives them, so that layout puts every video sample in the first half of the file and
     * every audio sample in the second. Playing it means seeking across the whole file and
     * back for every 20ms of audio, which defeats the player's read-ahead entirely.
     *
     * It only shows up at high resolutions, which is what makes it easy to miss: a 360p file
     * is small enough to sit in cache, so the thrashing costs nothing. A 1080p60 file is
     * 150MB and it doesn't.
     */
    private static void copyInterleaved(final MediaExtractor video, final MediaExtractor audio,
                                        final MediaMuxer muxer, final int videoTrack,
                                        final int audioTrack, final ByteBuffer buffer,
                                        final MediaCodec.BufferInfo info) {
        while (true) {
            // -1 once a track is exhausted.
            final long videoTime = video.getSampleTime();
            final long audioTime = audio.getSampleTime();

            if (videoTime < 0 && audioTime < 0) {
                break;
            }

            // Whichever track is further behind goes next, so the file lands in roughly
            // playback order and a player can read it front to back.
            final boolean takeVideo = audioTime < 0
                    || (videoTime >= 0 && videoTime <= audioTime);
            final MediaExtractor source = takeVideo ? video : audio;
            final int track = takeVideo ? videoTrack : audioTrack;

            final int size = source.readSampleData(buffer, 0);
            if (size < 0) {
                // Exhausted between the time check and the read; advance so getSampleTime
                // reports -1 next time round rather than spinning here.
                source.advance();
                continue;
            }

            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = source.getSampleTime();
            // MediaExtractor and MediaCodec use different flag constants for the same idea,
            // so translate rather than passing the raw value through.
            info.flags = (source.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;

            muxer.writeSampleData(track, buffer, info);
            source.advance();
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

    /** IS_PENDING hides the file from the gallery until the copy is actually complete. */
    private Uri publish(final File source, final String displayName, final String mimeType)
            throws IOException {

        final ContentResolver resolver = getContentResolver();
        final ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
        values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        final Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("MediaStore wouldn't accept the file");
        }

        try (InputStream in = new FileInputStream(source);
             OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) {
                throw new IOException("couldn't open the destination for writing");
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
        return uri;
    }
}
