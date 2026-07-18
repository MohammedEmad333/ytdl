package com.example.ytdl;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Talks to a bgutil poToken server, if one happens to be running on this device.
 *
 * Background: some YouTube videos return only a muxed 360p stream because the adaptive
 * formats are gated behind a poToken — an integrity token minted by running YouTube's
 * BotGuard challenge. The phone can't safely run that challenge, but the bgutil tool can,
 * and on Android it's practical to run bgutil inside Termux as a local HTTP server. When
 * it's up, it answers on 127.0.0.1:4416 and will mint a token bound to a video id.
 *
 * This class is deliberately split in two:
 *   - isServerUp() is used NOW, to turn the "0/0" diagnostic into an actionable pointer.
 *   - fetchToken() is the seam for LATER. Wiring it into extraction (via the extractor's
 *     setPoTokenProvider) is the full in-app path; it isn't called yet, but living here
 *     means enabling it is a change in one place rather than a new architecture.
 *
 * None of this is load-bearing for the app. If the server isn't running — which is the
 * normal case — every method fails quietly and the app behaves exactly as it did before.
 */
public final class PoToken {

    /** bgutil's default. It binds [::]:4416; 127.0.0.1 reaches it via IPv4 fallback. */
    private static final String BASE_URL = "http://127.0.0.1:4416";

    private static final MediaType JSON = MediaType.parse("application/json");

    // Short timeouts on purpose: this is a localhost probe, not a network call. A slow
    // answer here would stall the fetch screen, and the whole point is to stay invisible
    // when the server is absent.
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build();

    private PoToken() {
    }

    /**
     * Is a bgutil server answering on this device right now? Cheap, synchronous, and safe
     * to call off the main thread. Any failure — no server, wrong thing on the port, slow
     * response — is a plain "no".
     */
    public static boolean isServerUp() {
        final Request request = new Request.Builder()
                .url(BASE_URL + "/ping")
                .get()
                .build();
        try (Response response = CLIENT.newCall(request).execute()) {
            return response.isSuccessful();
        } catch (final Exception e) {
            return false;
        }
    }

    /**
     * Ask the server for a poToken bound to a video id, or null if anything goes wrong.
     *
     * Not called yet — this is the hook the full in-app path will use. YouTube moved to
     * tokens bound to the video id alone (content binding), which is why this takes just a
     * video id and no visitor-data handshake: bgutil accepts the id as content_binding and
     * returns a token bound to it. If YouTube's binding model changes back, this is the one
     * method that would need to grow a visitor-data parameter.
     */
    public static String fetchToken(final String videoId) {
        try {
            final JSONObject body = new JSONObject().put("content_binding", videoId);
            final Request request = new Request.Builder()
                    .url(BASE_URL + "/get_pot")
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();

            try (Response response = CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return null;
                }
                final ResponseBody responseBody = response.body();
                if (responseBody == null) {
                    return null;
                }
                final JSONObject parsed = new JSONObject(responseBody.string());
                final String token = parsed.optString("po_token", "");
                return token.isEmpty() ? null : token;
            }
        } catch (final Exception e) {
            // JSONException is checked and extends Exception (not RuntimeException), so it
            // has to be caught here alongside IO and runtime failures. Any failure means
            // "no token", which the caller already treats as "server didn't help".
            return null;
        }
    }
}
