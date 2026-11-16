package com.sandrox.osservio.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.os.Build;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;

/** Explicit bottom-navigation binding. Rendering never reparents the page or replaces its buttons. */
@android.annotation.SuppressLint("ViewConstructor")
final class InspectorTabBar extends LinearLayout implements InspectorGlassSession.Surface, Choreographer.FrameCallback {
    interface Renderer { void draw(Canvas canvas, InspectorTabBar bar); void release(); }
    interface Selection { void accept(int index); }
    private final InspectorNavigation source;
    private final InspectorGlassSession session;
    private final Selection selection;
    final InspectorTabMotion motion;
    final float density;
    private final Paint normalInk = new Paint(Paint.ANTI_ALIAS_FLAG), selectedInk = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focus = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final PathInterpolator easeOut = new PathInterpolator(0, 0, .58f, 1);
    private final int[] location = new int[2], origin = new int[2];
    private final Rect visibleWindow = new Rect();
    private int x, y, sourceX, sourceY, sourceWidth, sourceHeight;
    private long sourceVersion, inkVersion, frameTime;
    private boolean materialVisible = true, registered, capturing, ready, posted, initialized, opticalElevation;
    private float nativeElevation;
    private int pointer = -1;
    private float lastX;
    private ViewTreeObserver observed;
    private final ViewTreeObserver.OnPreDrawListener geometry = this::geometryChanged;
    private final ViewTreeObserver.OnScrollChangedListener scroll = this::contentChanged;
    Renderer renderer;

    InspectorTabBar(Context context, InspectorNavigation source, InspectorGlassSession session, int count, Selection selection) {
        super(context);
        this.source = source; this.session = session; this.selection = selection;
        density = getResources().getDisplayMetrics().density;
        motion = new InspectorTabMotion(count);
        setClipChildren(false); setClipToPadding(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        refreshAppearance();
    }

    @SuppressWarnings("deprecation") // Collection builders require API 33; the native fallback supports API 23.
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setCollectionInfo(AccessibilityNodeInfo.CollectionInfo.obtain(1, getChildCount(), false,
            AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_SINGLE));
    }

