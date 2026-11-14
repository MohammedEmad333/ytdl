package com.example.ytdl;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class LibraryFilterTest {
    private static DownloadService.Task task(String title, DownloadService.Kind kind,
                                             String mime, DownloadService.State state) {
        final DownloadService.Spec spec = kind == DownloadService.Kind.SUBTITLE
                ? DownloadService.Spec.subtitle("en", "SRT")
                : kind == DownloadService.Kind.MP3
                ? DownloadService.Spec.mp3(192) : DownloadService.Spec.muxed(720, "720p");
        final DownloadService.Task t = new DownloadService.Task(
                "https://example.com/media", title, spec);
        t.state = state;
        t.mimeType = mime;
        return t;
    }

    @Test public void completedVideosAndAudioAreSeparated() {
        final DownloadService.Task video = task("Movie", DownloadService.Kind.MUXED,
                "video/mp4", DownloadService.State.DONE);
        final DownloadService.Task audio = task("Song", DownloadService.Kind.MP3,
                "audio/mpeg", DownloadService.State.DONE);
        final List<DownloadService.Task> all = Arrays.asList(video, audio);
        assertEquals(video, LibraryFilter.select(all, "", LibraryFilter.VIDEO,
                LibraryFilter.NEWEST).get(0));
        assertEquals(audio, LibraryFilter.select(all, "", LibraryFilter.AUDIO,
                LibraryFilter.NEWEST).get(0));
        assertEquals(1, LibraryFilter.select(all, "", LibraryFilter.VIDEO,
                LibraryFilter.NEWEST).size());
    }

    @Test public void searchWorksWithCaseAndArtist() {
        final DownloadService.Task item = task("My Music", DownloadService.Kind.MP3,
                "audio/mpeg", DownloadService.State.DONE);
        item.artist = "Singer One";
        assertEquals(1, LibraryFilter.select(Arrays.asList(item), "sInGeR",
                LibraryFilter.ALL, LibraryFilter.NEWEST).size());
        assertEquals(1, LibraryFilter.select(Arrays.asList(item), "MUSIC",
                LibraryFilter.ALL, LibraryFilter.NEWEST).size());
        assertTrue(LibraryFilter.select(Arrays.asList(item), "not here",
                LibraryFilter.ALL, LibraryFilter.NEWEST).isEmpty());
    }

    @Test public void failuresAppearUnderNeedsAttentionButNotSavedCategories() {
        final DownloadService.Task failed = task("Failed", DownloadService.Kind.MUXED,
                "video/mp4", DownloadService.State.FAILED);
        assertEquals(1, LibraryFilter.select(Arrays.asList(failed), "",
                LibraryFilter.NEEDS_ATTENTION, LibraryFilter.NEWEST).size());
        assertTrue(LibraryFilter.select(Arrays.asList(failed), "",
                LibraryFilter.VIDEO, LibraryFilter.NEWEST).isEmpty());
    }

    @Test public void pendingItemsDoNotAppearInHistory() {
        final DownloadService.Task waiting = task("Pending", DownloadService.Kind.MUXED,
                "video/mp4", DownloadService.State.QUEUED);
        assertTrue(LibraryFilter.select(Arrays.asList(waiting), "",
                LibraryFilter.ALL, LibraryFilter.NEWEST).isEmpty());
    }

    @Test public void missingSpecDoesNotCrashAudioOrSubtitleFilters() {
        final DownloadService.Task unknown = task("Incomplete", DownloadService.Kind.MUXED,
                "application/octet-stream", DownloadService.State.DONE);
        unknown.mimeType = null;
        // Metadata from an old or partially restored item should never crash Library.
        // Spec is final by construction, so use an empty incoming list and null entries
        // for malformed records that the parser could otherwise skip.
        assertTrue(LibraryFilter.select(Arrays.asList((DownloadService.Task) null), "",
                LibraryFilter.AUDIO, LibraryFilter.NEWEST).isEmpty());
        assertTrue(LibraryFilter.select(Arrays.asList((DownloadService.Task) null), "",
                LibraryFilter.SUBTITLES, LibraryFilter.NEWEST).isEmpty());
        assertTrue(LibraryFilter.select(Arrays.asList(unknown), "",
                LibraryFilter.AUDIO, LibraryFilter.NEWEST).isEmpty());
        assertTrue(LibraryFilter.select(Arrays.asList(unknown), "",
                LibraryFilter.SUBTITLES, LibraryFilter.NEWEST).isEmpty());
    }

    @Test public void missingStateIsSafelySkipped() {
        final DownloadService.Task malformed = task("Incomplete", DownloadService.Kind.MUXED,
                "video/mp4", DownloadService.State.DONE);
        malformed.state = null;
        assertTrue(LibraryFilter.select(Arrays.asList(malformed), "",
                LibraryFilter.ALL, LibraryFilter.NEWEST).isEmpty());
    }

    @Test public void textMimeIsCategorizedAsSubtitle() {
        final DownloadService.Task caption = task("Caption", DownloadService.Kind.MUXED,
                "text/vtt", DownloadService.State.DONE);
        assertEquals(1, LibraryFilter.select(Arrays.asList(caption), "",
                LibraryFilter.SUBTITLES, LibraryFilter.NEWEST).size());
    }

    @Test public void orderChangesWithoutMutatingOriginalQueue() {
        final DownloadService.Task z = task("Zebra", DownloadService.Kind.MP3,
                "audio/mpeg", DownloadService.State.DONE);
        final DownloadService.Task a = task("Alpha", DownloadService.Kind.MP3,
                "audio/mpeg", DownloadService.State.DONE);
        final List<DownloadService.Task> all = Arrays.asList(z, a);
        assertEquals(z, LibraryFilter.select(all, "", 0, LibraryFilter.OLDEST).get(0));
        assertEquals(a, LibraryFilter.select(all, "", 0, LibraryFilter.NEWEST).get(0));
        assertEquals(a, LibraryFilter.select(all, "", 0, LibraryFilter.TITLE).get(0));
        assertEquals(z, all.get(0));
    }
}
