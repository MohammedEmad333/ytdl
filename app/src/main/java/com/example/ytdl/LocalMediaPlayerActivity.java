package com.example.ytdl;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** Offline audio/video playback from an explicitly completed NOVA library entry. */
public final class LocalMediaPlayerActivity extends Activity {
    static final String EXTRA_TASK_ID = "nova_player_task_id";
    private static final String POSITION = "video_position";
    private static final String WAS_PLAYING = "video_was_playing";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private VideoView videoView;
    private MediaPlayer audioPlayer;
    private SeekBar audioSeek;
    private TextView audioTime;
    private Button audioToggle;
    private DownloadService.Task task;
    private int position;
    private boolean playWhenReady = true;
    private boolean prepared;
    private TextView status;
    private Button external;

    private final Runnable updateAudioProgress = new Runnable() {
        @Override public void run() {
            if (audioPlayer == null || !prepared || audioSeek == null) return;
            try {
                final int current = audioPlayer.getCurrentPosition();
                position = current;
                audioSeek.setProgress(current);
                showAudioTime(current, audioPlayer.getDuration());
                if (audioPlayer.isPlaying()) {
                    ui.postDelayed(this, 500);
                }
            } catch (IllegalStateException ignored) {
                showPlaybackError();
            }
        }
    };

