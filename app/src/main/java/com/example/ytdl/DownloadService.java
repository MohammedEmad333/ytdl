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

import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

    /** What kind of file was asked for. */
    public enum Kind {
        MERGE,   // video-only stream + a chosen audio track, muxed together
        MUXED,   // a progressive stream that already has audio
        AUDIO    // audio track on its own
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
        public final String label;

        public Spec(final Kind kind, final int height, final String audioTrackId,
                    final String label) {
            this.kind = kind;
            this.height = height;
            this.audioTrackId = audioTrackId;
            this.label = label;
        }
    }

    public enum State {
        QUEUED("Queued"),
        RESOLVING("Resolving"),
        VIDEO("Video"),
        AUDIO("Audio"),
        MERGING("Merging"),
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
                    || this == MERGING || this == SAVING;
        }

        /** Transferring bytes, so a byte count and a rate mean something. */
        public boolean transferring() {
            return this == VIDEO || this == AUDIO;
        }
    }

    /** A queued download. Mutable fields are volatile: the worker writes, the UI polls. */
    public static final class Task {
        public final long id = NEXT_ID.getAndIncrement();
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
            this.pageUrl = pageUrl;
            this.title = title;
            this.spec = spec;
        }

        /** Pre-resolved by the activity, which already extracted to show you the list. */
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

    public static void enqueue(final Context context, final Task task) {
        TASKS.add(task);
        wake(context, null, 0);
    }

    public static void pause(final Task task) {
        task.pauseRequested = true;
        // A queued task has no worker inside it to notice the flag, so move it directly.
        if (task.state == State.QUEUED) {
            task.state = State.PAUSED;
        }
    }

    public static void resume(final Context context, final Task task) {
        task.pauseRequested = false;
        task.state = State.QUEUED;
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
                if (ACTION_PAUSE.equals(intent.getStringExtra(EXTRA_ACTION))) {
                    pause(task);
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
    private void resolve(final Task task) throws Exception {
        task.state = State.RESOLVING;
        update(task);

        Net.ensureExtractor();
        final StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, task.pageUrl);
        task.title = Streams.sanitize(info.getName());

        switch (task.spec.kind) {
            case AUDIO: {
                final AudioStream a = Streams.pickAudio(
                        Streams.allAudio(info), task.spec.audioTrackId);
                if (a == null) {
                    throw new IOException("no audio stream available");
                }
                task.preResolve(a.getContent(), null,
                        Streams.suffix(a.getFormat(), "m4a"), "audio/mp4");
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

        if (task.audioUrl != null) {
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
            publish(merged, task.title + ".mp4", "video/mp4");
        } else {
            control.checkpoint();
            task.state = State.SAVING;
            update(task);
            publish(video, task.title + "." + task.extension, task.mimeType);
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

    private static void deleteTemps(final Context c, final Task t) {
        videoTemp(c, t).delete();
        audioTemp(c, t).delete();
        mergedTemp(c, t).delete();
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
            }
        }
        if (waiting > 0) {
            text.append(" · ").append(waiting).append(" waiting");
        }

        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
                buildNotification(task.title, text.toString(), task.percent(), true, task));
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

    /** IS_PENDING hides the file from the gallery until the copy is actually complete. */
    private void publish(final File source, final String displayName, final String mimeType)
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
    }
}
