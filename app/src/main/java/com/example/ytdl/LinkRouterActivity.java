package com.example.ytdl;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small exported entry point for shared/opened links.
 *
 * YouTube links are forwarded unchanged. Spotify track links are resolved to a matching YouTube
 * stream first, then handed to MainActivity so the existing format picker, queue, pause/resume,
 * retry and background download implementation remain the single download path.
 */
public final class LinkRouterActivity extends Activity {

    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://\\S+");

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView status;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Net.ensureExtractor();
        setContentView(buildUi());

        final String link = extractLink(getIntent());
        if (link == null || link.isEmpty()) {
            openDownloader(null);
            return;
        }

        if (!Spotify.isSpotifyUrl(link)) {
            openDownloader(link);
            return;
        }

        status.setText("Reading Spotify track…\nFinding the matching audio source on YouTube.");
        executor.execute(() -> {
            try {
                final Spotify.Match match = Spotify.resolve(link);
                runOnUiThread(() -> {
                    Toast.makeText(this,
                            "Matched: " + match.youtubeTitle,
                            Toast.LENGTH_SHORT).show();
                    openDownloader(match.youtubeUrl);
                });
            } catch (final Exception e) {
                runOnUiThread(() -> status.setText(
                        "Couldn't resolve that Spotify link.\n\n" + safeMessage(e)
                                + "\n\nCurrently supported: individual Spotify track links."));
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private LinearLayout buildUi() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 28), Ui.dp(this, 28), Ui.dp(this, 28), Ui.dp(this, 28));

        final TextView title = Ui.mono(this, 15, Ui.TEXT);
        title.setText("MEDIA DOWNLOADER");
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = Ui.sans(this, 14, Ui.MUTED);
        status.setGravity(Gravity.CENTER);
        status.setText("Opening…");
        status.setPadding(0, Ui.dp(this, 18), 0, 0);
        root.addView(status);
        return root;
    }

    private void openDownloader(final String link) {
        final Intent next = new Intent(this, MainActivity.class);
        if (link != null && !link.isEmpty()) {
            next.setAction(Intent.ACTION_SEND);
            next.setType("text/plain");
            next.putExtra(Intent.EXTRA_TEXT, link);
        }
        startActivity(next);
        finish();
    }

    private static String extractLink(final Intent intent) {
        if (intent == null) {
            return null;
        }

        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            return intent.getData().toString();
        }

        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            final String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text == null) {
                return null;
            }
            final Matcher matcher = URL_IN_TEXT.matcher(text);
            return matcher.find() ? stripTrailingPunctuation(matcher.group()) : null;
        }
        return null;
    }

    private static String stripTrailingPunctuation(final String url) {
        return url.replaceAll("[),.;]+$", "");
    }

    private static String safeMessage(final Exception e) {
        final String message = e.getMessage();
        return message == null || message.trim().isEmpty()
                ? e.getClass().getSimpleName() : message;
    }
}
