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

    @Test public void skipControlsClampAtStartAndEnd() {
        assertEquals(0, LocalMediaPlayerActivity.skipTarget(4_000, 60_000, -10_000));
        assertEquals(60_000, LocalMediaPlayerActivity.skipTarget(56_000, 60_000, 10_000));
        assertEquals(20_000, LocalMediaPlayerActivity.skipTarget(10_000, 60_000, 10_000));
        assertEquals(0, LocalMediaPlayerActivity.skipTarget(-1, 0, -10_000));
    }

    @Test public void skipControlsDoNotOverflowLongDurations() {
        assertEquals(Integer.MAX_VALUE, LocalMediaPlayerActivity.skipTarget(
                Integer.MAX_VALUE - 5, Integer.MAX_VALUE, 10_000));
        assertEquals(0, LocalMediaPlayerActivity.skipTarget(
                0, Integer.MAX_VALUE, Integer.MIN_VALUE));
        assertEquals(1_000, LocalMediaPlayerActivity.skipTarget(
                Integer.MAX_VALUE, 1_000, 10_000));
    }


    @Test public void playbackPositionIsStoredOnlyForSavedLocalMedia() {
        final Context context = RuntimeEnvironment.getApplication();
        final DownloadService.Task item = saved(
                "content://media/external/video/media/751", "video/mp4");
        assertEquals(0, LocalMediaPlayerActivity.savedPosition(context, item));
        LocalMediaPlayerActivity.rememberPosition(context, item, 42_000, 120_000);
        assertEquals(42_000, LocalMediaPlayerActivity.savedPosition(context, item));

        item.state = DownloadService.State.FAILED;
        assertEquals(0, LocalMediaPlayerActivity.savedPosition(context, item));
        LocalMediaPlayerActivity.rememberPosition(context, item, 90_000, 120_000);
        item.state = DownloadService.State.DONE;
        assertEquals(42_000, LocalMediaPlayerActivity.savedPosition(context, item));

        item.outputUri = "https://example.org/media.mp4";
        assertEquals(0, LocalMediaPlayerActivity.savedPosition(context, item));
        LocalMediaPlayerActivity.rememberPosition(context, item, 30_000, 120_000);
        item.outputUri = "content://media/external/video/media/751";
        assertEquals(42_000, LocalMediaPlayerActivity.savedPosition(context, item));
        LocalMediaPlayerActivity.rememberPosition(context, item, 0, 120_000);
    }

    @Test public void playbackPositionNeverLeaksToReplacedMedia() {
        final Context context = RuntimeEnvironment.getApplication();
        final DownloadService.Task item = saved(
                "content://media/external/downloads/752", "audio/mp4");
        LocalMediaPlayerActivity.rememberPosition(context, item, 27_000, 100_000);
        assertEquals(27_000, LocalMediaPlayerActivity.savedPosition(context, item));
        item.outputUri = "content://media/external/downloads/753";
        assertEquals(0, LocalMediaPlayerActivity.savedPosition(context, item));
        LocalMediaPlayerActivity.rememberPosition(context, item, 0, 100_000);
        item.outputUri = "content://media/external/downloads/752";
        assertEquals(0, LocalMediaPlayerActivity.savedPosition(context, item));
    }

    @Test public void playbackPositionIgnoresFirstSecondsAndCompletedFiles() {
        final Context context = RuntimeEnvironment.getApplication();
        final DownloadService.Task item = saved(
                "content://media/external/audio/media/754", "audio/mpeg");
        LocalMediaPlayerActivity.rememberPosition(context, item, 4_999, 100_000);
        assertEquals(0, LocalMediaPlayerActivity.savedPosition(context, item));
        LocalMediaPlayerActivity.rememberPosition(context, item, 12_000, 100_000);
        assertEquals(12_000, LocalMediaPlayerActivity.savedPosition(context, item));
        LocalMediaPlayerActivity.rememberPosition(context, item, 96_000, 100_000);
        assertEquals(0, LocalMediaPlayerActivity.savedPosition(context, item));
        LocalMediaPlayerActivity.rememberPosition(context, item, 25_000, 100_000);
        LocalMediaPlayerActivity.rememberPosition(context, item, 0, 0);
        assertEquals(0, LocalMediaPlayerActivity.savedPosition(context, item));
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

    @Test public void favoritesRejectRemoteAndMalformedUris() {
        final Context context = RuntimeEnvironment.getApplication();
        final DownloadService.Task remote = saved("https://example.org/media.mp4", "video/mp4");
        assertFalse(LibraryFavorites.toggle(context, remote));
        assertFalse(LibraryFavorites.isFavorite(context, remote));
        final DownloadService.Task wrongAuthority = saved(
                "content://untrusted.provider/external/video/media/52", "video/mp4");
        assertFalse(LibraryFavorites.toggle(context, wrongAuthority));
        final DownloadService.Task query = saved(
                "content://media/external/video/media/52?unknown=true", "video/mp4");
        assertFalse(LibraryFavorites.toggle(context, query));
        final DownloadService.Task valid = saved(
                "content://media/external/downloads/52", "video/mp4");
        assertTrue(LibraryFavorites.toggle(context, valid));
        assertTrue(LibraryFavorites.isFavorite(context, valid));
        assertFalse(LibraryFavorites.toggle(context, valid));
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
