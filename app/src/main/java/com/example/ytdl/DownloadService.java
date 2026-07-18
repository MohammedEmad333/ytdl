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
import android.provider.MediaStore;

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
 * This is a foreground service rather than an executor in the activity because a queue that
 * dies when the screen goes away isn't a queue — and a notification needs something alive to
 * belong to. Everything goes through here now, including single-file downloads that used to
 * be handed to DownloadManager: two mechanisms meant two progress models and two ways to
 * behave when backgrounded, for no benefit.
 */
public class DownloadService extends Service {

    public enum State {
        QUEUED("Queued"),
        VIDEO("Downloading video"),
        AUDIO("Downloading audio"),
        MERGING("Merging"),
        SAVING("Saving"),
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
    }

    /** A queued download. Mutable fields are volatile: the worker writes, the UI polls. */
    public static final class Task {
        public final long id = NEXT_ID.getAndIncrement();
        public final String title;
        public final String videoUrl;
        /** Null means the file already has audio and needs no merge. */
        public final String audioUrl;
        public final String extension;
        public final String mimeType;
        public final String formatLabel;

        public volatile State state = State.QUEUED;
        public volatile int percent;
        public volatile String error;
        public volatile boolean cancelled;

        public Task(final String title, final String videoUrl, final String audioUrl,
                    final String extension, final String mimeType, final String formatLabel) {
            this.title = title;
            this.videoUrl = videoUrl;
            this.audioUrl = audioUrl;
            this.extension = extension;
            this.mimeType = mimeType;
            this.formatLabel = formatLabel;
        }
    }

    private static final AtomicLong NEXT_ID = new AtomicLong(1);
    private static final String CHANNEL_ID = "downloads";
    private static final int NOTIFICATION_ID = 1;

    private static final List<Task> TASKS = Collections.synchronizedList(new ArrayList<>());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    /** Copy for the UI to read without holding the lock while it draws. */
    public static List<Task> snapshot() {
        synchronized (TASKS) {
            return new ArrayList<>(TASKS);
        }
    }

    public static void enqueue(final Context context, final Task task) {
        TASKS.add(task);
        final Intent intent = new Intent(context, DownloadService.class);
        context.startForegroundService(intent);
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

    /** The worker checks this between chunks, so cancelling doesn't wait for the file. */
    public static void cancel(final Task task) {
        task.cancelled = true;
        if (task.state == State.QUEUED) {
            task.state = State.CANCELLED;
        }
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
        // startForegroundService gives roughly five seconds to get here before the system
        // kills the process, so this happens before anything slow.
        startForegroundCompat(buildNotification("Starting…", null, 0, true));

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
            // Android 14 requires the type to be declared at start and to match the manifest.
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
                if (t.state == State.QUEUED && !t.cancelled) {
                    return t;
                }
            }
        }
        return null;
    }

    private void finish() {
        int saved = 0;
        int failed = 0;
        for (final Task t : snapshot()) {
            if (t.state == State.DONE) {
                saved++;
            } else if (t.state == State.FAILED) {
                failed++;
            }
        }

        final String summary = failed == 0
                ? saved + (saved == 1 ? " file saved" : " files saved")
                : saved + " saved, " + failed + " failed";

        // DETACH leaves the notification behind as a plain, dismissible summary rather than
        // yanking it the instant the last file lands.
        stopForeground(STOP_FOREGROUND_DETACH);
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
                buildNotification("Downloads finished", summary, 0, false));
        stopSelf();
    }

    // ---------------------------------------------------------------- one task

    private void process(final Task task) {
        final File video = new File(getCacheDir(), "v-" + task.id + ".part");
        final File audio = new File(getCacheDir(), "a-" + task.id + ".part");
        final File merged = new File(getCacheDir(), "m-" + task.id + ".mp4");

        try {
            if (task.audioUrl == null) {
                task.state = State.VIDEO;
                Net.fetchToFile(task.videoUrl, video, p -> report(task, p), () -> !task.cancelled);

                task.state = State.SAVING;
                task.percent = 100;
                update(task);
                publish(video, task.title + "." + task.extension, task.mimeType);
            } else {
                task.state = State.VIDEO;
                Net.fetchToFile(task.videoUrl, video, p -> report(task, p), () -> !task.cancelled);

                task.state = State.AUDIO;
                task.percent = 0;
                Net.fetchToFile(task.audioUrl, audio, p -> report(task, p), () -> !task.cancelled);

                task.state = State.MERGING;
                update(task);
                mux(video, audio, merged);

                task.state = State.SAVING;
                update(task);
                publish(merged, task.title + ".mp4", "video/mp4");
            }

            task.state = State.DONE;
            task.percent = 100;
        } catch (final Exception e) {
            if (task.cancelled) {
                task.state = State.CANCELLED;
            } else {
                task.state = State.FAILED;
                task.error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
        } finally {
            video.delete();
            audio.delete();
            merged.delete();
            update(task);
        }
    }

    private void report(final Task task, final int percent) {
        task.percent = percent;
        // Notifications get rate-limited by the system if hammered, and the UI polls anyway.
        if (percent % 2 == 0) {
            update(task);
        }
    }

    private void update(final Task task) {
        int remaining = 0;
        for (final Task t : snapshot()) {
            if (!t.state.finished()) {
                remaining++;
            }
        }
        final String queued = remaining > 1 ? " · " + (remaining - 1) + " waiting" : "";
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
                buildNotification(task.title,
                        task.state.label + " " + task.percent + "%" + queued,
                        task.percent, true));
    }

    private Notification buildNotification(final String title, final String text,
                                           final int percent, final boolean ongoing) {
        final PendingIntent tap = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        final Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                // A framework icon: a real one would be a binary asset in the repo.
                .setSmallIcon(ongoing
                        ? android.R.drawable.stat_sys_download
                        : android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentIntent(tap)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing);

        if (text != null) {
            builder.setContentText(text);
        }
        if (ongoing) {
            builder.setProgress(100, percent, percent <= 0);
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
