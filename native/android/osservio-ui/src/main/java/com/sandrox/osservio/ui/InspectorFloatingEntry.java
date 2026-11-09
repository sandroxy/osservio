package com.sandrox.osservio.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.os.Build;
import android.os.PowerManager;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageButton;

/** A single app-decor child; no full-window touch target, idle timer, bitmap, or software shadow. */
// Session actions are required; this private control is never inflated from XML.
@android.annotation.SuppressLint("ViewConstructor")
final class InspectorFloatingEntry extends ImageButton {
    private final Runnable open;
    private final Runnable hide;
    private final Rect available = new Rect();
    private final Rect visibleFrame = new Rect();
    private final int[] location = new int[2];
    private final int size;
    private final int slop;
    private final boolean dark;
    private final boolean english;
    private final ViewTreeObserver.OnGlobalLayoutListener layout = this::position;
    private boolean trailing;
    private float fraction;
    private float downX, downY, initialX, initialY;
    private boolean dragging;

    InspectorFloatingEntry(Context context, Runnable open, Runnable hide) {
        super(InspectorTheme.context(context)); this.open = open; this.hide = hide;
        dark = InspectorPreferences.dark(context); english = InspectorPreferences.english(context);
        size = InspectorTheme.dp(context, 48); slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setImageResource(R.drawable.osservio_entry); setImageTintList(ColorStateList.valueOf(InspectorTheme.accent(getContext())));
        int inset = InspectorTheme.dp(context, 12); setPadding(inset, inset, inset, inset);
        InspectorTheme.surface(this, true, 24); setElevation(InspectorTheme.dp(context, 6)); setClipToOutline(true);
        setContentDescription(getContext().getString(R.string.osservio_ui_open)); setOnClickListener(ignored -> this.open.run());
        trailing = InspectorPreferences.storage(context).getBoolean("entryTrailing", true);
        fraction = InspectorPreferences.storage(context).getFloat("entryFraction", 0.35f);
        if (!Float.isFinite(fraction)) fraction = 0.35f;
        fraction = Math.max(0, Math.min(1, fraction));
    }
    boolean matchesPreferences() {
        return dark == InspectorPreferences.dark(getContext()) && english == InspectorPreferences.english(getContext());
    }
    void attach(ViewGroup parent) {
        if (getParent() == parent) { position(); return; }
        detach(); parent.addView(this, new ViewGroup.LayoutParams(size, size));
        parent.getViewTreeObserver().addOnGlobalLayoutListener(layout); position();
    }
    void detach() {
        animate().cancel(); dragging = false;
        if (getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) getParent(); parent.getViewTreeObserver().removeOnGlobalLayoutListener(layout); parent.removeView(this);
        }
    }
    private void bounds() {
        if (!(getParent() instanceof View)) return;
        View parent = (View) getParent(); int inset = InspectorTheme.dp(getContext(), 8);
        available.set(inset, inset, parent.getWidth() - size - inset, parent.getHeight() - size - inset);
        WindowInsets insets = parent.getRootWindowInsets();
        if (Build.VERSION.SDK_INT >= 30 && insets != null) {
            android.graphics.Insets edges = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            available.left += edges.left; available.top += edges.top; available.right -= edges.right; available.bottom -= edges.bottom;
        } else {
            parent.getWindowVisibleDisplayFrame(visibleFrame); parent.getLocationOnScreen(location);
            available.left = Math.max(available.left, visibleFrame.left - location[0] + inset);
            available.top = Math.max(available.top, visibleFrame.top - location[1] + inset);
            available.right = Math.min(available.right, visibleFrame.right - location[0] - size - inset);
            available.bottom = Math.min(available.bottom, visibleFrame.bottom - location[1] - size - inset);
        }
        available.right = Math.max(available.left, available.right); available.bottom = Math.max(available.top, available.bottom);
    }
    private void position() {
        if (dragging || getParent() == null) return;
        bounds(); setX(trailing ? available.right : available.left); setY(available.top + available.height() * fraction);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                animate().cancel(); downX = event.getRawX(); downY = event.getRawY(); initialX = getX(); initialY = getY();
                dragging = false; setPressed(true); return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                if (dragging || Math.hypot(dx, dy) > slop) {
                    dragging = true; setPressed(false); bounds();
                    setX(Math.min(available.right, Math.max(available.left, initialX + dx)));
                    setY(Math.min(available.bottom, Math.max(available.top, initialY + dy)));
                }
                return true;
            case MotionEvent.ACTION_UP:
                setPressed(false);
                if (!dragging) performClick(); else settle();
                dragging = false; return true;
            case MotionEvent.ACTION_CANCEL:
                setPressed(false); if (dragging) settle(); dragging = false; return true;
            default: return super.onTouchEvent(event);
        }
    }
    @Override public boolean performClick() { return super.performClick(); }
    private void settle() {
        bounds(); trailing = getX() >= available.exactCenterX();
        fraction = available.height() == 0 ? 0 : Math.max(0, Math.min(1, (getY() - available.top) / available.height()));
        InspectorPreferences.storage(getContext()).edit().putBoolean("entryTrailing", trailing).putFloat("entryFraction", fraction).apply();
        animate().x(trailing ? available.right : available.left).y(available.top + available.height() * fraction).setDuration(motionDuration(getContext())).start();
    }
    static long motionDuration(Context context) {
        if (Build.VERSION.SDK_INT >= 26 && !android.animation.ValueAnimator.areAnimatorsEnabled()) return 0;
        PowerManager power = context.getSystemService(PowerManager.class);
        if (power != null && (power.isPowerSaveMode() || (Build.VERSION.SDK_INT >= 29 && power.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE))) return 0;
        return 180;
    }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(android.R.id.closeButton, getContext().getString(R.string.osservio_ui_hide_entry)));
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT);
    }
    @Override public boolean performAccessibilityAction(int action, android.os.Bundle arguments) {
        if (action == android.R.id.closeButton) { hide.run(); return true; }
        if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId()
            || action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId()) {
            bounds(); setX(action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId() ? available.right : available.left);
            settle(); return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }
}
