package com.sandrox.osservio.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;

/** Bottom navigation owns its native buttons; the material only previews their selection. */
@android.annotation.SuppressLint("ViewConstructor")
final class InspectorTabBar extends LinearLayout {
    interface Selection { void accept(int index); }
    final InspectorSelectionMaterial material;
    final InspectorTabMotion motion;
    private final Selection selection;

    InspectorTabBar(Context context, InspectorNavigation source, InspectorGlassSession session, int count, Selection selection) {
        super(context); this.selection = selection;
        setClipChildren(false); setClipToPadding(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        material = new InspectorSelectionMaterial(this, session, count, false, new InspectorSelectionMaterial.Content() {
            private final int[] target = new int[2], origin = new int[2];
            private int x, y, width, height;
            @Override public boolean ready() {
                return source.isAttachedToWindow() && source.getWindowToken() == getWindowToken()
                    && source.getWidth() > 0 && source.getHeight() > 0;
            }
            @Override public void observe(Runnable changed) { source.setMaterialInvalidation(changed); }
            @Override public boolean moved() {
                source.getLocationOnScreen(origin);
                boolean changed = x != origin[0] || y != origin[1] || width != source.getWidth() || height != source.getHeight();
                x = origin[0]; y = origin[1]; width = source.getWidth(); height = source.getHeight(); return changed;
            }
            @Override public void drawBackdrop(Canvas canvas) {
                getLocationOnScreen(target); source.getLocationOnScreen(origin);
                canvas.drawColor(InspectorTheme.canvas(getContext()));
                canvas.translate(-(target[0] - origin[0]) - source.getScrollX(), -(target[1] - origin[1]) - source.getScrollY());
                source.draw(canvas);
            }
            @Override public void drawLabel(Canvas canvas, int index) { ((InspectorTabButton) getChildAt(index)).drawContent(canvas); }
        });
        motion = material.motion;
    }
    @SuppressWarnings("deprecation")
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setCollectionInfo(AccessibilityNodeInfo.CollectionInfo.obtain(1, getChildCount(), false,
            AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_SINGLE));
    }
    void refreshAppearance() { material.refreshAppearance(); }
    void syncSelection(int index) { material.syncSelection(index); }
    void activate(int index) {
        if (index < 0 || index >= getChildCount()) return;
        if (index != motion.selected()) selection.accept(index);
        material.syncSelection(index);
    }
    void setMaterialVisible(boolean visible) { material.setMaterialVisible(visible); }
    void updateRegistration() { material.updateRegistration(); }
    boolean materialReady() { return material.materialReady(); }
    float lensX() { return material.lensX(); }
    float lensScaleX() { return material.lensScaleX(); }
    float tabWidth() { return material.tabWidth(); }
    @Override public void draw(Canvas canvas) { if (!material.draw(canvas)) super.draw(canvas); }
    @Override public boolean onInterceptTouchEvent(MotionEvent event) { return material.intercept(event); }
    @android.annotation.SuppressLint("ClickableViewAccessibility") // Completion invokes the existing native child's performClick.
    @Override public boolean onTouchEvent(MotionEvent event) { return material.touch(event) || super.onTouchEvent(event); }
    @Override public void onDescendantInvalidated(View child, View target) {
        if (android.os.Build.VERSION.SDK_INT >= 26) super.onDescendantInvalidated(child, target);
        if (material != null) material.inkChanged();
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b); if (changed) material.geometryChangedByLayout(); material.updateRegistration();
    }
    @Override public void onRtlPropertiesChanged(int direction) {
        super.onRtlPropertiesChanged(direction); if (material != null) material.directionChanged();
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); material.updateRegistration(); }
    @Override protected void onDetachedFromWindow() { material.detach(); super.onDetachedFromWindow(); }
    @Override protected void onVisibilityChanged(View view, int visibility) {
        super.onVisibilityChanged(view, visibility); if (material != null) material.updateRegistration();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility); if (material != null) material.updateRegistration();
    }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); material.focusChanged(focused); }
}
