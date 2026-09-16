package com.example.ytdl;

import org.schabi.newpipe.extractor.InfoItem;
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

        Result(final String title, final String url, final String source) {
            this.title = title;
            this.url = url;
            this.source = source;
        }
    }

    public static List<Result> youtube(final String query) throws Exception {
        return search(ServiceList.YouTube, query, "YouTube");
    }

    public static List<Result> soundCloud(final String query) throws Exception {
        return search(ServiceList.SoundCloud, query, "SoundCloud");
    }

    private static List<Result> search(final StreamingService service, final String query,
                                       final String label) throws Exception {
        Net.ensureExtractor();
        final SearchExtractor extractor = service.getSearchExtractor(query);
        extractor.fetchPage();
        final SearchInfo info = SearchInfo.getInfo(extractor);
        final List<Result> out = new ArrayList<>();
        for (final InfoItem item : info.getRelatedItems()) {
            if (item instanceof StreamInfoItem) {
                final StreamInfoItem stream = (StreamInfoItem) item;
                out.add(new Result(stream.getName(), stream.getUrl(), label));
                if (out.size() >= 20) break;
            }
        }
        return out;
    }
}
