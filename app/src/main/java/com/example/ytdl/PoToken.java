package com.example.ytdl;

import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider;
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult;

import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Bridges the app to a bgutil poToken server running locally in Termux.
 *
 * Why this exists: some YouTube videos return only a muxed 360p stream because the adaptive
 * formats are gated behind a poToken — an integrity token minted by running YouTube's
 * BotGuard challenge, which a plain Android app can't safely do. bgutil can, and on Android
 * it's practical to run it inside Termux as a local HTTP server on 127.0.0.1:4416. When it's
 * up, this class asks it for a token and feeds that to NewPipeExtractor, which then returns
 * the full format list instead of just 360p.
 *
 * The whole thing is best-effort. If the server isn't running — the normal case — the
 * provider returns null for every client, and the extractor falls back to exactly the
 * behaviour the app had before: 360p on walled videos, everything on normal ones.
 */
public final class PoToken {

    /** bgutil's default. It binds [::]:4416; 127.0.0.1 reaches it via the IPv4 fallback. */
    private static final String BASE_URL = "http://127.0.0.1:4416";

    private static final MediaType JSON = MediaType.parse("application/json");

    // The /ping probe wants a short timeout — it runs on the fetch path and must stay
    // invisible when nothing's there. Token minting runs BotGuard and is genuinely slow,
    // so it gets a much longer read timeout.
    private static final OkHttpClient PING = new OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build();

    private static final OkHttpClient MINT = new OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private PoToken() {
    }

    /** A minted token plus the identity YouTube bound it to. */
    public static final class Token {
        final String poToken;
        final String visitorData;

        Token(final String poToken, final String visitorData) {
            this.poToken = poToken;
            this.visitorData = visitorData;
        }
    }

    /**
     * Is a bgutil server answering on this device right now? Cheap, synchronous, safe off
     * the main thread. Any failure — no server, wrong thing on the port, slow answer — is a
     * plain "no". Used to tailor the diagnostic and to decide whether a token retry is even
     * worth attempting.
     */
    public static boolean isServerUp() {
        final Request request = new Request.Builder().url(BASE_URL + "/ping").get().build();
        try (Response response = PING.newCall(request).execute()) {
            return response.isSuccessful();
        } catch (final Exception e) {
            return false;
        }
    }

    /**
     * Ask the server to mint a token bound to a video id.
     *
     * bgutil binds to a "content_binding" — here the video id. YouTube moved to content-bound
     * tokens, which is why no visitor-data handshake is needed on the request side: we send
     * the id, bgutil returns the token and the identifier (visit_identifier) it ended up
     * bound to, which we carry back as visitorData. Returns null on any failure.
     */
    @Nullable
    public static Token mint(final String videoId) {
        try {
            final JSONObject body = new JSONObject().put("content_binding", videoId);
            // Content-first order, matching Net.java's RequestBody.create usage which has
            // compiled in every build. okhttp 4.x resolves this to the current
            // create(String, MediaType) overload.
            final RequestBody payload = RequestBody.create(body.toString(), JSON);
            final Request request = new Request.Builder()
                    .url(BASE_URL + "/get_pot")
                    .post(payload)
                    .build();

            try (Response response = MINT.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return null;
                }
                final ResponseBody responseBody = response.body();
                if (responseBody == null) {
                    return null;
                }
                final JSONObject parsed = new JSONObject(responseBody.string());
                final String token = parsed.optString("po_token", "");
                if (token.isEmpty()) {
                    return null;
                }
                // Server may call it visit_identifier or visitor_data depending on version;
                // accept either, and tolerate its absence (content-bound tokens don't strictly
                // need it, but PoTokenResult wants a non-null string).
                String visitor = parsed.optString("visit_identifier", "");
                if (visitor.isEmpty()) {
                    visitor = parsed.optString("visitor_data", "");
                }
                return new Token(token, visitor);
            }
        } catch (final Exception e) {
            return null;
        }
    }

    /**
     * A PoTokenProvider backed by the bgutil server, for one specific video.
     *
     * The extractor asks per InnerTube client (web, web-embedded, android). We mint once for
     * the video id and hand the same token to whichever client asks — the token is bound to
     * the video, not the client. Minting is cached for this instance so three client calls
     * don't trigger three BotGuard runs.
     *
     * A provider instance is created per fetch attempt and thrown away after — never reused
     * across videos, because a token bound to one video id is no use for another, and stale
     * reuse is what the NewPipe maintainers saw produce 403s.
     */
    public static final class Provider implements PoTokenProvider {
        private final String videoId;
        private boolean minted;
        @Nullable private Token cached;

        public Provider(final String videoId) {
            this.videoId = videoId;
        }

        @Nullable
        private synchronized PoTokenResult result() {
            if (!minted) {
                cached = mint(videoId);
                minted = true;
            }
            if (cached == null) {
                return null;
            }
            // Constructor order is (visitorData, playerRequestPoToken, streamingDataPoToken).
            // Both poToken slots get the same content-bound token. If the streaming URLs ever
            // 403 while the player request succeeds, this is the line to revisit — it's where
            // a separate visitor-bound streaming token would go.
            return new PoTokenResult(cached.visitorData, cached.poToken, cached.poToken);
        }

        @Nullable
        @Override
        public PoTokenResult getWebClientPoToken(final String videoId) {
            return result();
        }

        @Nullable
        @Override
        public PoTokenResult getWebEmbedClientPoToken(final String videoId) {
            return result();
        }

        @Nullable
        @Override
        public PoTokenResult getAndroidClientPoToken(final String videoId) {
            // Leave the Android client alone — its integrity path (DroidGuard) differs, and
            // feeding it a web-minted token tends to hurt more than help. Web is what unlocks
            // the adaptive formats, which is the whole point here.
            return null;
        }

        @Nullable
        @Override
        public PoTokenResult getIosClientPoToken(final String videoId) {
            // Same reasoning as Android: iOS uses its own attestation, not a web poToken.
            return null;
        }
    }
}
