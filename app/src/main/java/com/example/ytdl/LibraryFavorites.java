package com.example.ytdl;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/**
 * Local-only favorites. A record includes the exact saved MediaStore URI,
 * so a recycled task ID cannot accidentally favorite a different download.
 */
final class LibraryFavorites {
    private static final String PREFS = "nova_library_favorites";
    private static final String PREFIX = "saved-";

    private LibraryFavorites() {}

    static boolean eligible(DownloadService.Task task) {
        if (task == null || task.id <= 0
                || task.state != DownloadService.State.DONE
                || task.outputUri == null || task.outputUri.isEmpty()) return false;
        try {
            return PendingMediaLedger.isOwnedItemUri(Uri.parse(task.outputUri));
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isFavorite(Context context, DownloadService.Task task) {
        return eligible(task) && task.outputUri.equals(
                preferences(context).getString(PREFIX + task.id, null));
    }

    static boolean toggle(Context context, DownloadService.Task task) {
        if (!eligible(task)) return false;
        final String key = PREFIX + task.id;
        final SharedPreferences prefs = preferences(context);
        if (task.outputUri.equals(prefs.getString(key, null))) {
            prefs.edit().remove(key).apply();
            return false;
        }
        prefs.edit().putString(key, task.outputUri).apply();
        return true;
    }
}
