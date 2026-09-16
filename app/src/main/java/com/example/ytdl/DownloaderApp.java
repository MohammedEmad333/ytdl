package com.example.ytdl;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App-level glue for Spotify URLs pasted into MainActivity's existing URL field.
 *
 * MainActivity intentionally remains focused on YouTube extraction. This watcher resolves a
 * pasted Spotify track to the matching YouTube stream, replaces the field value, and lets the
 * existing FETCH button continue through the proven extraction/download path unchanged.
 */
public final class DownloaderApp extends Application {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<EditText> attached = Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void onCreate() {
        super.onCreate();
        Net.ensureExtractor();

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(final Activity activity, final Bundle state) {
                if (activity instanceof MainActivity) {
                    main.post(() -> attachToUrlField(activity));
                }
            }

            @Override public void onActivityStarted(final Activity activity) { }
            @Override public void onActivityResumed(final Activity activity) { }
            @Override public void onActivityPaused(final Activity activity) { }
            @Override public void onActivityStopped(final Activity activity) { }
            @Override public void onActivitySaveInstanceState(final Activity activity, final Bundle state) { }
            @Override public void onActivityDestroyed(final Activity activity) { }
        });
    }

    private void attachToUrlField(final Activity activity) {
        final EditText field = findEditText(activity.getWindow().getDecorView());
        if (field == null || attached.contains(field)) {
            return;
        }
        attached.add(field);

        field.addTextChangedListener(new TextWatcher() {
            private String resolving = "";

            @Override public void beforeTextChanged(final CharSequence s, final int start,
                                                    final int count, final int after) { }
            @Override public void onTextChanged(final CharSequence s, final int start,
                                                final int before, final int count) { }

            @Override
            public void afterTextChanged(final Editable editable) {
                final String value = editable.toString().trim();
                if (!Spotify.isSpotifyUrl(value) || value.equals(resolving)) {
                    return;
                }

                resolving = value;
                Toast.makeText(activity, "Resolving Spotify track…", Toast.LENGTH_SHORT).show();
                executor.execute(() -> {
                    try {
                        final Spotify.Match match = Spotify.resolve(value);
                        main.post(() -> {
                            if (activity.isFinishing() || activity.isDestroyed()) {
                                return;
                            }
                            if (!field.getText().toString().trim().equals(value)) {
                                return;
                            }
                            field.setText(match.youtubeUrl);
                            field.setSelection(field.getText().length());
                            resolving = "";
                            Toast.makeText(activity,
                                    "Spotify matched: " + match.youtubeTitle,
                                    Toast.LENGTH_LONG).show();
                        });
                    } catch (final Exception e) {
                        main.post(() -> {
                            resolving = "";
                            if (!activity.isFinishing() && !activity.isDestroyed()) {
                                final String message = e.getMessage() == null
                                        ? "Couldn't resolve Spotify track" : e.getMessage();
                                Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                });
            }
        });
    }

    private static EditText findEditText(final View view) {
        if (view instanceof EditText) {
            return (EditText) view;
        }
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                final EditText found = findEditText(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
