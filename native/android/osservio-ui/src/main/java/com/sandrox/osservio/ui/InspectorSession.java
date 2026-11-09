package com.sandrox.osservio.ui;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import java.lang.ref.WeakReference;
import java.util.Objects;

/**
 * App-local, in-process inspection. All methods run on the main thread.
 * Attach each participating Activity from onResume. The factory must use the supplied
 * context and must not capture an Activity. Capture and archives remain host-owned.
 */
public final class InspectorSession implements Application.ActivityLifecycleCallbacks {
    public enum Presentation { HIDDEN, ENTRY, EXPANDED }
    public interface Factory { InspectorView create(Context context); }
    private final Application application;
    private final SessionContext context;
    private Factory factory;
    private WeakReference<Activity> owner = new WeakReference<>(null);
    private Presentation presentation = Presentation.HIDDEN;
    private boolean disposed;
    private boolean resumed;
    private boolean reconciling;
    private boolean reconcilePending;
    private InspectorView inspector;
    private InspectorFloatingEntry entry;
    private Dialog window;
    private final View.OnAttachStateChangeListener attachment = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) { reconcile(); }
        @Override public void onViewDetachedFromWindow(View view) { detachSurfaces(); }
    };

    public InspectorSession(Application application, Factory factory) {
        mainThread(); this.application = Objects.requireNonNull(application, "application");
        this.factory = Objects.requireNonNull(factory, "factory"); context = new SessionContext(application, this);
        application.registerActivityLifecycleCallbacks(this);
    }
    /** Rebinds the same reading/editing session without retaining the previous Activity. */
    public void attach(Activity activity) {
        mainThread(); Objects.requireNonNull(activity, "activity");
        if (disposed) throw new IllegalStateException("Inspector session is disposed");
        if (activity.getApplication() != application || activity.isDestroyed() || activity.isFinishing())
            throw new IllegalArgumentException("A live Activity from the owning application is required");
        Activity previous = owner.get();
        if (previous != activity) {
            detachSurfaces();
            if (previous != null) previous.getWindow().getDecorView().removeOnAttachStateChangeListener(attachment);
            owner = new WeakReference<>(activity); activity.getWindow().getDecorView().addOnAttachStateChangeListener(attachment);
        }
        resumed = true; reconcile();
    }
    public Presentation presentation() { mainThread(); return presentation; }
    public boolean isDisposed() { mainThread(); return disposed; }
    public boolean showEntry() {
        mainThread(); if (!available()) return false;
        if (presentation == Presentation.HIDDEN) presentation = Presentation.ENTRY;
        reconcile(); return true;
    }
    public boolean expand() {
        mainThread(); if (!available()) return false;
        presentation = Presentation.EXPANDED; reconcile(); return !disposed && presentation == Presentation.EXPANDED;
    }
    public void collapse() {
        mainThread(); if (disposed || presentation != Presentation.EXPANDED) return;
        presentation = Presentation.ENTRY; reconcile();
    }
    public void hideEntry() {
        mainThread(); if (disposed) return;
        presentation = Presentation.HIDDEN; reconcile();
    }
    /** Releases UI state and callbacks; never stops capture or deletes evidence. */
    public void dispose() {
        mainThread(); if (disposed) return;
        disposed = true; presentation = Presentation.HIDDEN; detachSurfaces();
        Activity activity = owner.get();
        if (activity != null) activity.getWindow().getDecorView().removeOnAttachStateChangeListener(attachment);
        owner.clear(); application.unregisterActivityLifecycleCallbacks(this);
        if (inspector != null) inspector.disposePresentation();
        inspector = null; entry = null; factory = null;
    }
    private boolean available() { Activity activity = owner.get(); return !disposed && activity != null && !activity.isDestroyed() && !activity.isFinishing(); }
    private void reconcile() {
        if (reconciling) { reconcilePending = true; return; }
        reconciling = true;
        try {
            do { reconcilePending = false; reconcilePresentation(); } while (reconcilePending && !disposed);
        } finally { reconciling = false; }
    }
    private void reconcilePresentation() {
        Activity activity = owner.get();
        if (!available() || !resumed || activity == null || !activity.getWindow().getDecorView().isAttachedToWindow()) { detachSurfaces(); return; }
        if (presentation == Presentation.EXPANDED) {
            if (entry != null) entry.detach();
            if (inspector == null) {
                InspectorView created = Objects.requireNonNull(factory.create(context), "Inspector factory returned null");
                if (disposed) { created.disposePresentation(); return; }
                inspector = created; factory = null; inspector.setOnMinimizeListener(this::collapse);
                if (presentation != Presentation.EXPANDED || !available()) { reconcilePending = true; return; }
            }
            if (window == null) createWindow(activity);
            inspector.setVisibility(View.VISIBLE);
            try { window.show(); }
            catch (WindowManager.BadTokenException | WindowManager.InvalidDisplayException unavailable) {
                // The host may lose its token between the liveness check and WindowManager admission.
                detachContentWindow(); presentation = Presentation.ENTRY; return;
            }
            Window nativeWindow = window.getWindow();
            if (nativeWindow != null) {
                nativeWindow.setLayout(-1, -1); InspectorDialogs.configureWindow(nativeWindow, InspectorPreferences.dark(context));
                inspector.requestApplyInsets();
            }
        } else {
            detachContentWindow();
            if (presentation == Presentation.ENTRY) {
                if (entry != null && !entry.matchesPreferences()) { entry.detach(); entry = null; }
                if (entry == null) entry = new InspectorFloatingEntry(context, this::expand, this::hideEntry);
                entry.attach((ViewGroup) activity.getWindow().getDecorView());
            } else if (entry != null) entry.detach();
        }
    }
    private void createWindow(Activity activity) {
        window = new Dialog(InspectorTheme.context(context), InspectorPreferences.dark(context) ? R.style.OsservioScreenDark : R.style.OsservioScreenLight) {
            // Dialog's platform back dispatcher also forwards here on API 33+.
            @SuppressWarnings("deprecation")
            @Override public void onBackPressed() { if (!inspector.navigateBack()) collapse(); }
        };
        window.setContentView(inspector); window.setCanceledOnTouchOutside(false);
        // A normal app subwindow: no overlay permission and no Activity-backed UI context.
        Window nativeWindow = window.getWindow();
        if (nativeWindow != null) {
            nativeWindow.setType(WindowManager.LayoutParams.TYPE_APPLICATION_PANEL);
            WindowManager.LayoutParams attributes = nativeWindow.getAttributes();
            attributes.token = activity.getWindow().getDecorView().getWindowToken(); nativeWindow.setAttributes(attributes);
            nativeWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            nativeWindow.setWindowAnimations(0);
        }
        inspector.setOnApplyWindowInsetsListener((root, insets) -> { InspectorDialogs.applyInsets(root, insets); return insets; });
        inspector.setAppearanceListener(dark -> {
            if (window != null && window.getWindow() != null) InspectorDialogs.configureWindow(window.getWindow(), dark);
        });
    }
    private void detachSurfaces() {
        if (entry != null) entry.detach();
        detachContentWindow();
    }
    private void detachContentWindow() {
        if (window != null) {
            InspectorWidgets.hideKeyboard(inspector); inspector.setVisibility(View.GONE);
            window.setOnCancelListener(null); window.dismiss(); window = null;
            if (inspector.getParent() instanceof ViewGroup) ((ViewGroup) inspector.getParent()).removeView(inspector);
        }
    }
    static Activity activity(Context context) {
        for (int i = 0; i < 16 && context instanceof ContextWrapper; i++) {
            if (context instanceof SessionContext) return ((SessionContext) context).session.owner.get();
            if (context instanceof Activity) return (Activity) context;
            Context base = ((ContextWrapper) context).getBaseContext(); if (base == context) break; context = base;
        }
        return null;
    }
    private static void mainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Inspector presentation requires the main thread");
    }
    private static final class SessionContext extends ContextWrapper {
        final InspectorSession session;
        SessionContext(Context base, InspectorSession session) { super(base); this.session = session; }
    }
    @Override public void onActivityPaused(Activity activity) { if (activity == owner.get()) { resumed = false; detachSurfaces(); } }
    @Override public void onActivityResumed(Activity activity) { if (activity == owner.get()) { resumed = true; reconcile(); } }
    @Override public void onActivityDestroyed(Activity activity) {
        if (activity == owner.get()) { detachSurfaces(); activity.getWindow().getDecorView().removeOnAttachStateChangeListener(attachment); owner.clear(); }
    }
    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
}