    /**
     * The URI is loaded only from the saved queue item, never from an external
     * intent. Downloads can contain video OR audio, while the typed Video/Audio
     * collections must agree with the media MIME type.
     */
    static boolean canPlay(DownloadService.Task value) {
        if (value == null || value.state != DownloadService.State.DONE
                || value.outputUri == null || value.mimeType == null) return false;
        final String mime = value.mimeType.toLowerCase(Locale.ROOT);
        final boolean video = mime.startsWith("video/");
        final boolean audio = mime.startsWith("audio/");
        if (!video && !audio) return false;
        try {
            final Uri uri = Uri.parse(value.outputUri);
            if (!PendingMediaLedger.isOwnedItemUri(uri)) return false;
            final List<String> segments = uri.getPathSegments();
            if ("video".equals(segments.get(1))) return video;
            if ("audio".equals(segments.get(1))) return audio;
            return "downloads".equals(segments.get(1));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    static Intent intentFor(Activity source, DownloadService.Task item) {
        if (!canPlay(item)) throw new IllegalArgumentException("Not a saved local media file");
        return new Intent(source, LocalMediaPlayerActivity.class)
                .putExtra(EXTRA_TASK_ID, item.id);
    }

    static String formatTime(long milliseconds) {
        final long seconds = Math.max(0, milliseconds) / 1000L;
        final long hours = seconds / 3600L;
        final long minutes = seconds / 60L % 60L;
        final long remaining = seconds % 60L;
        return hours > 0
                ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, remaining)
                : String.format(Locale.US, "%d:%02d", minutes, remaining);
    }

    private String tr(String english, String arabic) {
        return NovaPreferences.text(this, english, arabic);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.applyChrome(this);
        DownloadService.ensureLoaded(this);
        final Intent launch = getIntent();
        task = launch == null ? null : DownloadService.byId(
                launch.getLongExtra(EXTRA_TASK_ID, -1));
        if (!canPlay(task)) {
            Toast.makeText(this, tr("This saved media file is unavailable",
                    "ملف الوسائط المحفوظ غير متاح"), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (savedInstanceState != null) {
            position = Math.max(0, savedInstanceState.getInt(POSITION, 0));
            playWhenReady = savedInstanceState.getBoolean(WAS_PLAYING, false);
        }

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setLayoutDirection(NovaPreferences.arabic(this)
                ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 18),
                Ui.dp(this, 16), Ui.dp(this, 18));

        final Button back = Ui.button(this, tr("← Back to Library", "← العودة للمكتبة"), false);
        back.setOnClickListener(v -> finish());
        root.addView(back, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));

        final TextView title = Ui.headline(this, 19);
        title.setText(task.title == null ? tr("Saved media", "وسائط محفوظة") : task.title);
        title.setMaxLines(2);
        final LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = Ui.dp(this, 18);
        root.addView(title, titleParams);

        status = Ui.sans(this, 13, Ui.MUTED);
        status.setGravity(Gravity.CENTER);
        status.setText(tr("Opening saved media…", "جاري فتح الملف المحفوظ…"));
        status.setPadding(0, Ui.dp(this, 13), 0, Ui.dp(this, 13));

        external = Ui.button(this, tr("Open with another player", "فتح بمشغل آخر"), false);
        external.setVisibility(View.GONE);
        external.setOnClickListener(v -> openExternal());

        final boolean isAudio = task.mimeType.toLowerCase(Locale.ROOT).startsWith("audio/");
        if (isAudio) buildAudioUi(root);
        else buildVideoUi(root);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));
        root.addView(external, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));
        setContentView(root);

        if (isAudio) prepareAudio();
        else videoView.setVideoURI(Uri.parse(task.outputUri));
    }

    private void buildVideoUi(LinearLayout root) {
        videoView = new VideoView(this);
        videoView.setBackgroundColor(android.graphics.Color.BLACK);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, 0, 1f);
        params.topMargin = Ui.dp(this, 15);
        root.addView(videoView, params);
        final MediaController controls = new MediaController(this);
        controls.setAnchorView(videoView);
        videoView.setMediaController(controls);
        videoView.setOnPreparedListener(player -> {
            prepared = true;
            if (position > 0) videoView.seekTo(position);
            if (playWhenReady) videoView.start();
            status.setText(tr("Tap video for playback controls",
                    "اضغط على الفيديو لإظهار أدوات التشغيل"));
        });
        videoView.setOnErrorListener((player, what, extra) -> {
            showPlaybackError();
            return true;
        });
        videoView.setOnCompletionListener(player -> {
            playWhenReady = false;
            position = 0;
            status.setText(tr("Playback finished", "انتهى التشغيل"));
        });
    }

    private void buildAudioUi(LinearLayout root) {
        final LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setBackground(Ui.box(this, Ui.SURFACE, Ui.LINE, 20));
        panel.setPadding(Ui.dp(this, 18), Ui.dp(this, 24),
                Ui.dp(this, 18), Ui.dp(this, 24));
        final LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        panelParams.topMargin = Ui.dp(this, 15);
        root.addView(panel, panelParams);

        final TextView symbol = Ui.headline(this, 38);
        symbol.setGravity(Gravity.CENTER);
        symbol.setText("♫");
        panel.addView(symbol, new LinearLayout.LayoutParams(-1, Ui.dp(this, 96)));

        audioTime = Ui.mono(this, 14, Ui.MUTED);
        audioTime.setGravity(Gravity.CENTER);
        audioTime.setText("0:00 / 0:00");
        panel.addView(audioTime, new LinearLayout.LayoutParams(-1, Ui.dp(this, 42)));

        audioSeek = new SeekBar(this);
        audioSeek.setEnabled(false);
        audioSeek.setMax(1);
        audioSeek.setContentDescription(tr("Seek audio position", "تغيير موضع التشغيل"));
        audioSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser && prepared && audioPlayer != null) {
                    showAudioTime(value, audioPlayer.getDuration());
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (!prepared || audioPlayer == null) return;
                try {
                    position = bar.getProgress();
                    audioPlayer.seekTo(position);
                } catch (IllegalStateException ignored) {
                    showPlaybackError();
                }
            }
        });
        panel.addView(audioSeek, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));

        audioToggle = Ui.button(this, tr("Pause", "إيقاف مؤقت"), true);
        audioToggle.setEnabled(false);
        audioToggle.setOnClickListener(v -> toggleAudio());
        final LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 52));
        buttonParams.topMargin = Ui.dp(this, 12);
        panel.addView(audioToggle, buttonParams);
    }

    private void prepareAudio() {
        try {
            audioPlayer = new MediaPlayer();
            audioPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build());
            audioPlayer.setDataSource(this, Uri.parse(task.outputUri));
            audioPlayer.setOnPreparedListener(player -> {
                prepared = true;
                final int duration = Math.max(0, player.getDuration());
                audioSeek.setMax(Math.max(1, duration));
                audioSeek.setEnabled(duration > 0);
                audioToggle.setEnabled(true);
                if (position > 0) player.seekTo(Math.min(position, duration));
                if (playWhenReady) player.start();
                syncAudioButton();
                showAudioTime(player.getCurrentPosition(), duration);
                status.setText(tr("Playing an offline audio file",
                        "تشغيل ملف صوتي محفوظ على الجهاز"));
                ui.removeCallbacks(updateAudioProgress);
                ui.post(updateAudioProgress);
            });
            audioPlayer.setOnCompletionListener(player -> {
                playWhenReady = false;
                position = 0;
                player.seekTo(0);
                syncAudioButton();
                showAudioTime(0, player.getDuration());
                ui.removeCallbacks(updateAudioProgress);
                status.setText(tr("Playback finished", "انتهى التشغيل"));
            });
            audioPlayer.setOnErrorListener((player, what, extra) -> {
                showPlaybackError();
                return true;
            });
            audioPlayer.prepareAsync();
        } catch (IOException | RuntimeException error) {
            showPlaybackError();
        }
    }

    private void toggleAudio() {
        if (!prepared || audioPlayer == null) return;
        try {
            if (audioPlayer.isPlaying()) {
                audioPlayer.pause();
                playWhenReady = false;
                ui.removeCallbacks(updateAudioProgress);
            } else {
                audioPlayer.start();
                playWhenReady = true;
                ui.removeCallbacks(updateAudioProgress);
                ui.post(updateAudioProgress);
            }
            syncAudioButton();
        } catch (IllegalStateException error) {
            showPlaybackError();
        }
    }

    private void syncAudioButton() {
        if (audioToggle == null || audioPlayer == null || !prepared) return;
        audioToggle.setText(audioPlayer.isPlaying()
                ? tr("Pause", "إيقاف مؤقت") : tr("Play", "تشغيل"));
    }

    private void showAudioTime(int current, int duration) {
        if (audioTime != null) audioTime.setText(
                formatTime(current) + " / " + formatTime(duration));
    }

    private void showPlaybackError() {
        prepared = false;
        ui.removeCallbacks(updateAudioProgress);
        if (audioToggle != null) audioToggle.setEnabled(false);
        if (audioSeek != null) audioSeek.setEnabled(false);
        if (status != null) status.setText(tr(
                "NOVA cannot play this format. Try an external player.",
                "تعذّر تشغيل هذا النوع في نوفا. جرّب مشغلًا خارجيًا."));
        if (external != null) external.setVisibility(View.VISIBLE);
    }

    private void openExternal() {
        if (!canPlay(task)) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(task.outputUri), task.mimeType)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (RuntimeException unavailable) {
            Toast.makeText(this, tr("No compatible player installed",
                    "لا يوجد مشغل متوافق مثبت"), Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (prepared && playWhenReady) {
            if (videoView != null && !videoView.isPlaying()) videoView.start();
            if (audioPlayer != null) {
                try {
                    if (!audioPlayer.isPlaying()) audioPlayer.start();
                    syncAudioButton();
                    ui.removeCallbacks(updateAudioProgress);
                    ui.post(updateAudioProgress);
                } catch (IllegalStateException error) {
                    showPlaybackError();
                }
            }
        }
    }

    @Override protected void onPause() {
        ui.removeCallbacks(updateAudioProgress);
        if (videoView != null && prepared) {
            playWhenReady = videoView.isPlaying();
            position = videoView.getCurrentPosition();
            videoView.pause();
        }
        if (audioPlayer != null && prepared) {
            try {
                playWhenReady = audioPlayer.isPlaying();
                position = audioPlayer.getCurrentPosition();
                if (playWhenReady) audioPlayer.pause();
                syncAudioButton();
            } catch (IllegalStateException ignored) {
                showPlaybackError();
            }
        }
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (prepared) {
            if (videoView != null) {
                position = videoView.getCurrentPosition();
                playWhenReady = videoView.isPlaying();
            } else if (audioPlayer != null) {
                try {
                    position = audioPlayer.getCurrentPosition();
                    // Keep playWhenReady from onPause when saving after backgrounding.
                    if (audioPlayer.isPlaying()) playWhenReady = true;
                } catch (IllegalStateException ignored) {
                    // Preserve the last position rather than crashing during rotation.
                }
            }
        }
        state.putInt(POSITION, position);
        state.putBoolean(WAS_PLAYING, playWhenReady);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        ui.removeCallbacks(updateAudioProgress);
        if (videoView != null) videoView.stopPlayback();
        if (audioPlayer != null) {
            audioPlayer.release();
            audioPlayer = null;
        }
        super.onDestroy();
    }
}
