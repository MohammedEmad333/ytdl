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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Resolves public Spotify metadata to matching YouTube sources. */
public final class Spotify {
    private static final int COLLECTION_CAP = 100;
    private static final Pattern HTML_TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern OG_TITLE = Pattern.compile("(?is)<meta[^>]+property=[\\\"']og:title[\\\"'][^>]+content=[\\\"'](.*?)[\\\"'][^>]*>");
    private static final Pattern OG_TITLE_REVERSED = Pattern.compile("(?is)<meta[^>]+content=[\\\"'](.*?)[\\\"'][^>]+property=[\\\"']og:title[\\\"'][^>]*>");
    private static final Pattern OG_DESCRIPTION = Pattern.compile("(?is)<meta[^>]+property=[\\\"']og:description[\\\"'][^>]+content=[\\\"'](.*?)[\\\"'][^>]*>");
    private static final Pattern OG_DESCRIPTION_REVERSED = Pattern.compile("(?is)<meta[^>]+content=[\\\"'](.*?)[\\\"'][^>]+property=[\\\"']og:description[\\\"'][^>]*>");
    private static final Pattern TRACK_URI_THEN_NAME = Pattern.compile("(?is)spotify:track:([A-Za-z0-9]+).{0,500}?\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern NAME_THEN_TRACK_URI = Pattern.compile("(?is)\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".{0,500}?spotify:track:([A-Za-z0-9]+)");
    private static final Pattern TRACK_LINK = Pattern.compile("(?is)https://open\\.spotify\\.com/track/([A-Za-z0-9]+)");

    private Spotify() {}

    public static boolean isSpotifyUrl(final String value) {
        if (value == null) return false;
        final String lower = value.toLowerCase(Locale.US);
        return lower.startsWith("https://open.spotify.com/")
                || lower.startsWith("http://open.spotify.com/")
                || lower.startsWith("https://spotify.link/")
                || lower.startsWith("http://spotify.link/");
    }

    public static Match resolve(final String inputUrl) throws Exception {
        final List<Match> all = resolveAll(inputUrl);
        if (all.isEmpty()) throw new IOException("Spotify returned no tracks.");
        return all.get(0);
    }

    public static List<Match> resolveAll(final String inputUrl) throws Exception {
        final Page page = fetchPage(inputUrl);
        final String finalUrl = page.finalUrl;
        if (isTrackUrl(finalUrl)) {
            final List<Match> one = new ArrayList<>();
            one.add(resolveTrackPage(finalUrl, page.html));
            return one;
        }
        if (!isCollectionUrl(finalUrl)) {
            throw new IOException("Supported Spotify links: track, album and playlist.");
        }

        final List<Track> tracks = extractCollection(finalUrl, page.html);
        if (tracks.isEmpty()) {
            throw new IOException("Spotify did not expose the album/playlist tracks.");
        }
        final List<Match> matches = new ArrayList<>();
        for (int i = 0; i < tracks.size() && i < COLLECTION_CAP; i++) {
            final Track track = tracks.get(i);
            try {
                final Track metadata = enrichTrack(track);
                matches.add(match(metadata.title, metadata.artist, metadata.coverUrl));
            } catch (final Exception ignored) {
                // One unavailable song should not discard the whole collection.
            }
        }
        if (matches.isEmpty()) throw new IOException("No matching YouTube sources were found.");
        return matches;
    }

    private static Match resolveTrackPage(final String finalUrl, final String html) throws Exception {
        String title = meta(html, OG_TITLE, OG_TITLE_REVERSED);
        String description = meta(html, OG_DESCRIPTION, OG_DESCRIPTION_REVERSED);
        if (title.isEmpty()) {
            final Matcher titleMatcher = HTML_TITLE.matcher(html);
            if (titleMatcher.find()) title = decode(titleMatcher.group(1));
        }
        if (title.isEmpty()) title = oEmbedTitle(finalUrl);
        title = cleanTitle(title);
        description = cleanDescription(description);
        if (title.isEmpty()) throw new IOException("Spotify did not expose the track title.");
        final String artist = artistFromDescription(title, description);
        final String coverUrl = meta(html, OG_IMAGE, OG_IMAGE_REVERSED);
        return match(title, artist, coverUrl);
    }

