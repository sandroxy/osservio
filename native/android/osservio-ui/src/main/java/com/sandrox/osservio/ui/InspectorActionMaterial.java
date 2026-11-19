package com.sandrox.osservio.ui;

import android.graphics.Canvas;
import android.os.Build;
import android.view.Choreographer;
import android.view.View;

/** Small control material with bounded lifetime. It never receives or dispatches a touch event. */
final class InspectorActionMaterial implements InspectorGlassSession.Surface, Choreographer.FrameCallback {
    interface Renderer { void draw(Canvas canvas, View target, boolean prominent, float press); void release(); }
    private final View view;
    private final InspectorNavigation navigation;
    private final InspectorGlassSession session;
    private final InspectorSpring press = new InspectorSpring(0, .7, 400, .001);
    private boolean enabled, prominent, attached, posted, ready;
    private long frameTime;
    private float nativeElevation;
    Renderer renderer;

    InspectorActionMaterial(View view) {
        this.view = view; navigation = InspectorNavigation.from(view.getContext());
        session = navigation == null ? null : navigation.materials;
        if (session != null) session.watch(this);
    }
    void configure(boolean prominent) { enabled = true; this.prominent = prominent; updateRegistration(); view.invalidate(); }
    void setEnabled(boolean value) { enabled = value; updateRegistration(); view.invalidate(); }
    @Override public void updateRegistration() {
        boolean wanted = enabled && session != null && Build.VERSION.SDK_INT >= 33 && session.enabled()
            && view.isAttachedToWindow() && view.isShown() && view.hasWindowFocus()
            && view.getWindowVisibility() == View.VISIBLE && view.isHardwareAccelerated() && navigation.materialVisible(view);
        if (wanted == attached) { if (!wanted) release(); return; }
        attached = wanted;
        if (wanted) { session.attach(this); view.invalidate(); }
        else { release(); session.detach(this); }
    }
    void draw(Canvas canvas) {
        if (!attached || session == null || !session.allowed() || !canvas.isHardwareAccelerated() || !view.isEnabled()
            || (!view.isInTouchMode() && view.hasFocus()) || Build.VERSION.SDK_INT < 33) {
            release(); return;
        }
        int save = canvas.save();
        try {
            if (!session.budget.reserve(this, view.getWidth(), view.getHeight())) { release(); return; }
            if (renderer == null) renderer = InspectorActionRenderer.create();
            renderer.draw(canvas, view, prominent, (float) press.value);
            if (!ready) { nativeElevation = view.getElevation(); view.setElevation(InspectorTheme.dp(view.getContext(), 1)); }
            ready = true;
        } catch (RuntimeException | LinkageError | OutOfMemoryError unavailable) { session.fail(); }
        finally { canvas.restoreToCount(save); }
    }
    void stateChanged() {
        if (!view.isEnabled()) { release(); return; }
        press.target = ready && view.isPressed() ? 1 : 0;
        if (ready && !posted && press.running()) {
            frameTime = System.nanoTime(); posted = true; Choreographer.getInstance().postFrameCallback(this);
        }
        view.invalidate();
    }
    float scale() { return ready ? 1 - .04f * (float) press.value : 1; }
    @Override public void doFrame(long now) {
        posted = false;
        if (!attached || !session.allowed() || !view.isShown()) { release(); return; }
        now = Math.max(now, frameTime);
        press.step((now - frameTime) / 1e9 / session.animationScale()); frameTime = now; view.invalidate();
        if (press.running()) { posted = true; Choreographer.getInstance().postFrameCallback(this); }
    }
    void detach() { attached = false; release(); if (session != null) session.detach(this); }
    @Override public void release() {
        if (posted) Choreographer.getInstance().removeFrameCallback(this);
        if (ready) view.setElevation(nativeElevation);
        posted = false; ready = false; frameTime = 0; press.snap(0);
        Renderer previous = renderer; renderer = null;
        if (session != null) session.budget.release(this);
        try { if (previous != null) previous.release(); }
        catch (RuntimeException | LinkageError | OutOfMemoryError unavailable) { session.fail(); }
    }
    @Override public void invalidate() { view.invalidate(); }
}