    void refreshAppearance() {
        normalInk.setColorFilter(new PorterDuffColorFilter(InspectorTheme.secondary(getContext()), PorterDuff.Mode.SRC_IN));
        selectedInk.setColorFilter(new PorterDuffColorFilter(InspectorTheme.accent(getContext()), PorterDuff.Mode.SRC_IN));
        focus.setColor(InspectorTheme.accent(getContext())); focus.setStyle(Paint.Style.STROKE); focus.setStrokeWidth(dp(2));
        inkVersion++; contentChanged();
    }
    void syncSelection(int index) {
        if (!initialized || index != motion.selected()) {
            clearPointer(); motion.sync(index, initialized && ready, getHeight() / density);
            initialized = true; kick();
        }
    }
    void activate(int index) {
        if (index < 0 || index >= getChildCount()) return;
        clearPointer();
        if (index != motion.selected()) selection.accept(index);
        if (ready) { motion.settle(index, getHeight() / density); kick(); }
    }
    void setMaterialVisible(boolean visible) {
        if (materialVisible == visible) return;
        materialVisible = visible; updateRegistration(); invalidate();
    }
    @Override public void updateRegistration() {
        if (session == null) return;
        boolean wanted = materialVisible && Build.VERSION.SDK_INT >= 33 && session.enabled()
            && isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE && isHardwareAccelerated();
        if (wanted == registered) { if (!wanted) release(); return; }
        registered = wanted;
        if (wanted) {
            source.setMaterialInvalidation(this::contentChanged);
            observed = getViewTreeObserver(); observed.addOnPreDrawListener(geometry); observed.addOnScrollChangedListener(scroll);
            session.attach(this); contentChanged();
        } else {
            source.setMaterialInvalidation(null); removeListeners(); release(); session.detach(this);
        }
    }
    private void removeListeners() {
        if (observed != null && observed.isAlive()) { observed.removeOnPreDrawListener(geometry); observed.removeOnScrollChangedListener(scroll); }
        observed = null;
    }
    private boolean geometryChanged() {
        getLocationOnScreen(location); source.getLocationOnScreen(origin);
        getWindowVisibleDisplayFrame(visibleWindow);
        if (x != location[0] || y != location[1] || sourceX != origin[0] || sourceY != origin[1]
            || sourceWidth != source.getWidth() || sourceHeight != source.getHeight()) {
            x = location[0]; y = location[1]; sourceX = origin[0]; sourceY = origin[1];
            sourceWidth = source.getWidth(); sourceHeight = source.getHeight(); contentChanged();
        }
        return true;
    }
    private void contentChanged() {
        if (capturing) return;
        sourceVersion++;
        if (registered && session.allowed()) postInvalidateOnAnimation();
    }
    @Override public void onDescendantInvalidated(View child, View target) {
        if (Build.VERSION.SDK_INT >= 26) super.onDescendantInvalidated(child, target);
        if (!capturing) inkVersion++;
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh); release(); sourceVersion++; inkVersion++;
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (changed) { inkVersion++; contentChanged(); }
    }
    @Override public void onRtlPropertiesChanged(int direction) {
        super.onRtlPropertiesChanged(direction);
        if (motion != null) { release(); inkVersion++; contentChanged(); }
    }

    @Override public void draw(Canvas canvas) {
        boolean rendered = false;
        if (eligible(canvas)) {
            int save = canvas.save();
            try {
                capturing = true;
                if (renderer == null && Build.VERSION.SDK_INT >= 33) renderer = InspectorTabRenderer.create();
                if (renderer != null) { renderer.draw(canvas, this); rendered = true; }
            } catch (RuntimeException | LinkageError | OutOfMemoryError unavailable) {
                session.fail();
            } finally { capturing = false; canvas.restoreToCount(save); }
        }
        ready = rendered;
        updateElevation(rendered);
        if (!rendered) { release(); super.draw(canvas); }
        else drawFocus(canvas);
    }
    private boolean eligible(Canvas canvas) {
        if (!registered || !session.allowed() || Build.VERSION.SDK_INT < 33 || !canvas.isHardwareAccelerated()) return false;
        if (!source.isAttachedToWindow() || source.getWindowToken() != getWindowToken() || source.getWidth() <= 0 || source.getHeight() <= 0) return false;
        for (android.view.ViewParent ancestor = getParent(); ancestor != null; ancestor = ancestor.getParent()) if (ancestor == source) return false;
        return InspectorTabGeometry.reserve(session.budget, this, getWidth(), getHeight(), density, getChildCount());
    }
    void drawSource(Canvas canvas) {
        getLocationOnScreen(location); source.getLocationOnScreen(origin);
        canvas.drawColor(InspectorTheme.canvas(getContext()));
        canvas.translate(-(location[0] - origin[0]) - source.getScrollX(), -(location[1] - origin[1]) - source.getScrollY());
        source.draw(canvas);
    }
    void drawLabels(Canvas canvas, float width, float height, boolean colored, float scale) {
        int layer = canvas.saveLayer(0, 0, width, height, colored ? selectedInk : normalInk);
        float vertical = (height - getHeight()) / 2;
        for (int i = 0; i < getChildCount(); i++) {
            InspectorTabButton button = (InspectorTabButton) getChildAt(i);
            int save = canvas.save();
            canvas.translate(button.getLeft(), button.getTop() + vertical);
            canvas.scale(scale, scale, button.getWidth() / 2f, button.getHeight() / 2f);
            button.drawContent(canvas); canvas.restoreToCount(save);
        }
        canvas.restoreToCount(layer);
    }
    private void drawFocus(Canvas canvas) {
        if (isInTouchMode()) return;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.hasFocus()) canvas.drawRoundRect(child.getLeft() + dp(2), child.getTop() + dp(2),
                child.getRight() - dp(2), child.getBottom() - dp(2), dp(25), dp(25), focus);
        }
    }
    float dp(float value) { return value * density; }
    float tabWidth() { return (getWidth() - dp(8)) / getChildCount(); }
    boolean dark() { return InspectorPreferences.dark(getContext()); }
    float visualPosition() { return getLayoutDirection() == LAYOUT_DIRECTION_RTL ? getChildCount() - 1 - (float) motion.position.value : (float) motion.position.value; }
    float panelOffset() {
        float fraction = Math.max(-1, Math.min(1, (float) motion.panel.value / getWidth()));
        return dp(4) * Math.signum(fraction) * easeOut.getInterpolation(Math.abs(fraction));
    }
    float lensX() {
        float desired = dp(4) + visualPosition() * tabWidth() + panelOffset();
        if (visibleWindow.isEmpty()) return desired;
        return InspectorTabGeometry.constrainLens(desired, tabWidth(), lensScaleX(),
            visibleWindow.left - x + dp(1), visibleWindow.right - x - dp(1));
    }
    float lensScaleX() { return (float) motion.scaleX.value / (1 - Math.max(-.2f, Math.min(.2f, (float) motion.speed.value / 10 * .75f))); }
    float lensScaleY() { return (float) motion.scaleY.value * (1 - Math.max(-.2f, Math.min(.2f, (float) motion.speed.value / 10 * .25f))); }
    long sourceVersion() { return sourceVersion; }
    long inkVersion() { return inkVersion; }
    boolean materialReady() { return ready; }
    private void updateElevation(boolean enhanced) {
        if (enhanced == opticalElevation) return;
        if (enhanced) { nativeElevation = getElevation(); super.setElevation(0); }
        else super.setElevation(nativeElevation);
        opticalElevation = enhanced;
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (!ready) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            lastX = event.getX();
            pointer = event.getPointerId(0);
            // Other tabs retain ordinary native click routing. Only the selected lens owns a drag.
            if (event.getX() >= lensX() && event.getX() <= lensX() + tabWidth()) {
                motion.hold(getHeight() / density); parentIntercept(true); kick(); return true;
            }
        }
        return motion.held();
    }
    @android.annotation.SuppressLint("ClickableViewAccessibility") // Gesture completion calls the existing child's performClick; the bar is not a second action node.
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!ready) return super.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: return true;
            case MotionEvent.ACTION_MOVE:
                int at = event.findPointerIndex(pointer);
                if (at < 0) { cancelGesture(); return true; }
                float value = event.getX(at), delta = value - lastX; lastX = value;
                if (motion.held()) {
                    motion.drag(delta / tabWidth() * (getLayoutDirection() == LAYOUT_DIRECTION_RTL ? -1 : 1), delta); invalidate(); kick();
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerId(event.getActionIndex()) == pointer) {
                    int next = event.getActionIndex() == 0 ? 1 : 0;
                    if (next < event.getPointerCount()) { pointer = event.getPointerId(next); lastX = event.getX(next); }
                    else cancelGesture();
                }
                return true;
            case MotionEvent.ACTION_UP:
                int destination = motion.destination();
                boolean inside = event.getY() >= -ViewConfiguration.get(getContext()).getScaledTouchSlop()
                    && event.getY() <= getHeight() + ViewConfiguration.get(getContext()).getScaledTouchSlop();
                if (motion.held() && inside) {
                    motion.settle(destination, getHeight() / density); clearPointer();
                    getChildAt(destination).performClick(); kick();
                } else cancelGesture();
                return true;
            case MotionEvent.ACTION_CANCEL: cancelGesture(); return true;
            default: return true;
        }
    }
    private void cancelGesture() { motion.cancel(getHeight() / density); clearPointer(); kick(); }
    private void clearPointer() { pointer = -1; parentIntercept(false); }
    private void parentIntercept(boolean disallow) { if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(disallow); }
    private void kick() {
        if (!posted && registered && ready && session.allowed() && motion.running()) {
            posted = true; Choreographer.getInstance().postFrameCallback(this);
        }
    }
    @Override public void doFrame(long now) {
        posted = false;
        if (!registered || !session.allowed() || !isShown()) { stopMotion(); return; }
        double seconds = frameTime == 0 ? 0 : Math.min(.064, (now - frameTime) / 1e9) / session.animationScale();
        frameTime = now; motion.step(seconds); invalidate();
        if (motion.running()) kick(); else frameTime = 0;
    }
    private void stopMotion() {
        if (posted) Choreographer.getInstance().removeFrameCallback(this);
        posted = false; frameTime = 0; clearPointer(); motion.stop();
    }
    @Override public void release() {
        if (motion == null) return;
        ready = false; stopMotion(); updateElevation(false);
        Renderer previous = renderer; renderer = null; session.budget.release(this);
        try { if (previous != null) previous.release(); }
        catch (RuntimeException | LinkageError | OutOfMemoryError unavailable) { session.fail(); }
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); updateRegistration(); }
    @Override protected void onDetachedFromWindow() {
        registered = false; source.setMaterialInvalidation(null); removeListeners(); release(); session.detach(this); super.onDetachedFromWindow();
    }
    @Override protected void onVisibilityChanged(View view, int visibility) { super.onVisibilityChanged(view, visibility); updateRegistration(); }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); updateRegistration(); }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused && motion.held()) cancelGesture();
    }
}