    private static Match match(final String title, final String artist) throws Exception {
        return match(title, artist, "");
    }

    private static Match match(final String title, final String artist, final String coverUrl) throws Exception {
        final String query = (artist.isEmpty() ? title : title + " " + artist) + " official audio";
        final SearchExtractor extractor = ServiceList.YouTube.getSearchExtractor(query);
        extractor.fetchPage();
        final SearchInfo results = SearchInfo.getInfo(extractor);

        StreamInfoItem best = null;
        int bestScore = Integer.MIN_VALUE;
        for (final InfoItem item : results.getRelatedItems()) {
            if (!(item instanceof StreamInfoItem)) continue;
            final StreamInfoItem stream = (StreamInfoItem) item;
            final int score = score(title, artist, stream.getName());
            if (score > bestScore) {
                bestScore = score;
                best = stream;
            }
        }
        if (best == null) throw new IOException("No matching YouTube result for “" + title + "”.");
        return new Match(title, artist, coverUrl, query, best.getUrl(), best.getName());
    }

    private static int score(final String title, final String artist, final String candidate) {
        final Set<String> wanted = tokens(title + " " + artist);
        final Set<String> got = tokens(candidate);
        int score = 0;
        for (final String token : wanted) if (got.contains(token)) score += token.length() >= 5 ? 4 : 2;
        final String lower = candidate.toLowerCase(Locale.US);
        if (lower.contains("official audio") || lower.contains("topic")) score += 8;
        if (lower.contains("official video")) score += 3;
        for (final String noisy : new String[]{"cover", "karaoke", "nightcore", "sped up", "slowed", "live", "remix"}) {
            if (lower.contains(noisy) && !title.toLowerCase(Locale.US).contains(noisy)) score -= 10;
        }
        return score;
    }

    private static Set<String> tokens(final String raw) {
        final Set<String> out = new HashSet<>();
        for (final String token : raw.toLowerCase(Locale.US).replaceAll("[^\\p{L}\\p{N}]+", " ").split("\\s+")) {
            if (token.length() > 1) out.add(token);
        }
        return out;
    }

    private static List<Track> extractCollection(final String finalUrl, final String originalHtml) throws IOException {
        final String embedUrl = finalUrl.replace("open.spotify.com/", "open.spotify.com/embed/");
        String html = originalHtml;
        try { html += "\n" + fetchPage(embedUrl).html; } catch (final Exception ignored) {}

        final List<Track> tracks = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        collect(tracks, seen, html, TRACK_URI_THEN_NAME, false);
        collect(tracks, seen, html, NAME_THEN_TRACK_URI, true);

        if (tracks.isEmpty()) {
            final Matcher links = TRACK_LINK.matcher(html);
            while (links.find() && tracks.size() < COLLECTION_CAP) {
                final String id = links.group(1);
                if (seen.add(id)) {
                    try {
                        final String url = "https://open.spotify.com/track/" + id;
                        final Page trackPage = fetchPage(url);
                        final Track track = trackMetadata(id, trackPage.html, "");
                        if (!track.title.isEmpty()) tracks.add(track);
                    } catch (final Exception ignored) {}
                }
            }
        }
        return tracks;
    }

    private static void collect(final List<Track> out, final Set<String> seen, final String html,
                                final Pattern pattern, final boolean nameFirst) {
        final Matcher matcher = pattern.matcher(html);
        while (matcher.find() && out.size() < COLLECTION_CAP) {
            final String id = nameFirst ? matcher.group(2) : matcher.group(1);
            final String name = decode(nameFirst ? matcher.group(1) : matcher.group(2));
            if (seen.add(id) && !name.trim().isEmpty()) out.add(new Track(id, cleanTitle(name), "", ""));
        }
    }

