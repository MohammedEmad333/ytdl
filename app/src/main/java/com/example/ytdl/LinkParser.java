package com.example.ytdl;

import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds and validates supported media links inside copied/shared text. */
final class LinkParser {
    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://\\S+");

    private LinkParser() {}

    static String firstSupportedMediaLink(final String value) {
        if (value == null) return null;
        final String trimmed = value.trim();
        if (isSupportedMediaLink(trimmed)) return trimmed;

        final Matcher matcher = URL_IN_TEXT.matcher(value);
        while (matcher.find()) {
            final String candidate = trimTrailingPunctuation(matcher.group());
            if (isSupportedMediaLink(candidate)) return candidate;
        }
        return null;
    }

    private static String trimTrailingPunctuation(String value) {
        while (!value.isEmpty()) {
            final char last = value.charAt(value.length() - 1);
            if (last == '.' || last == ',' || last == ';' || last == ':'
                    || last == ')' || last == ']' || last == '}'
                    || last == '!' || last == '?' || last == '\'' || last == '"') {
                value = value.substring(0, value.length() - 1);
            } else {
                break;
            }
        }
        return value;
    }

    private static boolean isSupportedMediaLink(final String value) {
        if (!(value.startsWith("http://") || value.startsWith("https://"))) return false;
        if (Spotify.isSpotifyUrl(value)) return true;
        try {
            if (ServiceList.YouTube.getLinkTypeByUrl(value) != StreamingService.LinkType.NONE) {
                return true;
            }
        } catch (final Exception ignored) {}
        try {
            return ServiceList.SoundCloud.getLinkTypeByUrl(value)
                    != StreamingService.LinkType.NONE;
        } catch (final Exception ignored) {
            return false;
        }
    }
}
