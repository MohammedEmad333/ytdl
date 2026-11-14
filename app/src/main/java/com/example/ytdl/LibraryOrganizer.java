package com.example.ytdl;

import android.net.Uri;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Read-only library organization. Duplicate candidates are metadata matches, not
 * verified byte-for-byte duplicates. This class never opens or deletes media files.
 */
final class LibraryOrganizer {
    static final int ALL_SOURCES = 0;
    static final int YOUTUBE = 1;
    static final int SOUNDCLOUD = 2;
    static final int X = 3;
    static final int SPOTIFY = 4;
    static final int LOCAL = 5;
    static final int OTHER = 6;

    private LibraryOrganizer() {}

    static int sourceOf(DownloadService.Task task) {
        if (task == null || task.pageUrl == null) return OTHER;
        final String page = task.pageUrl.trim();
        if (page.startsWith("local-import://video/")) return LOCAL;
        try {
            final URI uri = new URI(page);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    && !"http".equalsIgnoreCase(uri.getScheme())) return OTHER;
            if (uri.getHost() == null) return OTHER;
            final String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.equals("youtu.be") || host.equals("youtube.com")
                    || host.endsWith(".youtube.com")) return YOUTUBE;
            if (host.equals("soundcloud.com") || host.endsWith(".soundcloud.com")) {
                return SOUNDCLOUD;
            }
            if (host.equals("x.com") || host.endsWith(".x.com")
                    || host.equals("twitter.com") || host.endsWith(".twitter.com")) {
                return X;
            }
            if (host.equals("spotify.link") || host.equals("spotify.com")
                    || host.endsWith(".spotify.com")) return SPOTIFY;
        } catch (URISyntaxException ignored) {
            // Bad legacy URLs are shown under Other, not sent to a server.
        }
        return OTHER;
    }

    /**
     * Return IDs of completed tasks that *may* represent duplicate files.
     *
     * Downloaded media: exact page URL, requested stream format and MIME type.
     * Imported video: same visible filename, byte size and MIME type.
     * Distinct MediaStore item URIs are required, because multiple queue records
     * referencing one file do not prove that two copies were saved.
     *
     * Hashing or deleting files would require separate explicit user approval.
     */
    static Set<Long> possibleDuplicateIds(List<DownloadService.Task> tasks) {
        if (tasks == null || tasks.isEmpty()) return Collections.emptySet();
        final Map<List<Object>, List<DownloadService.Task>> groups = new HashMap<>();
        for (DownloadService.Task task : tasks) {
            final List<Object> key = candidateKey(task);
            if (key != null) {
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(task);
            }
        }
        final Set<Long> result = new HashSet<>();
        for (List<DownloadService.Task> group : groups.values()) {
            final Set<String> uniqueFiles = new HashSet<>();
            for (DownloadService.Task item : group) uniqueFiles.add(item.outputUri);
            if (uniqueFiles.size() > 1) {
                for (DownloadService.Task item : group) result.add(item.id);
            }
        }
        return result;
    }

    private static List<Object> candidateKey(DownloadService.Task task) {
        if (task == null || task.id <= 0 || task.state != DownloadService.State.DONE
                || task.outputUri == null || task.outputUri.isEmpty()
                || task.mimeType == null || task.mimeType.trim().isEmpty()) return null;
        try {
            if (!PendingMediaLedger.isOwnedItemUri(Uri.parse(task.outputUri))) return null;
        } catch (RuntimeException invalid) {
            return null;
        }

        final String mime = task.mimeType.trim().toLowerCase(Locale.ROOT);
        if (sourceOf(task) == LOCAL) {
            // Import IDs are always different, so compare the original filename and
            // actual imported byte count. Equal metadata is only a suggestion.
            if (task.title == null || task.title.trim().isEmpty() || task.done <= 0
                    || !mime.startsWith("video/")) return null;
            return Arrays.asList("import", task.title.trim().toLowerCase(Locale.ROOT),
                    task.done, mime);
        }
        if (task.pageUrl == null || task.pageUrl.trim().isEmpty()
                || task.spec == null || sourceOf(task) == OTHER) return null;
        final DownloadService.Spec spec = task.spec;
        return Arrays.asList("download", task.pageUrl.trim(), spec.kind, spec.height,
                spec.bitrateKbps, spec.audioTrackId, spec.subtitleTag, mime);
    }
}
