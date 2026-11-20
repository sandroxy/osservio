package com.sandrox.osservio.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

/** Replays only the part of a moving choice drawn into the page's existing margins. */
final class InspectorSegmentOverflow extends Drawable {
    private final ViewGroup owner;
    private final View viewport;
    private final InspectorSegmentControl control;
    private final int[] origin = new int[2], location = new int[2];
    private final Rect visible = new Rect();
    private int left, top;
    private boolean attached;

    InspectorSegmentOverflow(InspectorSegmentControl control, ViewGroup owner, View viewport) {
        this.control = control;
        this.owner = owner;
        this.viewport = viewport;
    }

    void update(int marginX, int marginY) {
        if (!control.getLocalVisibleRect(visible) || !viewport.getLocalVisibleRect(visible)) {
            clear();
            return;
        }
        owner.getLocationOnScreen(origin);
        viewport.getLocationOnScreen(location);
        int viewportY = location[1] - origin[1];
        int viewportTop = viewportY + visible.top, viewportBottom = viewportY + visible.bottom;
        control.getLocationOnScreen(location);
        int previousLeft = left, previousTop = top;
        left = location[0] - origin[0];
        top = location[1] - origin[1];
        int bottom = top + control.getHeight();
        // A stationary reader header may use the page's top inset. Once scrolled,
        // keep the drawing inside the visible header, clear of the reading pane.
        int limitTop = viewport.canScrollVertically(-1) ? viewportTop : 0;
        int limitBottom = viewport == owner ? owner.getHeight() : viewportBottom;
        visible.set(Math.max(0, left - marginX), Math.max(limitTop, top - marginY),
            Math.min(owner.getWidth(), left + control.getWidth() + marginX), Math.min(limitBottom, bottom + marginY));
        boolean moved = left != previousLeft || top != previousTop || !visible.equals(getBounds());
        setBounds(visible);
        if (!attached) owner.getOverlay().add(this);
        else if (moved) invalidateSelf();
        attached = true;
    }

    @Override public void draw(Canvas canvas) {
        if (!attached || android.os.Build.VERSION.SDK_INT < 33 || !canvas.isHardwareAccelerated() || !control.isShown()) return;
        int save = canvas.save();
        try {
            canvas.clipRect(getBounds());
            canvas.translate(left, top);
            canvas.clipOutRect(0, 0, control.getWidth(), control.getHeight());
            control.material.drawOverflow(canvas);
        } finally { canvas.restoreToCount(save); }
    }

    void clear() {
        if (!attached) return;
        attached = false;
        owner.getOverlay().remove(this);
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter filter) { }
    @SuppressWarnings("deprecation")
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
