package com.sandrox.osservio.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Centers short titles while reserving real space for actions and wrapping long titles. */
final class InspectorToolbar extends ViewGroup {
    final LinearLayout leading, trailing;
    final TextView title;
    InspectorToolbar(Context context) {
        super(context);
        leading = actions(context); trailing = actions(context);
        title = InspectorTheme.text(context, 17, true); title.setGravity(Gravity.CENTER);
        title.setMaxLines(2); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (android.os.Build.VERSION.SDK_INT >= 29) title.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED);
        addView(leading); addView(title); addView(trailing);
        int inset = InspectorTheme.dp(context, 16); setPadding(inset, 0, inset, 0);
    }
    private static LinearLayout actions(Context context) {
        LinearLayout row = new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL); row.setBaselineAligned(false);
        row.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        android.graphics.drawable.GradientDrawable gap = new android.graphics.drawable.GradientDrawable();
        gap.setColor(android.graphics.Color.TRANSPARENT); gap.setSize(InspectorTheme.dp(context, 8), 1); row.setDividerDrawable(gap); return row;
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), available = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int actionSpec = MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST);
        int height = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        leading.measure(actionSpec, height); trailing.measure(actionSpec, height);
        int gap = InspectorTheme.dp(getContext(), 8);
        int titleWidth = Math.max(0, available - leading.getMeasuredWidth() - trailing.getMeasuredWidth() - gap * 2);
        title.measure(MeasureSpec.makeMeasureSpec(titleWidth, MeasureSpec.AT_MOST), height);
        int contentHeight = Math.max(InspectorTheme.dp(getContext(), 56), Math.max(title.getMeasuredHeight() + gap,
            Math.max(leading.getMeasuredHeight(), trailing.getMeasuredHeight())));
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(contentHeight + getPaddingTop() + getPaddingBottom(), heightSpec));
    }
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left, height = bottom - top;
        place(leading, getPaddingLeft(), height);
        place(trailing, width - getPaddingRight() - trailing.getMeasuredWidth(), height);
        int gap = InspectorTheme.dp(getContext(), 8);
        int min = leading.getRight() + gap, max = trailing.getLeft() - gap - title.getMeasuredWidth();
        place(title, Math.max(min, Math.min((width - title.getMeasuredWidth()) / 2, max)), height);
    }
    private void place(View child, int left, int height) {
        int top = getPaddingTop() + (height - getPaddingTop() - getPaddingBottom() - child.getMeasuredHeight()) / 2;
        child.layout(left, top, left + child.getMeasuredWidth(), top + child.getMeasuredHeight());
    }
}
