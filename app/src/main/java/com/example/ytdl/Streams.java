package com.example.ytdl;

import android.os.Build;

import org.schabi.newpipe.extractor.services.youtube.ItagItem;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Choosing streams, in one place.
 *
 * Both sides need this and they must agree. The activity uses it to show you what's on offer;
 * the service uses it to re-derive the same choice later — for a playlist item that was never
 * extracted, or for a paused task whose stream URLs have since expired. If the two ever
 * disagreed, you'd pick 1080p and silently receive something else.
 */
public final class Streams {

    private Streams() {
    }

    // ---------------------------------------------------------------- video

    /**
     * Can MediaMuxer actually put this in an MP4?
     *
     * Filtering on container alone isn't enough, which was a real bug: YouTube serves some
     * videos as AV1 inside MPEG-4, so they passed the container check, reached addTrack, and
     * threw — a Failed task with a codec message and no obvious reason why that one video.
     * MediaMuxer only gained AV1-in-MP4 at API 31, and minSdk here is 29.
     */
    public static boolean muxable(final VideoStream stream) {
        if (stream.getFormat() != org.schabi.newpipe.extractor.MediaFormat.MPEG_4) {
            return false;
        }
        final String codec = stream.getCodec();
        if (codec == null || codec.isEmpty()) {
            // Unknown codec: let it through and let addTrack be the judge. Better to try and
            // report a real error than to hide a format that might have worked.
            return true;
        }
        final String c = codec.toLowerCase(Locale.US);
        if (c.startsWith("avc")) {
            return true;
        }
        if (c.startsWith("hev") || c.startsWith("hvc")) {
            return true;
        }
        if (c.startsWith("av01")) {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
        }
        return false;
    }

    /** Video with no audio track — where every resolution above 720p lives. Highest first. */
    public static List<VideoStream> videoOnly(final StreamInfo info) {
        final List<VideoStream> out = new ArrayList<>();
        for (final VideoStream vs : info.getVideoOnlyStreams()) {
            if (vs.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) {
                continue;
            }
            if (!muxable(vs)) {
                continue;
            }
            out.add(vs);
        }
        Collections.sort(out, (a, b) -> heightOf(b.getResolution()) - heightOf(a.getResolution()));
        return out;
    }

    /** Video and audio already in one file. Tops out at 360p on most videos. Highest first. */
    public static List<VideoStream> muxed(final StreamInfo info) {
        final List<VideoStream> out = new ArrayList<>();
        for (final VideoStream vs : info.getVideoStreams()) {
            if (vs.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) {
                continue;
            }
            out.add(vs);
        }
        Collections.sort(out, (a, b) -> heightOf(b.getResolution()) - heightOf(a.getResolution()));
        return out;
    }

    /**
     * Nearest height at or below the target, falling back to the lowest available if
     * everything exceeds it. A playlist's videos don't all offer the same resolutions, so
     * asking for 1080p has to mean "1080p or the best under it", not "fail".
     */
    public static VideoStream pickHeight(final List<VideoStream> highestFirst, final int target) {
        VideoStream lowest = null;
        for (final VideoStream vs : highestFirst) {
            if (heightOf(vs.getResolution()) <= target) {
                return vs;
            }
            lowest = vs;
        }
        return lowest;
    }

    // ---------------------------------------------------------------- audio

    /** AAC in .m4a — what MediaMuxer accepts alongside H.264 in an MP4. Best-ranked first. */
    public static List<AudioStream> mergeAudio(final StreamInfo info) {
        final List<AudioStream> out = new ArrayList<>();
        for (final AudioStream as : info.getAudioStreams()) {
            if (as.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) {
                continue;
            }
            if (as.getFormat() != org.schabi.newpipe.extractor.MediaFormat.M4A) {
                continue;
            }
            out.add(as);
        }
        Collections.sort(out, (a, b) -> Long.compare(rank(b), rank(a)));
        return out;
    }

    /** Every audio track, any container. Best-ranked first. */
    public static List<AudioStream> allAudio(final StreamInfo info) {
        final List<AudioStream> out = new ArrayList<>();
        for (final AudioStream as : info.getAudioStreams()) {
            if (as.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) {
                continue;
            }
            out.add(as);
        }
        Collections.sort(out, (a, b) -> Long.compare(rank(b), rank(a)));
        return out;
    }

