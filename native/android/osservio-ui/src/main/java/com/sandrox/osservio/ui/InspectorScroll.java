package com.sandrox.osservio.ui;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.widget.ListView;
import android.widget.ScrollView;

/** Content may draw behind tabs, but focused and requested controls must remain above them. */
final class InspectorScroll {
    private InspectorScroll() { }
    private static boolean inset(View view) {
        Object value = view.getTag(R.id.osservio_scroll_padding);
        return value instanceof int[] && view.getPaddingBottom() > ((int[]) value)[3];
    }
    private static int adjustment(Rect rect, int top, int bottom) {
        // Oversized targets keep their leading edge visible instead of jumping past it.
        return Math.max(0, Math.min(rect.bottom - bottom, rect.top - top));
    }
    @android.annotation.SuppressLint("ViewConstructor")
    static class Page extends ScrollView {
        Page(Context context) { super(context); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
            int delta = super.computeScrollDeltaToGetChildRectOnScreen(rect);
            if (!inset(this)) return delta;
            int top = getScrollY() + delta;
            return delta + adjustment(rect, top + getPaddingTop(), top + getHeight() - getPaddingBottom());
        }
    }
    @android.annotation.SuppressLint("ViewConstructor")
    static class List extends ListView {
        List(Context context) { super(context); }
        @Override public boolean requestChildRectangleOnScreen(View child, Rect rect, boolean immediate) {
            if (!inset(this)) return super.requestChildRectangleOnScreen(child, rect, immediate);
            Rect requested = new Rect(rect);
            boolean moved = super.requestChildRectangleOnScreen(child, rect, immediate);
            requested.offset(child.getLeft() - child.getScrollX(), child.getTop() - child.getScrollY());
            int extra = adjustment(requested, getPaddingTop(), getHeight() - getPaddingBottom());
            if (extra > 0) scrollListBy(extra);
            return moved || extra > 0;
        }
    }
}
