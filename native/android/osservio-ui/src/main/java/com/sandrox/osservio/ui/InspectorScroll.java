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
        @Override public boolean canScrollVertically(int direction) {
            if (getChildCount() == 0) return false;
            // ScrollView's touch interceptor asks this before taking a drag from
            // a clickable child. Use the same padded viewport as its scrollTo
            // bounds; scrollbar metrics use the full height and miss inset-only overflow.
            int viewport = getHeight() - getPaddingTop() - getPaddingBottom();
            int range = Math.max(0, getChildAt(0).getHeight() - viewport);
            return direction < 0 ? getScrollY() > 0 : direction > 0 && getScrollY() < range;
        }
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
        @Override protected void onVisibilityChanged(View changedView, int visibility) {
            super.onVisibilityChanged(changedView, visibility); updateSelectorVisibility(getWindowVisibility());
        }
        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility); updateSelectorVisibility(visibility);
        }
        private void updateSelectorVisibility(int windowVisibility) {
            // AbsListView owns its selector separately from View's background.
            // It otherwise remains visible while an ancestor hides this page,
            // allowing a hardware ripple to resume when navigation returns.
            android.graphics.drawable.Drawable selector = getSelector();
            if (selector != null) selector.setVisible(isShown() && windowVisibility == VISIBLE, false);
        }
        interface Records { int count(); String key(int index); }
        /** Keep visible record identities and their pixel offsets across a bounded data refresh. */
        void preservingReadingPosition(Records records, Runnable update) {
            preservingReadingPosition(true, records, update);
        }
        void preservingReadingPosition(boolean preserve, Records records, Runnable update) {
            if (!preserve) { update.run(); return; }
            java.util.LinkedHashMap<String, Integer> anchors = new java.util.LinkedHashMap<>();
            for (int child = 0; child < getChildCount(); child++) {
                int index = getFirstVisiblePosition() + child - getHeaderViewsCount();
                if (index >= 0 && index < records.count()) anchors.put(records.key(index), getChildAt(child).getTop());
            }
            update.run();
            if (anchors.isEmpty()) return;
            java.util.HashMap<String, Integer> positions = new java.util.HashMap<>();
            for (int index = 0; index < records.count(); index++) {
                String key = records.key(index);
                if (anchors.containsKey(key)) positions.put(key, index);
                if (positions.size() == anchors.size()) break;
            }
            for (java.util.Map.Entry<String, Integer> anchor : anchors.entrySet()) {
                Integer index = positions.get(anchor.getKey());
                if (index != null) {
                    setSelectionFromTop(index + getHeaderViewsCount(), anchor.getValue() - getPaddingTop());
                    return;
                }
            }
        }
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
