package com.example.ytdl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.MediaStore;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Request;
import okhttp3.Response;

/** Downloads an extracted audio stream and converts it to a tagged MP3. */
public final class Mp3ExportService extends Service {
    private static final String CHANNEL = "mp3_exports";
    private static final int NOTIFICATION = 22;
    private static final String URL = "url";
    private static final String PAGE_URL = "page_url";
    private static final String TITLE = "title";
    private static final String ARTIST = "artist";
    private static final String COVER = "cover";
    private static final String BITRATE = "bitrate";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    public static void enqueue(final Context context, final String url, final String title,
                               final String artist, final String coverUrl) {
        enqueue(context, url, title, artist, coverUrl, 192);
    }

    public static void enqueue(final Context context, final String url, final String title,
                               final String artist, final String coverUrl, final int bitrateKbps) {
        final Intent intent = new Intent(context, Mp3ExportService.class)
                .putExtra(URL, url)
                .putExtra(TITLE, title)
                .putExtra(ARTIST, artist)
                .putExtra(COVER, coverUrl)
                .putExtra(BITRATE, normalizeBitrate(bitrateKbps));
        context.startForegroundService(intent);
    }

    public static void enqueuePage(final Context context, final String pageUrl, final String title,
                                   final String artist, final String coverUrl, final int bitrateKbps) {
        final Intent intent = new Intent(context, Mp3ExportService.class)
                .putExtra(PAGE_URL, pageUrl)
                .putExtra(TITLE, title)
                .putExtra(ARTIST, artist)
                .putExtra(COVER, coverUrl)
                .putExtra(BITRATE, normalizeBitrate(bitrateKbps));
        context.startForegroundService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        final NotificationChannel channel = new NotificationChannel(
                CHANNEL, "MP3 exports", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        foreground("Preparing MP3…");
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        final String url = safe(intent.getStringExtra(URL));
        final String pageUrl = safe(intent.getStringExtra(PAGE_URL));
        if (url.isEmpty() && pageUrl.isEmpty()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        final String title = Streams.sanitize(intent.getStringExtra(TITLE));
        final String artist = safe(intent.getStringExtra(ARTIST));
        final String cover = safe(intent.getStringExtra(COVER));
        final int bitrate = normalizeBitrate(intent.getIntExtra(BITRATE, 192));
        WORKER.execute(() -> runJob(startId, url, pageUrl, title, artist, cover, bitrate));
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(final Intent intent) { return null; }

    private void runJob(final int startId, final String directUrl, final String pageUrl,
                        final String title, final String artist, final String coverUrl,
                        final int bitrateKbps) {
        final File source = new File(getCacheDir(), "mp3-" + startId + ".source");
        final File cover = new File(getCacheDir(), "mp3-" + startId + ".jpg");
        final File output = new File(getCacheDir(), "mp3-" + startId + ".mp3");
        try {
            String audioUrl = directUrl;
            String resolvedArtist = artist;
            String resolvedCover = coverUrl;
            if (audioUrl.isEmpty()) {
                update("Resolving audio…");
                Net.ensureExtractor();
                final org.schabi.newpipe.extractor.stream.StreamInfo info =
                        org.schabi.newpipe.extractor.stream.StreamInfo.getInfo(
                                org.schabi.newpipe.extractor.ServiceList.YouTube, pageUrl);
                final java.util.List<org.schabi.newpipe.extractor.stream.AudioStream> audio =
                        Streams.allAudio(info);
                if (audio.isEmpty()) throw new IOException("No downloadable audio stream found");
                audioUrl = audio.get(0).getContent();
                if (resolvedArtist.isEmpty() && info.getUploaderName() != null) {
                    resolvedArtist = info.getUploaderName();
                }
                if (resolvedCover.isEmpty() && info.getThumbnails() != null
                        && !info.getThumbnails().isEmpty()) {
                    resolvedCover = info.getThumbnails().get(0).getUrl();
                }
            }

            update("Downloading audio…");
            Net.fetchToFile(audioUrl, source, -1, (done, total) -> {}, () -> {});
            final boolean hasCover = downloadCover(resolvedCover, cover);

            update("Encoding MP3 " + bitrateKbps + " kbps…");
            final StringBuilder command = new StringBuilder("-y -i ")
                    .append(q(source.getAbsolutePath())).append(' ');
            if (hasCover) {
                command.append("-i ").append(q(cover.getAbsolutePath())).append(' ')
                        .append("-map 0:a:0 -map 1:v:0 -c:v mjpeg -disposition:v attached_pic ");
            } else {
                command.append("-map 0:a:0 ");
            }
            command.append("-vn ".replace("-vn ", hasCover ? "" : "-vn "))
                    .append("-c:a libmp3lame -b:a ").append(bitrateKbps)
                    .append("k -id3v2_version 3 ")
                    .append("-metadata title=").append(q(meta(title))).append(' ')
                    .append("-metadata artist=").append(q(meta(resolvedArtist))).append(' ')
                    .append(q(output.getAbsolutePath()));

            final FFmpegSession session = FFmpegKit.execute(command.toString());
            if (!ReturnCode.isSuccess(session.getReturnCode()) || !output.exists()) {
                throw new IOException("MP3 conversion failed");
            }

            update("Saving MP3…");
            publish(output, title + ".mp3");
            done(title + ".mp3 saved");
        } catch (final Exception e) {
            done("MP3 failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } finally {
            source.delete();
            cover.delete();
            output.delete();
            stopSelf(startId);
        }
    }

    private boolean downloadCover(final String url, final File out) {
        if (url.isEmpty()) return false;
        try (Response response = Net.HTTP.newCall(new Request.Builder()
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

    private void publish(final File file, final String displayName) throws IOException {
        final ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg");
        values.put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/YTDL");
        values.put(MediaStore.Audio.Media.IS_PENDING, 1);
        final Uri uri = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Couldn't create MP3 in MediaStore");
        try (InputStream in = new FileInputStream(file);
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
    }

    private void foreground(final String text) {
        final Notification notification = notification(text, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION, notification);
        }
    }

    private void update(final String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(text, true));
    }

    private void done(final String text) {
        stopForeground(STOP_FOREGROUND_DETACH);
        getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(text, false));
    }

    private Notification notification(final String text, final boolean ongoing) {
        final PendingIntent tap = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(ongoing ? android.R.drawable.stat_sys_download
                        : android.R.drawable.stat_sys_download_done)
                .setContentTitle("MP3 export")
                .setContentText(text)
                .setContentIntent(tap)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .build();
    }

    private static String q(final String value) {
        return "'" + value.replace("'", "") + "'";
    }

    private static String meta(final String value) {
        return safe(value).replace("\n", " ").replace("\r", " ");
    }

    private static int normalizeBitrate(final int value) {
        if (value >= 256) return 320;
        if (value <= 160) return 128;
        return 192;
    }

    private static String safe(final String value) { return value == null ? "" : value; }
}
