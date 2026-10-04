package com.example.ytdl;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/** Process-level initialization and presentation wiring. */
public final class DownloaderApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Net.ensureExtractor();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(final Activity activity, final Bundle state) {}
            @Override public void onActivityStarted(final Activity activity) {}
            @Override public void onActivityResumed(final Activity activity) {
                Ui.polishActivity(activity);
                DynamicUiPolish.install(activity);
                InteractionUiPolish.install(activity);
            }
            @Override public void onActivityPaused(final Activity activity) {}
            @Override public void onActivityStopped(final Activity activity) {}
            @Override public void onActivitySaveInstanceState(final Activity activity, final Bundle state) {}
            @Override public void onActivityDestroyed(final Activity activity) {}
        });
    }
}
