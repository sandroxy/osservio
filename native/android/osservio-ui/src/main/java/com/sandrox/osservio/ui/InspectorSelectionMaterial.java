package com.sandrox.osservio.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.os.Build;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.PathInterpolator;
import android.view.ViewGroup;
import android.view.ViewConfiguration;

/** Shared optical selection and gesture state; native children own commands and accessibility. */
final class InspectorSelectionMaterial implements InspectorGlassSession.Surface, Choreographer.FrameCallback {
    interface Renderer { void draw(Canvas canvas, InspectorSelectionMaterial bar); void release(); }
    interface Content {
        boolean ready();
        void observe(Runnable invalidated);
        void drawBackdrop(Canvas canvas);
        void drawLabel(Canvas canvas, int index);
        default boolean moved() { return false; }
    }
    private final ViewGroup view;
    private final Content content;
    private final boolean scrollable;
    private int touchSlop;
    private final InspectorGlassSession session;
    final InspectorTabMotion motion;
    float density;
    private final Paint normalInk = new Paint(Paint.ANTI_ALIAS_FLAG), selectedInk = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focus = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final PathInterpolator easeOut = new PathInterpolator(0, 0, .58f, 1);
    private final int[] location = new int[2];
    private final Rect visibleWindow = new Rect();
    private final Rect drawingClip = new Rect();
    private int x, y, width, height;
    private long sourceVersion, inkVersion, frameTime;
    private boolean materialVisible = true, registered, capturing, ready, posted, initialized, opticalElevation;
    private float nativeElevation;
    private int pointer = -1;
    private float lastX, downX, downY;
    private boolean pending, dragging, parentClaimed;
    private ViewTreeObserver observed;
    private final ViewTreeObserver.OnPreDrawListener geometry = this::geometryChanged;
    private final ViewTreeObserver.OnScrollChangedListener scroll = this::contentChanged;
    Renderer renderer;

    InspectorSelectionMaterial(ViewGroup view, InspectorGlassSession session, int count, boolean scrollable, Content content) {
        this.view = view; this.session = session; this.content = content; this.scrollable = scrollable;
        density = view.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        motion = new InspectorTabMotion(count);
        if (session != null) session.watch(this);
        refreshAppearance();
    }

    void refreshAppearance() {
        normalInk.setColorFilter(new PorterDuffColorFilter(InspectorTheme.secondary(view.getContext()), PorterDuff.Mode.SRC_IN));
        selectedInk.setColorFilter(new PorterDuffColorFilter(InspectorTheme.accent(view.getContext()), PorterDuff.Mode.SRC_IN));
        focus.setColor(InspectorTheme.accent(view.getContext())); focus.setStyle(Paint.Style.STROKE); focus.setStrokeWidth(dp(2));
        inkVersion++; contentChanged();
    }
    void syncSelection(int index) {
        if (!initialized || index != motion.selected()) {
            clearPointer(); motion.sync(index, initialized && ready, view.getHeight() / density);
            initialized = true; kick();
        }
    }
    void setMaterialVisible(boolean visible) {
        if (materialVisible == visible) return;
        materialVisible = visible; updateRegistration(); invalidate();
    }
    @Override public void updateRegistration() {
        if (session == null) return;
        boolean wanted = materialVisible && Build.VERSION.SDK_INT >= 33 && session.enabled()
            && content.ready() && view.isAttachedToWindow() && view.isShown() && view.hasWindowFocus() && view.getWindowVisibility() == View.VISIBLE && view.isHardwareAccelerated();
        if (wanted == registered) { if (!wanted) release(); return; }
        registered = wanted;
        if (wanted) {
            content.observe(this::contentChanged);
            observed = view.getViewTreeObserver(); observed.addOnPreDrawListener(geometry); observed.addOnScrollChangedListener(scroll);
            session.attach(this); contentChanged();
        } else {
            content.observe(null); removeListeners(); release(); session.detach(this);
        }
    }
    private void removeListeners() {
        if (observed != null && observed.isAlive()) { observed.removeOnPreDrawListener(geometry); observed.removeOnScrollChangedListener(scroll); }
        observed = null;
    }
    private boolean geometryChanged() {
        updateRegistration();
        if (!registered) return true;
        view.getLocationOnScreen(location);
        view.getWindowVisibleDisplayFrame(visibleWindow);
        if (x != location[0] || y != location[1] || content.moved()) {
            x = location[0]; y = location[1]; contentChanged();
        }
        return true;
    }
    private void contentChanged() {
        if (capturing) return;
        sourceVersion++;
        if (registered && session.allowed()) view.postInvalidateOnAnimation();
    }
    void inkChanged() { if (!capturing) { inkVersion++; contentChanged(); } }
    void geometryChangedByLayout() {
        float nextDensity = view.getResources().getDisplayMetrics().density;
        if (width != view.getWidth() || height != view.getHeight() || nextDensity != density) {
            release(); width = view.getWidth(); height = view.getHeight(); density = nextDensity;
            touchSlop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        }
        inkVersion++; contentChanged();
    }
    void directionChanged() { release(); inkVersion++; contentChanged(); }

