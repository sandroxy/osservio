package com.sandrox.osservio.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.os.Build;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;

/** An optical background for the existing tab controls; geometry, input and semantics stay native. */
@android.annotation.SuppressLint("ViewConstructor")
final class InspectorGlassBar extends LinearLayout {
    interface Renderer {
        void draw(Canvas canvas, View source, View target, float radius, int tint);
        void release();
    }
    private final InspectorGlassSession session;
    private final InspectorNavigation source;
    private boolean registered, capturing;
    private boolean materialVisible = true;
    private final android.graphics.Paint readingSurface = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final int[] position = new int[2], origin = new int[2];
    private int x, y, sourceX, sourceY, sourceWidth, sourceHeight;
    Renderer renderer;
    private ViewTreeObserver observed;
    private final ViewTreeObserver.OnScrollChangedListener scroll = this::contentChanged;
    private final ViewTreeObserver.OnPreDrawListener geometry = this::geometryChanged;
    private boolean geometryChanged() {
        getLocationOnScreen(position); source.getLocationOnScreen(origin);
        if (x != position[0] || y != position[1] || sourceX != origin[0] || sourceY != origin[1]
            || sourceWidth != source.getWidth() || sourceHeight != source.getHeight()) {
            x = position[0]; y = position[1]; sourceX = origin[0]; sourceY = origin[1];
            sourceWidth = source.getWidth(); sourceHeight = source.getHeight(); contentChanged();
        }
        return true;
    }
    InspectorGlassBar(Context context, InspectorNavigation source, InspectorGlassSession session) {
        super(context); this.source = source; this.session = session; setWillNotDraw(false);
        // Keep the optical edge clear while protecting the native icons and small labels.
    }
    void setMaterialVisible(boolean visible) {
        if (materialVisible == visible) return;
        materialVisible = visible; updateRegistration(); invalidate();
    }
    void updateRegistration() {
        if (session == null) return; // Visibility callbacks may arrive during the View constructor.
        boolean wanted = materialVisible && Build.VERSION.SDK_INT >= 33 && session.enabled() && isAttachedToWindow()
            && isShown() && getWindowVisibility() == VISIBLE && isHardwareAccelerated();
        if (wanted == registered) { if (!wanted) release(); return; }
        registered = wanted;
        if (wanted) {
            source.setMaterialInvalidation(this::contentChanged);
            observed = getViewTreeObserver(); observed.addOnScrollChangedListener(scroll); observed.addOnPreDrawListener(geometry);
            session.attach(this); invalidate();
        } else {
            source.setMaterialInvalidation(null);
            if (observed != null && observed.isAlive()) { observed.removeOnScrollChangedListener(scroll); observed.removeOnPreDrawListener(geometry); }
            observed = null; release(); session.detach(this);
        }
    }
    private void contentChanged() {
        if (registered && !capturing && session.allowed()) postInvalidateOnAnimation();
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); updateRegistration(); }
    @Override protected void onDetachedFromWindow() {
        // View still reports attached during this callback; remove the binding explicitly.
        registered = false; source.setMaterialInvalidation(null);
        if (observed != null && observed.isAlive()) { observed.removeOnScrollChangedListener(scroll); observed.removeOnPreDrawListener(geometry); }
        observed = null; release(); session.detach(this); super.onDetachedFromWindow();
    }
    @Override protected void onVisibilityChanged(View view, int visibility) { super.onVisibilityChanged(view, visibility); updateRegistration(); }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); updateRegistration(); }
    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (height > 0) {
            int color = InspectorTheme.surface(getContext()) & 0xFFFFFF;
            readingSurface.setShader(new android.graphics.LinearGradient(0, 0, 0, height,
                new int[] {color, color | 0xF0000000, color | 0xF0000000, color},
                new float[] {0, .18f, .82f, 1}, android.graphics.Shader.TileMode.CLAMP));
        }
        invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!eligible(canvas)) { release(); return; }
        try {
            if (renderer == null && Build.VERSION.SDK_INT >= 33) renderer = InspectorGlassRenderer.create();
            if (renderer == null) return;
            capturing = true;
            int tint = InspectorPreferences.dark(getContext()) ? (InspectorTheme.canvas(getContext()) & 0xFFFFFF) | 0x14000000 : 0;
            renderer.draw(canvas, source, this, InspectorTheme.dp(getContext(), 32), tint);
            float inset = InspectorTheme.dp(getContext(), 1);
            float radius = Math.max(0, Math.min(InspectorTheme.dp(getContext(), 32), getHeight() / 2f) - inset);
            canvas.drawRoundRect(inset, inset, getWidth() - inset, getHeight() - inset, radius, radius, readingSurface);
        } catch (RuntimeException | LinkageError | OutOfMemoryError unavailable) {
            session.fail();
            // The exact native Drawable is never removed: retain padding, outline, elevation and theme.
            if (getBackground() != null) getBackground().draw(canvas);
        } finally { capturing = false; }
    }
    private boolean eligible(Canvas canvas) {
        if (!registered || !session.allowed() || Build.VERSION.SDK_INT < 33 || !canvas.isHardwareAccelerated()) return false;
        if (!source.isAttachedToWindow() || source.getWindowToken() != getWindowToken() || source.getWidth() <= 0 || source.getHeight() <= 0) return false;
        for (android.view.ViewParent ancestor = getParent(); ancestor != null; ancestor = ancestor.getParent()) if (ancestor == source) return false;
        return session.budget.reserve(this, getWidth(), getHeight());
    }
    void release() {
        Renderer previous = renderer; renderer = null; session.budget.release(this);
        try { if (previous != null) previous.release(); }
        catch (RuntimeException | LinkageError | OutOfMemoryError unavailable) { session.fail(); }
    }
}
