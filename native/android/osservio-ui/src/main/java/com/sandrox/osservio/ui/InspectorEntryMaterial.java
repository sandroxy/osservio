package com.sandrox.osservio.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.FrameLayout;

/** A local backdrop for the floating button, outside the Activity content it samples. */
final class InspectorEntryMaterial implements InspectorGlassSession.Surface, ViewTreeObserver.OnPreDrawListener {
    interface Renderer {
        void draw(Canvas canvas, View source, View target, int left, int top, int base, int tint);
        void release();
    }
    private static final int MAX_CONTENT_CHECKS = 96;
    private final View target;
    private final InspectorGlassSession session;
    private final int tint, fallback;
    private final int[] origin = new int[2], position = new int[2];
    private final Rect region = new Rect(), bounds = new Rect();
    private Window window;
    private View source;
    private ViewTreeObserver observer;
    private boolean ready;
    private int left, top, base, remainingViews;
    Renderer renderer;

    InspectorEntryMaterial(View target, InspectorGlassSession session) {
        this.target = target; this.session = session;
        fallback = InspectorTheme.surface(target.getContext());
        // Light appearance uses a neutral body. Dark appearance has no custom tint;
        // the renderer keeps the upstream regular defaults for that appearance.
        tint = InspectorTheme.dark(target.getContext()) ? Color.TRANSPARENT : (fallback & 0xFFFFFF) | 0xB3000000;
    }
    void bind(Window window) {
        if (this.window != window) {
            unregister();
            this.window = window;
        }
        updateRegistration();
    }
    @Override public void updateRegistration() {
        boolean visible = window != null && Build.VERSION.SDK_INT >= 33 && session.enabled()
            && target.isAttachedToWindow() && target.isShown() && target.getWindowVisibility() == View.VISIBLE
            && target.isHardwareAccelerated();
        if (!visible) { unregister(); return; }
        if (observer != null) return;
        observer = target.getViewTreeObserver();
        observer.addOnPreDrawListener(this);
        session.attach(this); target.invalidate();
    }
    private void unregister() {
        if (observer == null) return;
        if (observer.isAlive()) observer.removeOnPreDrawListener(this);
        observer = null; source = null; ready = false;
        release(); session.detach(this); target.invalidate();
    }
    @Override public boolean onPreDraw() {
        if (observer == null) return true;
        boolean wasReady = ready;
        ready = session.allowed() && (window.getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) == 0
            && target.getLayerType() != View.LAYER_TYPE_SOFTWARE;
        if (ready && (source == null || !source.isAttachedToWindow())) source = window.findViewById(android.R.id.content);
        // The entry is a direct child of the decor. Only its separate, standard Activity content can be a source.
        ready = ready && source instanceof FrameLayout && target.getParent() == window.peekDecorView()
            && source != target.getParent() && source.getWindowToken() == target.getWindowToken() && source.isShown()
            && target.getWidth() > 0 && target.getHeight() > 0 && target.getGlobalVisibleRect(region)
            && source.getGlobalVisibleRect(bounds) && bounds.contains(region) && sourceAligned();
        if (!ready) {
            if (wasReady) { release(); target.invalidate(); }
            return true;
        }
        target.getLocationInWindow(position); source.getLocationInWindow(origin);
        int x = position[0] - origin[0], y = position[1] - origin[1];
        Drawable background = window.peekDecorView().getBackground();
        int color = background instanceof ColorDrawable ? ((ColorDrawable) background).getColor() : fallback;
        if (Color.alpha(color) != 255) color = fallback;
        if (!wasReady || left != x || top != y || base != color || source.isDirty()) target.invalidate();
        left = x; top = y; base = color;
        return true;
    }
    private boolean sourceAligned() {
        // The recording uses window translations. During a host scale, rotation or crossfade use the native fill.
        for (View view = source; view != target.getParent();) {
            if (view.getAlpha() != 1 || view.getScaleX() != 1 || view.getScaleY() != 1 || view.getRotation() != 0
                || view.getRotationX() != 0 || view.getRotationY() != 0 || !(view.getParent() instanceof View)) return false;
            view = (View) view.getParent();
        }
        return true;
    }
    void draw(Canvas canvas) {
        if (!ready || !session.allowed() || !canvas.isHardwareAccelerated() || Build.VERSION.SDK_INT < 33
            || (window.getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0) {
            release(); return;
        }
        try {
            // Inspect only branches intersecting this small recording. A dense or externally composed
            // area keeps the ordinary button without changing any host View's renderer.
            remainingViews = MAX_CONTENT_CHECKS;
            region.inset(-InspectorGlassBudget.MARGIN, -InspectorGlassBudget.MARGIN);
            if (!ordinaryContent(source) || !session.budget.reserve(this, target.getWidth(), target.getHeight())) {
                release(); return;
            }
            if (renderer == null) renderer = InspectorGlassRenderer.create();
            renderer.draw(canvas, source, target, left, top, base, tint);
        } catch (RuntimeException | LinkageError | OutOfMemoryError unavailable) {
            session.fail();
        } finally {
            region.inset(InspectorGlassBudget.MARGIN, InspectorGlassBudget.MARGIN);
        }
    }
    private boolean ordinaryContent(View view) {
        if (view instanceof SurfaceView || view instanceof TextureView || view instanceof WebView
            || view.getLayerType() == View.LAYER_TYPE_SOFTWARE) return false;
        if (!(view instanceof ViewGroup)) return true;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            if (--remainingViews < 0) return false;
            View child = group.getChildAt(index);
            if (child.getVisibility() == View.VISIBLE && child.getAlpha() > 0 && child.getGlobalVisibleRect(bounds)
                && Rect.intersects(region, bounds) && !ordinaryContent(child)) return false;
        }
        return true;
    }
    boolean tracksMotion() { return observer != null && session.allowed(); }
    @Override public void invalidate() { target.invalidate(); }
    @Override public void release() {
        Renderer previous = renderer; renderer = null; session.budget.release(this);
        try { if (previous != null) previous.release(); }
        catch (RuntimeException | LinkageError | OutOfMemoryError unavailable) { session.fail(); }
    }
}