    boolean draw(Canvas canvas) {
        boolean rendered = false;
        canvas.getClipBounds(drawingClip);
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
        if (!rendered) release();
        else drawFocus(canvas);
        return rendered;
    }
    private boolean eligible(Canvas canvas) {
        if (!registered || session == null || !session.allowed() || Build.VERSION.SDK_INT < 33 || !canvas.isHardwareAccelerated()) return false;
        if (!content.ready()) return false;
        return InspectorTabGeometry.reserve(session.budget, this, view.getWidth(), view.getHeight(), density, view.getChildCount(), scrollable);
    }
    void drawSource(Canvas canvas) { content.drawBackdrop(canvas); }
    void drawLabels(Canvas canvas, float width, float height, boolean colored, float scale) {
        int layer = canvas.saveLayer(0, 0, width, height, colored ? selectedInk : normalInk);
        float vertical = (height - view.getHeight()) / 2;
        for (int i = 0; i < view.getChildCount(); i++) {
            View button = view.getChildAt(i);
            int save = canvas.save();
            canvas.translate(button.getLeft(), button.getTop() + vertical);
            canvas.scale(scale, scale, button.getWidth() / 2f, button.getHeight() / 2f);
            content.drawLabel(canvas, i); canvas.restoreToCount(save);
        }
        canvas.restoreToCount(layer);
    }
    private void drawFocus(Canvas canvas) {
        if (view.isInTouchMode()) return;
        for (int i = 0; i < view.getChildCount(); i++) {
            View child = view.getChildAt(i);
            if (child.hasFocus()) canvas.drawRoundRect(child.getLeft() + dp(2), child.getTop() + dp(2),
                child.getRight() - dp(2), child.getBottom() - dp(2), dp(25), dp(25), focus);
        }
    }
    int getWidth() { return view.getWidth(); }
    int getHeight() { return view.getHeight(); }
    boolean segmented() { return scrollable; }
    float dp(float value) { return value * density; }
    float tabWidth() { return (view.getWidth() - dp(8)) / view.getChildCount(); }
    boolean dark() { return InspectorPreferences.dark(view.getContext()); }
    float visualPosition() { return view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? view.getChildCount() - 1 - (float) motion.position.value : (float) motion.position.value; }
    float panelOffset() {
        float fraction = Math.max(-1, Math.min(1, (float) motion.panel.value / view.getWidth()));
        return dp(4) * Math.signum(fraction) * easeOut.getInterpolation(Math.abs(fraction));
    }
    float lensX() {
        float desired = dp(4) + visualPosition() * tabWidth() + panelOffset();
        if (visibleWindow.isEmpty()) return desired;
        float left = visibleWindow.left - x + dp(1), right = visibleWindow.right - x - dp(1);
        if (scrollable && !drawingClip.isEmpty()) {
            left = Math.max(left, drawingClip.left + dp(1)); right = Math.min(right, drawingClip.right - dp(1));
        }
        return InspectorTabGeometry.constrainLens(desired, tabWidth(), lensScaleX(),
            left, right);
    }
    float lensScaleX() {
        float scale = (float) motion.scaleX.value / (1 - Math.max(-.2f, Math.min(.2f, (float) motion.speed.value / 10 * .75f)));
        return scrollable && !drawingClip.isEmpty() ? Math.min(scale, Math.max(1, (drawingClip.width() - dp(2)) / tabWidth())) : scale;
    }
    float lensScaleY() {
        float scale = (float) motion.scaleY.value * (1 - Math.max(-.2f, Math.min(.2f, (float) motion.speed.value / 10 * .25f)));
        if (!scrollable || drawingClip.isEmpty()) return scale;
        // Readers can put a selector inside a clipped scrolling header. Respect
        // that real viewport instead of cutting off the enlarged lens or unclipping text.
        float center = view.getHeight() / 2f;
        float available = 2 * Math.min(center - drawingClip.top, drawingClip.bottom - center) - dp(2);
        return Math.min(scale, Math.max(1, available / (view.getHeight() - dp(8))));
    }
    long sourceVersion() { return sourceVersion; }
    long inkVersion() { return inkVersion; }
    boolean materialReady() { return ready; }
    private void updateElevation(boolean enhanced) {
        if (scrollable) return;
        if (enhanced == opticalElevation) return;
        if (enhanced) { nativeElevation = view.getElevation(); view.setElevation(0); }
        else view.setElevation(nativeElevation);
        opticalElevation = enhanced;
    }

