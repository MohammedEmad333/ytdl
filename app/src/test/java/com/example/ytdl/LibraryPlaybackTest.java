package com.example.ytdl;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class LibraryPlaybackTest {
    private DownloadService.Task saved(String url, String mime) {
        final DownloadService.Task task = new DownloadService.Task(
                "https://example.org/watch/1", "Local video",
                DownloadService.Spec.muxed(720, "720p"));
        task.state = DownloadService.State.DONE;
        task.outputUri = url;
        task.mimeType = mime;
        return task;
    }

    @Test public void playsOnlyCompletedLocalMediaStoreVideos() {
        final DownloadService.Task video = saved(
                "content://media/external/video/media/123", "video/mp4");
        assertTrue(LocalMediaPlayerActivity.canPlay(video));
        assertTrue(LocalMediaPlayerActivity.canPlay(saved(
                "content://media/external/downloads/321", "video/mp4")));
        assertFalse(LocalMediaPlayerActivity.canPlay(saved(
                "https://example.org/remote.mp4", "video/mp4")));
        assertFalse(LocalMediaPlayerActivity.canPlay(saved(
                "content://other.example/file/12", "video/mp4")));
        assertTrue(LocalMediaPlayerActivity.canPlay(saved(
                "content://media/external/audio/media/12", "audio/mpeg")));
        assertTrue(LocalMediaPlayerActivity.canPlay(saved(
                "content://media/external/downloads/12", "audio/mp4")));
        assertFalse(LocalMediaPlayerActivity.canPlay(saved(
                "content://media/external/video/media/12", "audio/mpeg")));
        assertFalse(LocalMediaPlayerActivity.canPlay(saved(
                "content://media/external/audio/media/12", "video/mp4")));
        assertFalse(LocalMediaPlayerActivity.canPlay(saved(
                "content://media/external/downloads/12", "text/plain")));
        assertFalse(LocalMediaPlayerActivity.canPlay(saved(
                "content://media/external/downloads/12?unsafe=true", "video/mp4")));
        video.state = DownloadService.State.QUEUED;
        assertFalse(LocalMediaPlayerActivity.canPlay(video));
    }

    @Test public void internalPlayerIntentCarriesOnlyTaskId() {
        final DownloadService.Task video = saved(
                "content://media/external/video/media/123", "video/mp4");
        final HistoryActivity source = Robolectric.buildActivity(HistoryActivity.class)
                .create().start().resume().get();
        final Intent intent = LocalMediaPlayerActivity.intentFor(source, video);
        assertEquals(LocalMediaPlayerActivity.class.getName(),
                intent.getComponent().getClassName());
        assertEquals(video.id, intent.getLongExtra(
                LocalMediaPlayerActivity.EXTRA_TASK_ID, -1));
        assertNull(intent.getData());
        assertEquals(1, intent.getExtras().size());
    }

    @Test public void audioPlayerIntentCarriesNoUntrustedMediaUrl() {
        final DownloadService.Task audio = saved(
                "content://media/external/audio/media/123", "audio/mpeg");
        final HistoryActivity source = Robolectric.buildActivity(HistoryActivity.class)
                .create().start().resume().get();
        final Intent intent = LocalMediaPlayerActivity.intentFor(source, audio);
        assertEquals(audio.id, intent.getLongExtra(
                LocalMediaPlayerActivity.EXTRA_TASK_ID, -1));
        assertNull(intent.getData());
        assertEquals(1, intent.getExtras().size());
    }

    @Test public void playbackTimeFormattingIsNonNegativeAndSupportsHours() {
        assertEquals("0:00", LocalMediaPlayerActivity.formatTime(-1000));
        assertEquals("0:09", LocalMediaPlayerActivity.formatTime(9_900));
        assertEquals("1:05", LocalMediaPlayerActivity.formatTime(65_000));
        assertEquals("1:01:01", LocalMediaPlayerActivity.formatTime(3_661_000));
    }

    @Test public void favoritesMatchExactSavedFileAndRejectUnfinishedTasks() {
        final Context context = RuntimeEnvironment.getApplication();
        final DownloadService.Task video = saved(
                "content://media/external/video/media/123", "video/mp4");
        assertFalse(LibraryFavorites.isFavorite(context, video));
        assertTrue(LibraryFavorites.toggle(context, video));
        assertTrue(LibraryFavorites.isFavorite(context, video));

        final DownloadService.Task notFinished = saved(
                "content://media/external/video/media/123", "video/mp4");
        notFinished.state = DownloadService.State.FAILED;
        assertFalse(LibraryFavorites.isFavorite(context, notFinished));
        assertFalse(LibraryFavorites.toggle(context, notFinished));

        video.outputUri = "content://media/external/video/media/124";
        assertFalse(LibraryFavorites.isFavorite(context, video));
        assertTrue(LibraryFavorites.toggle(context, video));
        assertTrue(LibraryFavorites.isFavorite(context, video));
        assertFalse(LibraryFavorites.toggle(context, video));
        assertFalse(LibraryFavorites.isFavorite(context, video));
    }

    @Test public void favoritesFilterRespectsSearchAndCompletion() {
        final DownloadService.Task favorite = saved(
                "content://media/external/video/media/123", "video/mp4");
        final DownloadService.Task other = saved(
                "content://media/external/video/media/124", "video/mp4");
        other.title = "Other";
        final DownloadService.Task failed = saved(
                "content://media/external/video/media/125", "video/mp4");
        failed.state = DownloadService.State.FAILED;
        final java.util.List<DownloadService.Task> all = Arrays.asList(favorite, other, failed);
        assertEquals(1, LibraryFilter.select(all, "", LibraryFilter.FAVORITES,
                LibraryFilter.NEWEST, task -> task == favorite || task == failed).size());
        assertEquals(favorite, LibraryFilter.select(all, "local",
                LibraryFilter.FAVORITES, LibraryFilter.NEWEST,
                task -> task == favorite).get(0));
        assertTrue(LibraryFilter.select(all, "other", LibraryFilter.FAVORITES,
                LibraryFilter.NEWEST, task -> task == favorite).isEmpty());
    }
}
