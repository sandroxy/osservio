package com.sandrox.osservio.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;
import android.widget.ImageButton;

/** Native control semantics with a separately owned optical background. */
@android.annotation.SuppressLint("ViewConstructor")
final class InspectorActionIcon extends ImageButton {
    final InspectorActionMaterial material;
    private boolean grouped;
    InspectorActionIcon(Context context) { super(context); material = new InspectorActionMaterial(this); material.configure(false); }
    boolean isGrouped() { return grouped; }
    void setGrouped(boolean value) {
        if (grouped == value) return;
        grouped = value; material.setEnabled(!value); InspectorWidgets.iconBackground(this);
    }
    @Override public void draw(Canvas canvas) {
        int save = canvas.save(); float scale = material.scale();
        canvas.scale(scale, scale, getWidth() / 2f, getHeight() / 2f);
        super.draw(canvas); canvas.restoreToCount(save);
    }
    @Override protected void onDraw(Canvas canvas) { material.draw(canvas); super.onDraw(canvas); }
    @Override protected void drawableStateChanged() { super.drawableStateChanged(); if (material != null) material.stateChanged(); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); material.updateRegistration(); }
    @Override public void onStartTemporaryDetach() { material.detach(); super.onStartTemporaryDetach(); }
    @Override public void onFinishTemporaryDetach() { super.onFinishTemporaryDetach(); material.updateRegistration(); }
    @Override protected void onDetachedFromWindow() { material.detach(); super.onDetachedFromWindow(); }
    @Override protected void onVisibilityChanged(View view, int visibility) {
        super.onVisibilityChanged(view, visibility); if (material != null) material.updateRegistration();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility); if (material != null) material.updateRegistration();
    }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); material.updateRegistration(); }
}
