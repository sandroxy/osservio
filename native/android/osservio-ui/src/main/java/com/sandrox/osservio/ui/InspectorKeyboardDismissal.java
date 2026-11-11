package com.sandrox.osservio.ui;

import android.content.Context;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;

/** Dismisses SDK input focus without consuming the target control's first touch. */
final class InspectorKeyboardDismissal {
    private InspectorKeyboardDismissal() { }
    static void outside(View root, MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN || !(root.findFocus() instanceof EditText)) return;
        if (!hitsInput(root, (int) event.getRawX(), (int) event.getRawY())) InspectorWidgets.hideKeyboard(root);
    }
    private static boolean hitsInput(View view, int x, int y) {
        Rect bounds = new Rect();
        if (!view.isShown() || !view.getGlobalVisibleRect(bounds) || !bounds.contains(x, y)) return false;
        if (view instanceof EditText) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) if (hitsInput(group.getChildAt(i), x, y)) return true;
        }
        return false;
    }
    @android.annotation.SuppressLint("ViewConstructor")
    static class Frame extends FrameLayout {
        Frame(Context context) { super(context); }
        @Override public boolean dispatchTouchEvent(MotionEvent event) { outside(this, event); return super.dispatchTouchEvent(event); }
    }
}
