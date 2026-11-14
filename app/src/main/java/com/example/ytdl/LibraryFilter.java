package com.example.ytdl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/** Pure filtering and ordering for the saved/failed library, independent of Android views. */
final class LibraryFilter {
    static final int ALL = 0;
    static final int VIDEO = 1;
    static final int AUDIO = 2;
    static final int SUBTITLES = 3;
    static final int NEEDS_ATTENTION = 4;
    static final int FAVORITES = 5;

    static final int NEWEST = 0;
    static final int OLDEST = 1;
    static final int TITLE = 2;

    private LibraryFilter() {}

    static List<DownloadService.Task> select(List<DownloadService.Task> all,
                                              String search, int category, int order) {
        return select(all, search, category, order, task -> false);
    }

    static List<DownloadService.Task> select(List<DownloadService.Task> all,
                                              String search, int category, int order,
                                              Predicate<DownloadService.Task> favorites) {
        final String query = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        final List<DownloadService.Task> result = new ArrayList<>();
        for (DownloadService.Task task : all) {
            if (task == null || task.state == null || !task.state.finished()) continue;
            if (!matchesCategory(task, category, favorites)) continue;
            final String title = task.title == null ? "" : task.title;
            final String artist = task.artist == null ? "" : task.artist;
            final String label = task.spec == null ? "" : task.spec.label;
            if (!query.isEmpty()
                    && !title.toLowerCase(Locale.ROOT).contains(query)
                    && !artist.toLowerCase(Locale.ROOT).contains(query)
                    && !label.toLowerCase(Locale.ROOT).contains(query)) continue;
            result.add(task);
        }
        if (order == OLDEST) {
            result.sort(Comparator.comparingLong(t -> t.id));
        } else if (order == TITLE) {
            result.sort((a, b) -> {
                final String at = a.title == null ? "" : a.title;
                final String bt = b.title == null ? "" : b.title;
                return at.compareToIgnoreCase(bt);
            });
        } else {
            result.sort((a, b) -> Long.compare(b.id, a.id));
        }
        return result;
    }

    private static boolean matchesCategory(DownloadService.Task task, int category,
                                           Predicate<DownloadService.Task> favorites) {
        if (category == FAVORITES) return task.state == DownloadService.State.DONE
                && favorites != null && favorites.test(task);
        if (category == ALL) return true;
        if (category == NEEDS_ATTENTION) {
            return task.state == DownloadService.State.FAILED
                    || task.state == DownloadService.State.CANCELLED;
        }
        if (task.state != DownloadService.State.DONE) return false;
        final String mime = task.mimeType == null
                ? "" : task.mimeType.toLowerCase(Locale.ROOT);
        if (category == VIDEO) return mime.startsWith("video/");
        if (category == AUDIO) return mime.startsWith("audio/")
                || task.spec != null && task.spec.kind == DownloadService.Kind.MP3;
        if (category == SUBTITLES) return task.spec != null
                && task.spec.kind == DownloadService.Kind.SUBTITLE
                || mime.startsWith("text/");
        return false;
    }
}
