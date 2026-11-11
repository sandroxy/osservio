package com.sandrox.osservio.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;

/** Drag ownership stays on the handle, so list scrolling and action taps cannot accidentally dismiss a draft. */
@SuppressLint("ViewConstructor")
final class InspectorSheetHandle extends View {
    private final View card;
    private final Runnable dismiss;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float startY;
    private long startTime;
    private boolean dragging;
    InspectorSheetHandle(Context context, View card, Runnable dismiss) {
        super(context); this.card = card; this.dismiss = dismiss;
        setMinimumHeight(InspectorTheme.dp(context, 40)); setFocusable(true); setClickable(true);
        setContentDescription(context.getString(R.string.osservio_ui_dismiss_panel));
        setOnClickListener(ignored -> dismiss.run());
        paint.setColor(InspectorTheme.secondary(context)); paint.setAlpha(110);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float half = InspectorTheme.dp(getContext(), 18), height = InspectorTheme.dp(getContext(), 4);
        canvas.drawRoundRect(getWidth() / 2f - half, getHeight() / 2f - height / 2,
            getWidth() / 2f + half, getHeight() / 2f + height / 2, height, height, paint);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                card.animate().cancel(); startY = event.getRawY(); startTime = event.getEventTime(); dragging = false; return true;
            case MotionEvent.ACTION_MOVE:
                float distance = Math.max(0, event.getRawY() - startY);
                dragging |= distance > ViewConfiguration.get(getContext()).getScaledTouchSlop();
                if (dragging) card.setTranslationY(distance);
                return true;
            case MotionEvent.ACTION_UP:
                float travel = Math.max(0, event.getRawY() - startY);
                float speed = travel / Math.max(1, event.getEventTime() - startTime);
                if (dragging && (travel > Math.max(InspectorTheme.dp(getContext(), 64), card.getHeight() * 0.18f)
                        || (speed > 1.2f && travel > ViewConfiguration.get(getContext()).getScaledTouchSlop()))) { restore(); dismiss.run(); return true; }
                else if (!dragging) performClick();
                restore(); return true;
            case MotionEvent.ACTION_CANCEL: restore(); return true;
            default: return true;
        }
    }
    private void restore() {
        dragging = false;
        card.animate().translationY(0).setDuration(InspectorFloatingEntry.motionDuration(getContext())).start();
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info); info.setClassName(android.widget.Button.class.getName());
        if (isEnabled()) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS);
    }
    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_DISMISS && isEnabled()) { dismiss.run(); return true; }
        return super.performAccessibilityAction(action, arguments);
    }
}
