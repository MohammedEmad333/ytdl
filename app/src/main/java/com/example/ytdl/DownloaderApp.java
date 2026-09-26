package com.example.ytdl;

import android.app.Application;

/**
 * Process-level initialization.
 *
 * Spotify links are intentionally handled by MainActivity. Older builds watched the input
 * field here and replaced Spotify URLs with a YouTube match before FETCH was pressed. That
 * bypassed Spotify collection handling and discarded Spotify title/artist/cover metadata.
 */
public final class DownloaderApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Net.ensureExtractor();
    }
}
