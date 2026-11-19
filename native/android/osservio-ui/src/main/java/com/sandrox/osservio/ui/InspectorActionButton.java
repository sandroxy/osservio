package com.sandrox.osservio.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;
import android.widget.Button;

/** Native control semantics with a separately owned optical background. */
@android.annotation.SuppressLint("ViewConstructor")
final class InspectorActionButton extends Button {
    private InspectorActionMaterial material;
    InspectorActionButton(Context context) { super(context); }
    void configureMaterial(boolean prominent) {
        if (material == null) material = new InspectorActionMaterial(this);
        material.configure(prominent);
    }
    @Override public void draw(Canvas canvas) {
        int save = canvas.save(); float scale = material == null ? 1 : material.scale();
        canvas.scale(scale, scale, getWidth() / 2f, getHeight() / 2f);
        super.draw(canvas); canvas.restoreToCount(save);
    }
    @Override protected void onDraw(Canvas canvas) { if (material != null) material.draw(canvas); super.onDraw(canvas); }
    @Override protected void drawableStateChanged() { super.drawableStateChanged(); if (material != null) material.stateChanged(); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if (material != null) material.updateRegistration(); }
    @Override public void onStartTemporaryDetach() { if (material != null) material.detach(); super.onStartTemporaryDetach(); }
    @Override public void onFinishTemporaryDetach() { super.onFinishTemporaryDetach(); if (material != null) material.updateRegistration(); }
    @Override protected void onDetachedFromWindow() { if (material != null) material.detach(); super.onDetachedFromWindow(); }
    @Override protected void onVisibilityChanged(View view, int visibility) {
        super.onVisibilityChanged(view, visibility); if (material != null) material.updateRegistration();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility); if (material != null) material.updateRegistration();
    }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (material != null) material.updateRegistration(); }
}