    /**
     * By track id when one was explicitly chosen, otherwise the best-ranked.
     *
     * The id can legitimately go missing — a playlist queues by preference rather than by id,
     * and a re-resolve months later might find the track gone. Falling back to the ranking
     * beats failing the download.
     */
    public static AudioStream pickAudio(final List<AudioStream> ranked, final String trackId) {
        if (ranked.isEmpty()) {
            return null;
        }
        if (trackId != null) {
            for (final AudioStream as : ranked) {
                if (trackId.equals(as.getAudioTrackId())) {
                    return as;
                }
            }
        }
        return ranked.get(0);
    }

    /**
     * Bitrate alone isn't enough. YouTube ships dubbed audio tracks next to the original,
     * often at identical bitrates, so ranking on kbps would pick whichever came first — a
     * coin flip between English and a dub. The original wins outright; bitrate breaks ties.
     */
    public static long rank(final AudioStream stream) {
        final AudioTrackType type = stream.getAudioTrackType();
        final long originalBonus = type == AudioTrackType.ORIGINAL ? 1_000_000L : 0L;
        return originalBonus + Math.max(0, stream.getAverageBitrate());
    }

    public static String trackLabel(final AudioStream stream) {
        final String name = stream.getAudioTrackName();
        final Locale locale = stream.getAudioLocale();

        final String who;
        if (name != null && !name.isEmpty()) {
            who = name;
        } else if (locale != null) {
            who = locale.getDisplayName();
        } else {
            who = "Track";
        }

        final AudioTrackType type = stream.getAudioTrackType();
        final String kind = type == null ? "untagged" : type.name().toLowerCase(Locale.US);
        final String rate = " · " + stream.getAverageBitrate() + " kbps";

        // YouTube's track names frequently already contain the word — "English (US) original"
        // becoming "English (US) original (original)" is just a stutter. Only add the marker
        // when it says something the name doesn't, which is exactly when it matters most:
        // an untagged track the ranking couldn't reason about.
        if (who.toLowerCase(Locale.US).contains(kind)) {
            return who + rate;
        }
        return who + " (" + kind + ")" + rate;
    }

    // ---------------------------------------------------------------- misc

    /**
     * Byte length YouTube reports for a stream, or -1 if it didn't.
     *
     * It's on the itag rather than the stream, and absent for anything that isn't YouTube —
     * hence the two guards. Worth having: on a phone, the gap between choosing 1080p and
     * knowing what choosing 1080p costs is the whole decision.
     */
    public static long sizeOf(final Stream stream) {
        final ItagItem itag = stream.getItagItem();
        if (itag == null) {
            return -1;
        }
        final long bytes = itag.getContentLength();
        return bytes == ItagItem.CONTENT_LENGTH_UNKNOWN ? -1 : bytes;
    }

    /** Subtitles as the extractor hands them over. YouTube's default format is TTML. */
    public static List<SubtitlesStream> subtitles(final StreamInfo info) {
        final List<SubtitlesStream> out = new ArrayList<>();
        if (info.getSubtitles() != null) {
            out.addAll(info.getSubtitles());
        }
        return out;
    }

    public static String subtitleLabel(final SubtitlesStream stream) {
        final String language = stream.getDisplayLanguageName();
        final String base = language == null || language.isEmpty()
                ? stream.getLanguageTag() : language;
        return stream.isAutoGenerated() ? base + " (auto)" : base;
    }

    /** "1080p60" -> 1080. */
    public static int heightOf(final String resolution) {
        if (resolution == null) {
            return 0;
        }
        int end = 0;
        while (end < resolution.length() && Character.isDigit(resolution.charAt(end))) {
            end++;
        }
        return end == 0 ? 0 : Integer.parseInt(resolution.substring(0, end));
    }

    public static String formatName(final org.schabi.newpipe.extractor.MediaFormat format) {
        return format == null ? "unknown" : format.getName();
    }

    public static String suffix(final org.schabi.newpipe.extractor.MediaFormat format,
                                final String fallback) {
        return format == null ? fallback : format.getSuffix();
    }

    /** Video titles routinely contain characters that are illegal in filenames. */
    public static String sanitize(final String name) {
        if (name == null) {
            return "video";
        }
        final String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (cleaned.isEmpty()) {
            return "video";
        }
        return cleaned.length() > 100 ? cleaned.substring(0, 100) : cleaned;
    }
}