    boolean intercept(MotionEvent event) {
        if (!ready || !view.isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = downX = event.getX(); downY = event.getY(); pointer = event.getPointerId(0);
                if (event.getX() >= lensX() && event.getX() <= lensX() + tabWidth()) {
                    if (frameTime == 0) frameTime = event.getEventTime() * 1_000_000L;
                    pending = scrollable; dragging = !scrollable;
                    motion.hold(view.getHeight() / density); if (!scrollable) parentIntercept(true);
                    kick(); return !scrollable;
                }
                return false;
            case MotionEvent.ACTION_MOVE:
                int at = event.findPointerIndex(pointer);
                if (pending && at < 0) { cancelGesture(); return false; }
                if (pending && at >= 0) {
                    float dx = event.getX(at) - downX, dy = event.getY(at) - downY;
                    if (Math.abs(dy) > touchSlop && Math.abs(dy) >= Math.abs(dx)) { cancelGesture(); return false; }
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                        pending = false; dragging = true; parentIntercept(true); return true;
                    }
                }
                return dragging;
            case MotionEvent.ACTION_POINTER_UP:
                if (pending && event.getPointerId(event.getActionIndex()) == pointer) {
                    int next = event.getActionIndex() == 0 ? 1 : 0;
                    if (next < event.getPointerCount()) {
                        pointer = event.getPointerId(next); lastX = downX = event.getX(next); downY = event.getY(next);
                    } else cancelGesture();
                }
                return dragging;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pending) cancelGesture();
                return dragging;
            default: return dragging;
        }
    }
    boolean touch(MotionEvent event) {
        if (!ready) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: return true;
            case MotionEvent.ACTION_MOVE:
                int at = event.findPointerIndex(pointer);
                if (at < 0) { cancelGesture(); return true; }
                dragTo(event.getX(at));
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerId(event.getActionIndex()) == pointer) {
                    int next = event.getActionIndex() == 0 ? 1 : 0;
                    if (next < event.getPointerCount()) { pointer = event.getPointerId(next); lastX = event.getX(next); }
                    else cancelGesture();
                }
                return true;
            case MotionEvent.ACTION_UP:
                int released = event.findPointerIndex(pointer);
                if (motion.held() && released >= 0) {
                    // A tracked drag commits on release, even outside the bar. Only
                    // cancellation restores the original tab; include the final coordinate.
                    dragTo(event.getX(released));
                    int destination = motion.destination();
                    if (!view.getChildAt(destination).isEnabled()) { cancelGesture(); return true; }
                    motion.settle(destination, view.getHeight() / density); clearPointer();
                    view.getChildAt(destination).performClick(); kick();
                } else cancelGesture();
                return true;
            case MotionEvent.ACTION_CANCEL: cancelGesture(); return true;
            default: return true;
        }
    }
    private void dragTo(float value) {
        float delta = value - lastX; lastX = value;
        if (motion.held()) {
            motion.drag(delta / tabWidth() * (view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -1 : 1), delta);
            invalidate(); kick();
        }
    }
    private void cancelGesture() { motion.cancel(view.getHeight() / density); clearPointer(); kick(); }
    private void clearPointer() { pointer = -1; pending = false; dragging = false; parentIntercept(false); }
    private void parentIntercept(boolean disallow) {
        if (parentClaimed == disallow) return;
        parentClaimed = disallow;
        if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(disallow);
    }
    private void kick() {
        if (!posted && registered && ready && session.allowed() && motion.running()) {
            posted = true; Choreographer.getInstance().postFrameCallback(this);
        }
    }
    @Override public void doFrame(long now) {
        posted = false;
        if (!registered || session == null || !session.allowed() || !view.isShown()) { stopMotion(); return; }
        // The analytic springs remain stable across missed frames. Truncating elapsed
        // time would stretch a short transition indefinitely on a busy renderer.
        // Input can be dispatched in a frame whose vsync precedes the event.
        now = Math.max(now, frameTime);
        double seconds = frameTime == 0 ? 0 : (now - frameTime) / 1e9 / session.animationScale();
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
        Renderer previous = renderer; renderer = null; if (session != null) session.budget.release(this);
        try { if (previous != null) previous.release(); }
        catch (RuntimeException | LinkageError | OutOfMemoryError unavailable) { session.fail(); }
    }
    void detach() {
        registered = false; content.observe(null); removeListeners(); release();
        if (session != null) session.detach(this);
    }
    void focusChanged(boolean focused) {
        if (!focused && motion.held()) cancelGesture();
        updateRegistration(); invalidate();
    }
    @Override public void invalidate() { view.invalidate(); }
}