    private static Track enrichTrack(final Track track) {
        if (!track.artist.isEmpty() && !track.coverUrl.isEmpty()) return track;
        try {
            final Page page = fetchPage("https://open.spotify.com/track/" + track.id);
            return trackMetadata(track.id, page.html, track.title);
        } catch (final Exception ignored) {
            return track;
        }
    }

    private static Track trackMetadata(final String id, final String html, final String fallbackTitle) {
        String title = cleanTitle(meta(html, OG_TITLE, OG_TITLE_REVERSED));
        if (title.isEmpty()) title = fallbackTitle;
        final String description = cleanDescription(meta(html, OG_DESCRIPTION, OG_DESCRIPTION_REVERSED));
        final String artist = artistFromDescription(title, description);
        final String coverUrl = meta(html, OG_IMAGE, OG_IMAGE_REVERSED);
        return new Track(id, title, artist, coverUrl);
    }

    private static boolean isTrackUrl(final String url) {
        return url.toLowerCase(Locale.US).contains("open.spotify.com/track/");
    }

    private static boolean isCollectionUrl(final String url) {
        final String lower = url.toLowerCase(Locale.US);
        return lower.contains("open.spotify.com/album/") || lower.contains("open.spotify.com/playlist/");
    }

    private static Page fetchPage(final String url) throws IOException {
        final Request request = new Request.Builder().url(url).header("User-Agent", Net.USER_AGENT).build();
        try (Response response = Net.HTTP.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Spotify returned HTTP " + response.code());
            final ResponseBody body = response.body();
            if (body == null) throw new IOException("Spotify returned an empty page.");
            return new Page(response.request().url().toString(), body.string());
        }
    }

    private static String oEmbedTitle(final String spotifyUrl) {
        try {
            final String encoded = URLEncoder.encode(spotifyUrl, StandardCharsets.UTF_8.name());
            final Request request = new Request.Builder()
                    .url("https://open.spotify.com/oembed?url=" + encoded)
                    .header("User-Agent", Net.USER_AGENT).build();
            try (Response response = Net.HTTP.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return "";
                return decode(new JSONObject(response.body().string()).optString("title", ""));
            }
        } catch (final Exception ignored) { return ""; }
    }

    private static String artistFromDescription(final String title, final String description) {
        String extra = description.replace("Listen to " + title + " on Spotify.", "")
                .replace("Listen to " + title + " on Spotify", "").trim();
        final int dot = extra.indexOf('·');
        if (dot > 0) extra = extra.substring(0, dot).trim();
        if (extra.length() > 100) extra = extra.substring(0, 100).trim();
        return extra;
    }

    private static String meta(final String html, final Pattern normal, final Pattern reversed) {
        Matcher matcher = normal.matcher(html);
        if (matcher.find()) return decode(matcher.group(1));
        matcher = reversed.matcher(html);
        return matcher.find() ? decode(matcher.group(1)) : "";
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
        if (raw == null) return "";
        return raw.replace("\\u0026", "&").replace("\\u003d", "=")
                .replace("\\\"", "\"").replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&#x27;", "'").replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    public static final class Match {
        public final String spotifyTitle;
        public final String artist;
        public final String coverUrl;
        public final String query;
        public final String youtubeUrl;
        public final String youtubeTitle;

        Match(final String spotifyTitle, final String artist, final String coverUrl, final String query,
              final String youtubeUrl, final String youtubeTitle) {
            this.spotifyTitle = spotifyTitle;
            this.artist = artist;
            this.coverUrl = coverUrl;
            this.query = query;
            this.youtubeUrl = youtubeUrl;
            this.youtubeTitle = youtubeTitle;
        }
    }

    private static final class Track {
        final String id;
        final String title;
        final String artist;
        final String coverUrl;
        Track(final String id, final String title, final String artist, final String coverUrl) {
            this.id = id; this.title = title; this.artist = artist; this.coverUrl = coverUrl;
        }
    }

    private static final class Page {
        final String finalUrl;
        final String html;
        Page(final String finalUrl, final String html) { this.finalUrl = finalUrl; this.html = html; }
    }
}
