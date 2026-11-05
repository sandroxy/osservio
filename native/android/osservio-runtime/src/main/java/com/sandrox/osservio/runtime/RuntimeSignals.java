package com.sandrox.osservio.runtime;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentCallbacks2;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Looper;
import com.sandrox.osservio.core.BreadcrumbEvent;
import com.sandrox.osservio.core.BreadcrumbRecorder;
import com.sandrox.osservio.core.LogMessage;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/** Explicit native callback ownership. Never reads activity state bundles, views, or user input. */
public final class RuntimeSignals {
    private final Application application;
    private final BreadcrumbRecorder recorder;
    private Callbacks callbacks;
    public RuntimeSignals(Application application, BreadcrumbRecorder recorder) {
        this.application = Objects.requireNonNull(application, "application"); this.recorder = Objects.requireNonNull(recorder, "recorder");
    }
    public void start() {
        requireMain(); if (callbacks != null) return;
        Callbacks created = new Callbacks(new Lease(recorder)); callbacks = created;
        try {
            application.registerActivityLifecycleCallbacks(created);
            application.registerComponentCallbacks(created);
        } catch (RuntimeException failure) {
            created.lease.invalidate(); callbacks = null;
            application.unregisterActivityLifecycleCallbacks(created);
            application.unregisterComponentCallbacks(created);
            throw failure;
        }
    }
    public void stop() {
        requireMain(); Callbacks old = callbacks; if (old == null) return;
        old.lease.invalidate(); callbacks = null;
        application.unregisterActivityLifecycleCallbacks(old); application.unregisterComponentCallbacks(old);
    }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Runtime signal controls require the main thread");
    }
    private static final class Callbacks implements Application.ActivityLifecycleCallbacks, ComponentCallbacks2 {
        final Lease lease;
        Callbacks(Lease lease) { this.lease = lease; }
        private void activity(Activity activity, BreadcrumbEvent.Kind kind, String label) {
            lease.record(kind, label, LogMessage.publicText(activity.getClass().getName()), null);
        }
        @Override public void onActivityStarted(Activity activity) { activity(activity, BreadcrumbEvent.Kind.ACTIVITY_STARTED, "android.activity.started"); }
        @Override public void onActivityResumed(Activity activity) { activity(activity, BreadcrumbEvent.Kind.ACTIVITY_RESUMED, "android.activity.resumed"); }
        @Override public void onActivityPaused(Activity activity) { activity(activity, BreadcrumbEvent.Kind.ACTIVITY_PAUSED, "android.activity.paused"); }
        @Override public void onActivityStopped(Activity activity) { activity(activity, BreadcrumbEvent.Kind.ACTIVITY_STOPPED, "android.activity.stopped"); }
        @Override public void onActivityDestroyed(Activity activity) { activity(activity, BreadcrumbEvent.Kind.ACTIVITY_DESTROYED, "android.activity.destroyed"); }
        @Override public void onActivityCreated(Activity activity, Bundle state) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
        @Override public void onConfigurationChanged(Configuration configuration) { }
        // Superseded by onTrimMemory; Android 14+ does not issue this callback.
        @SuppressWarnings("deprecation")
        @Override public void onLowMemory() { }
        @Override public void onTrimMemory(int level) {
            lease.record(BreadcrumbEvent.Kind.MEMORY_TRIM, "android.memory.trim", null, (long) level);
        }
    }
    static final class Lease {
        final BreadcrumbRecorder recorder;
        final ReentrantLock lock = new ReentrantLock();
        boolean active = true;
        Lease(BreadcrumbRecorder recorder) { this.recorder = recorder; }
        void invalidate() { lock.lock(); try { active = false; } finally { lock.unlock(); } }
        void record(BreadcrumbEvent.Kind kind, String label, LogMessage message, Long value) {
            if (lock.isHeldByCurrentThread() || !lock.tryLock()) return;
            try { if (active) recorder.record(kind, label, message, value, BreadcrumbEvent.Retention.RESTART_RELEVANT); }
            finally { lock.unlock(); }
        }
    }
}
