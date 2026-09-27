package com.example.ytdl;

import android.content.Context;

/** Bulk actions for the existing durable download queue. */
public final class BatchControls {
    private BatchControls() {}

    public static void pauseAll(final Context context) {
        DownloadService.cancelPauseAfterCurrent();
        for (final DownloadService.Task task : DownloadService.snapshot()) {
            if (!task.state.finished() && task.state != DownloadService.State.PAUSED) {
                DownloadService.pause(context, task);
            }
        }
    }

    public static void resumeAll(final Context context) {
        DownloadService.cancelPauseAfterCurrent();
        for (final DownloadService.Task task : DownloadService.snapshot()) {
            if (task.state == DownloadService.State.PAUSED) {
                DownloadService.resume(context, task);
            }
        }
    }

    public static void cancelAll(final Context context) {
        for (final DownloadService.Task task : DownloadService.snapshot()) {
            if (!task.state.finished()) {
                DownloadService.cancel(context, task);
            }
        }
    }

    public static int retryFailed(final Context context) {
        int count = 0;
        for (final DownloadService.Task task : DownloadService.snapshot()) {
            if (task.state == DownloadService.State.FAILED) {
                DownloadService.retry(context, task);
                count++;
            }
        }
        return count;
    }
}
