package com.sandrox.osservio.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;
import android.widget.LinearLayout;

/** A shared toolbar backing. Children retain independent hit regions, focus and actions. */
@android.annotation.SuppressLint("ViewConstructor")
final class InspectorActionGroup extends LinearLayout {
    private final InspectorActionMaterial material;
    private boolean grouped;

    InspectorActionGroup(Context context) {
        super(context); setWillNotDraw(false); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        material = new InspectorActionMaterial(this);
        InspectorAppearance.style(this, this::updateBackground);
    }
    private void updateBackground() {
        if (grouped) InspectorTheme.surface(this, false, 24);
        else setBackground(null);
    }
    private void updateGrouping() {
        int icons = 0;
        boolean onlyIcons = true;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            if (child instanceof InspectorActionIcon) icons++; else onlyIcons = false;
        }
        boolean value = onlyIcons && icons > 1;
        if (grouped != value) {
            grouped = value; updateBackground(); material.setEnabled(value);
        }
        for (int i = 0; i < getChildCount(); i++) {
            if (getChildAt(i) instanceof InspectorActionIcon) ((InspectorActionIcon) getChildAt(i)).setGrouped(value);
        }
    }
    @Override public void onViewAdded(View child) { super.onViewAdded(child); updateGrouping(); }
    @Override public void onViewRemoved(View child) {
        super.onViewRemoved(child);
        if (child instanceof InspectorActionIcon) ((InspectorActionIcon) child).setGrouped(false);
        updateGrouping();
    }
    @Override protected void onMeasure(int width, int height) { updateGrouping(); super.onMeasure(width, height); }
    @Override protected void onDraw(Canvas canvas) { material.draw(canvas); super.onDraw(canvas); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); material.updateRegistration(); }
    @Override protected void onDetachedFromWindow() { material.detach(); super.onDetachedFromWindow(); }
    @Override public void onStartTemporaryDetach() { material.detach(); super.onStartTemporaryDetach(); }
    @Override public void onFinishTemporaryDetach() { super.onFinishTemporaryDetach(); material.updateRegistration(); }
    @Override protected void onVisibilityChanged(View view, int visibility) {
        super.onVisibilityChanged(view, visibility); if (material != null) material.updateRegistration();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility); if (material != null) material.updateRegistration();
    }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); material.updateRegistration(); }
}
