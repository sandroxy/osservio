package com.sandrox.osservio.ui;

import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;

/** Native press feedback whose pending animations cannot outlive the visible control. */
final class InspectorPressFeedback extends LayerDrawable {
    private final boolean wholeRowHotspot;
    private boolean hadPress;

    InspectorPressFeedback(int color, Drawable content, Drawable mask, boolean wholeRowHotspot) {
        super(new Drawable[] {new RippleDrawable(ColorStateList.valueOf(color), content, mask)});
        this.wholeRowHotspot = wholeRowHotspot;
    }

    @Override protected boolean onStateChange(int[] state) {
        if (pressed(state)) hadPress = true;
        return super.onStateChange(state);
    }

    @Override public boolean setVisible(boolean visible, boolean restart) {
        if (visible && !isVisible()) discardPress();
        return super.setVisible(visible, restart);
    }

    @Override public void jumpToCurrentState() {
        if (!pressed(getState())) discardPress();
        super.jumpToCurrentState();
    }

    private void discardPress() {
        if (!hadPress) return;
        Drawable previous = getDrawable(0);
        // Patterned RippleDrawable queues its first animation until draw(). Neither
        // hiding nor jumpToCurrentState clears that pending start on affected systems.
        // A fresh native drawable keeps the recipe without replaying an old touch.
        Drawable replacement = previous.getConstantState().newDrawable().mutate();
        previous.jumpToCurrentState();
        setDrawable(0, replacement);
        replacement.setLayoutDirection(getLayoutDirection());
        replacement.setLevel(getLevel());
        replacement.setVisible(isVisible(), false);
        replacement.setState(getState());
        Rect hotspot = new Rect(); getHotspotBounds(hotspot);
        replacement.setHotspotBounds(hotspot.left, hotspot.top, hotspot.right, hotspot.bottom);
        hadPress = pressed(getState());
        invalidateSelf();
    }

    @Override public void setHotspotBounds(int left, int top, int right, int bottom) {
        // CheckedTextView otherwise confines the background to its trailing checkmark.
        Rect row = getBounds();
        if (wholeRowHotspot) super.setHotspotBounds(row.left, row.top, row.right, row.bottom);
        else super.setHotspotBounds(left, top, right, bottom);
    }

    @Override public ConstantState getConstantState() {
        // These per-view feedback instances also own lifecycle state; cloning a plain
        // LayerDrawable would drop that behavior.
        return null;
    }

    private static boolean pressed(int[] state) {
        for (int value : state) if (value == android.R.attr.state_pressed) return true;
        return false;
    }
}
