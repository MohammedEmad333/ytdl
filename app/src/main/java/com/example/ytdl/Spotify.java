package com.example.ytdl;

import org.json.JSONObject;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Resolves a public Spotify track link to a matching YouTube stream.
 *
 * Spotify links are used only as metadata. The app never attempts to bypass Spotify DRM or
 * download Spotify's protected media stream. Instead it reads the public track title and uses
 * NewPipeExtractor's YouTube search, after which the existing download pipeline takes over.
 */
public final class Spotify {

    private static final Pattern HTML_TITLE = Pattern.compile(
            "(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern OG_TITLE = Pattern.compile(
            "(?is)<meta[^>]+property=[\\\"']og:title[\\\"'][^>]+content=[\\\"'](.*?)[\\\"'][^>]*>");
    private static final Pattern OG_TITLE_REVERSED = Pattern.compile(
            "(?is)<meta[^>]+content=[\\\"'](.*?)[\\\"'][^>]+property=[\\\"']og:title[\\\"'][^>]*>");
    private static final Pattern OG_DESCRIPTION = Pattern.compile(
            "(?is)<meta[^>]+property=[\\\"']og:description[\\\"'][^>]+content=[\\\"'](.*?)[\\\"'][^>]*>");
    private static final Pattern OG_DESCRIPTION_REVERSED = Pattern.compile(
            "(?is)<meta[^>]+content=[\\\"'](.*?)[\\\"'][^>]+property=[\\\"']og:description[\\\"'][^>]*>");

    private Spotify() {
    }

    public static boolean isSpotifyUrl(final String value) {
        if (value == null) {
            return false;
        }
        final String lower = value.toLowerCase(Locale.US);
        return lower.startsWith("https://open.spotify.com/")
                || lower.startsWith("http://open.spotify.com/")
                || lower.startsWith("https://spotify.link/")
                || lower.startsWith("http://spotify.link/");
    }

    public static Match resolve(final String inputUrl) throws Exception {
        final Page page = fetchPage(inputUrl);
        final String finalUrl = page.finalUrl;

        if (!isTrackUrl(finalUrl)) {
            throw new IOException("Spotify albums and playlists are not supported yet; share an individual track.");
        }

        String title = meta(page.html, OG_TITLE, OG_TITLE_REVERSED);
        String description = meta(page.html, OG_DESCRIPTION, OG_DESCRIPTION_REVERSED);

        if (title.isEmpty()) {
            final Matcher titleMatcher = HTML_TITLE.matcher(page.html);
            if (titleMatcher.find()) {
                title = decode(titleMatcher.group(1));
            }
        }

        if (title.isEmpty()) {
            title = oEmbedTitle(finalUrl);
        }

        title = cleanTitle(title);
        description = cleanDescription(description);

        if (title.isEmpty()) {
            throw new IOException("Spotify did not expose the track title.");
        }

        final String query = buildQuery(title, description);
        final SearchExtractor extractor = ServiceList.YouTube.getSearchExtractor(query);
        extractor.fetchPage();
        final SearchInfo results = SearchInfo.getInfo(extractor);

        for (final InfoItem item : results.getRelatedItems()) {
            if (item instanceof StreamInfoItem) {
                final StreamInfoItem stream = (StreamInfoItem) item;
                return new Match(title, query, stream.getUrl(), stream.getName());
            }
        }

        throw new IOException("No matching YouTube result was found for “" + title + "”.");
    }

    private static boolean isTrackUrl(final String url) {
        final String lower = url.toLowerCase(Locale.US);
        return lower.contains("open.spotify.com/track/");
    }

    private static Page fetchPage(final String url) throws IOException {
        final Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", Net.USER_AGENT)
                .build();

        try (Response response = Net.HTTP.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Spotify returned HTTP " + response.code());
            }
            final ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Spotify returned an empty page.");
            }
            return new Page(response.request().url().toString(), body.string());
        }
    }

    private static String oEmbedTitle(final String spotifyUrl) {
        try {
            final String encoded = URLEncoder.encode(spotifyUrl, StandardCharsets.UTF_8.name());
            final Request request = new Request.Builder()
                    .url("https://open.spotify.com/oembed?url=" + encoded)
                    .header("User-Agent", Net.USER_AGENT)
                    .build();
            try (Response response = Net.HTTP.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return "";
                }
                return decode(new JSONObject(response.body().string()).optString("title", ""));
            }
        } catch (final Exception ignored) {
            return "";
        }
    }

    private static String meta(final String html, final Pattern normal, final Pattern reversed) {
        Matcher matcher = normal.matcher(html);
        if (matcher.find()) {
            return decode(matcher.group(1));
        }
        matcher = reversed.matcher(html);
        return matcher.find() ? decode(matcher.group(1)) : "";
    }

    private static String buildQuery(final String title, final String description) {
        if (description.isEmpty()) {
            return title + " official audio";
        }

        // Spotify descriptions commonly start with "Listen to <track> on Spotify. <artist> · ...".
        // Keep only a short artist-like suffix so search doesn't get polluted by marketing copy.
        String extra = description
                .replace("Listen to " + title + " on Spotify.", "")
                .replace("Listen to " + title + " on Spotify", "")
                .trim();
        final int dot = extra.indexOf('·');
        if (dot > 0) {
            extra = extra.substring(0, dot).trim();
        }
        if (extra.length() > 100) {
            extra = extra.substring(0, 100).trim();
        }
        return extra.isEmpty() ? title + " official audio" : title + " " + extra + " official audio";
    }

    private static String cleanTitle(final String raw) {
        String value = decode(raw).trim();
        value = value.replaceAll("(?i)\\s*[|–-]\\s*Spotify\\s*$", "").trim();
        value = value.replaceAll("(?i)\\s*-\\s*song and lyrics by\\s+", " ").trim();
        return value;
    }

    private static String cleanDescription(final String raw) {
        return decode(raw).replaceAll("\\s+", " ").trim();
    }

    private static String decode(final String raw) {
        if (raw == null) {
            return "";
        }
        return raw
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    public static final class Match {
        public final String spotifyTitle;
        public final String query;
        public final String youtubeUrl;
        public final String youtubeTitle;

        Match(final String spotifyTitle, final String query,
              final String youtubeUrl, final String youtubeTitle) {
            this.spotifyTitle = spotifyTitle;
            this.query = query;
            this.youtubeUrl = youtubeUrl;
            this.youtubeTitle = youtubeTitle;
        }
    }

    private static final class Page {
        final String finalUrl;
        final String html;

        Page(final String finalUrl, final String html) {
            this.finalUrl = finalUrl;
            this.html = html;
        }
    }
}
