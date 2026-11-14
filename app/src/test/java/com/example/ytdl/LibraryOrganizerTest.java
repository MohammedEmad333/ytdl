package com.example.ytdl;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class LibraryOrganizerTest {
    private static DownloadService.Task saved(String page, String uri, String mime) {
        final DownloadService.Task t = new DownloadService.Task(page, "Sample",
                DownloadService.Spec.muxed(720, "720p"));
        t.state = DownloadService.State.DONE;
        t.outputUri = uri;
        t.mimeType = mime;
        return t;
    }

    @Test public void sourcesUseExactHostsNotLookalikes() {
        assertEquals(LibraryOrganizer.YOUTUBE, LibraryOrganizer.sourceOf(saved(
                "https://m.youtube.com/watch?v=123", null, "video/mp4")));
        assertEquals(LibraryOrganizer.YOUTUBE, LibraryOrganizer.sourceOf(saved(
                "https://youtu.be/abc", null, "video/mp4")));
        assertEquals(LibraryOrganizer.SOUNDCLOUD, LibraryOrganizer.sourceOf(saved(
                "https://soundcloud.com/artist/track", null, "audio/mp4")));
        assertEquals(LibraryOrganizer.X, LibraryOrganizer.sourceOf(saved(
                "https://x.com/user/status/123", null, "video/mp4")));
        assertEquals(LibraryOrganizer.SPOTIFY, LibraryOrganizer.sourceOf(saved(
                "https://open.spotify.com/track/123", null, "audio/mpeg")));
        assertEquals(LibraryOrganizer.LOCAL, LibraryOrganizer.sourceOf(saved(
                "local-import://video/52", null, "video/mp4")));
        assertEquals(LibraryOrganizer.OTHER, LibraryOrganizer.sourceOf(saved(
                "https://youtube.com.attacker.example/watch", null, "video/mp4")));
        assertEquals(LibraryOrganizer.OTHER, LibraryOrganizer.sourceOf(saved(
                "file:///sdcard/video.mp4", null, "video/mp4")));
        assertEquals(LibraryOrganizer.OTHER, LibraryOrganizer.sourceOf(null));
    }

    @Test public void identicalDownloadMetadataFlagsBothSavedCopies() {
        final String page = "https://www.youtube.com/watch?v=abc";
        final DownloadService.Task a = saved(page,
                "content://media/external/downloads/501", "video/mp4");
        final DownloadService.Task b = saved(page,
                "content://media/external/downloads/502", "video/mp4");
        final Set<Long> duplicates = LibraryOrganizer.possibleDuplicateIds(
                Arrays.asList(a, b));
        assertEquals(2, duplicates.size());
        assertTrue(duplicates.contains(a.id));
        assertTrue(duplicates.contains(b.id));
    }

    @Test public void formatAndFileIdentityMustMatchConservatively() {
        final String page = "https://youtu.be/test";
        final DownloadService.Task a = saved(page,
                "content://media/external/downloads/601", "video/mp4");
        final DownloadService.Task b = saved(page,
                "content://media/external/downloads/602", "audio/mp4");
        assertTrue(LibraryOrganizer.possibleDuplicateIds(Arrays.asList(a, b)).isEmpty());

        b.mimeType = "video/mp4";
        b.outputUri = a.outputUri;
        assertTrue(LibraryOrganizer.possibleDuplicateIds(Arrays.asList(a, b)).isEmpty());

        b.outputUri = "content://other.provider/external/downloads/602";
        assertTrue(LibraryOrganizer.possibleDuplicateIds(Arrays.asList(a, b)).isEmpty());
    }

    @Test public void unfinishedAndMissingMediaAreNeverCandidates() {
        final DownloadService.Task done = saved("https://youtu.be/test",
                "content://media/external/downloads/701", "video/mp4");
        final DownloadService.Task failed = saved("https://youtu.be/test",
                "content://media/external/downloads/702", "video/mp4");
        failed.state = DownloadService.State.FAILED;
        assertTrue(LibraryOrganizer.possibleDuplicateIds(
                Arrays.asList(done, failed, null)).isEmpty());
        failed.state = DownloadService.State.DONE;
        failed.outputUri = null;
        assertTrue(LibraryOrganizer.possibleDuplicateIds(
                Arrays.asList(done, failed)).isEmpty());
        assertTrue(LibraryOrganizer.possibleDuplicateIds(null).isEmpty());
        assertTrue(LibraryOrganizer.possibleDuplicateIds(Collections.emptyList()).isEmpty());
    }

    @Test public void importedFilesNeedMatchingNameAndSize() {
        final DownloadService.Task a = saved("local-import://video/1",
                "content://media/external/video/media/801", "video/mp4");
        final DownloadService.Task b = saved("local-import://video/2",
                "content://media/external/video/media/802", "video/mp4");
        a.title = "Clip.MP4";
        b.title = "clip.mp4";
        a.done = 1024;
        b.done = 1024;
        assertEquals(2, LibraryOrganizer.possibleDuplicateIds(Arrays.asList(a, b)).size());
        b.done = 2048;
        assertTrue(LibraryOrganizer.possibleDuplicateIds(Arrays.asList(a, b)).isEmpty());
        b.done = 1024;
        b.title = "Other.mp4";
        assertTrue(LibraryOrganizer.possibleDuplicateIds(Arrays.asList(a, b)).isEmpty());
    }
}
