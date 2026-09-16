package com.example.ytdl;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exported entry point for shared/opened media links. */
public final class LinkRouterActivity extends Activity {
    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://\\S+");

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        openDownloader(extractLink(getIntent()));
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
        final TextView status = Ui.sans(this, 14, Ui.MUTED);
        status.setGravity(Gravity.CENTER);
        status.setText("Opening link…");
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
        if (intent == null) return null;
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            return intent.getData().toString();
        }
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            final String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text == null) return null;
            final Matcher matcher = URL_IN_TEXT.matcher(text);
            return matcher.find() ? matcher.group().replaceAll("[),.;]+$", "") : null;
        }
        return null;
    }
}
