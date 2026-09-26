package com.example.ytdl;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.List;

/** Lightweight search facade used by the fetch screen. */
public final class MediaSearch {
    private MediaSearch() {}

    public static final class Result {
        public final String title;
        public final String url;
        public final String source;
        public final String uploader;
        public final long duration;
        public final String thumbnailUrl;

        Result(final String title, final String url, final String source,
               final String uploader, final long duration, final String thumbnailUrl) {
            this.title = title;
            this.url = url;
            this.source = source;
            this.uploader = uploader == null ? "" : uploader;
            this.duration = duration;
            this.thumbnailUrl = thumbnailUrl == null ? "" : thumbnailUrl;
        }
    }

    public static final class Session {
        private final SearchExtractor extractor;
        private final String label;
        private Page nextPage;

        private Session(final SearchExtractor extractor, final String label) {
            this.extractor = extractor;
            this.label = label;
        }

        public List<Result> first() throws Exception {
            extractor.fetchPage();
            final ListExtractor.InfoItemsPage<InfoItem> page = extractor.getInitialPage();
            nextPage = page.getNextPage();
            return convert(page.getItems(), label);
        }

        public boolean hasMore() {
            return nextPage != null;
        }

        public List<Result> next() throws Exception {
            if (nextPage == null) return new ArrayList<>();
            final ListExtractor.InfoItemsPage<InfoItem> page = extractor.getPage(nextPage);
            nextPage = page.getNextPage();
            return convert(page.getItems(), label);
        }
    }

    public static Session youtubeSession(final String query) throws Exception {
        Net.ensureExtractor();
        return new Session(ServiceList.YouTube.getSearchExtractor(query), "YouTube");
    }

    public static Session soundCloudSession(final String query) throws Exception {
        Net.ensureExtractor();
        return new Session(ServiceList.SoundCloud.getSearchExtractor(query), "SoundCloud");
    }

    public static List<Result> youtube(final String query) throws Exception {
        return youtubeSession(query).first();
    }

    public static List<Result> soundCloud(final String query) throws Exception {
        return soundCloudSession(query).first();
    }

    private static List<Result> convert(final List<InfoItem> items, final String label) {
        final List<Result> out = new ArrayList<>();
        for (final InfoItem item : items) {
            if (item instanceof StreamInfoItem) {
                final StreamInfoItem stream = (StreamInfoItem) item;
                String thumb = "";
                try {
                    if (stream.getThumbnails() != null && !stream.getThumbnails().isEmpty()) {
                        thumb = stream.getThumbnails().get(0).getUrl();
                    }
                } catch (final Exception ignored) {}
                out.add(new Result(stream.getName(), stream.getUrl(), label,
                        stream.getUploaderName(), stream.getDuration(), thumb));
            }
        }
        return out;
    }
}
