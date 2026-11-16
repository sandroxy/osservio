package com.sandrox.osservio.ui;

import android.os.Build;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.DoNotInline;
import androidx.annotation.RequiresApi;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Rebind retained SDK pages after system sizing changes without discarding their readers or drafts. */
final class InspectorLayoutConfiguration {
    private final DisplayMetrics before, after;
    private final float densityRatio;
    private final Set<View> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    InspectorLayoutConfiguration(DisplayMetrics before, DisplayMetrics after) {
        this.before = before; this.after = after; densityRatio = after.density / before.density;
    }
    int pixels(int value) { return Math.round(value * densityRatio); }
    void apply(View view) {
        if (!visited.add(view)) return;
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null) {
            if (params.width > 0) params.width = pixels(params.width);
            if (params.height > 0) params.height = pixels(params.height);
            if (params instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margin = (ViewGroup.MarginLayoutParams) params;
                margin.setMargins(pixels(margin.leftMargin), pixels(margin.topMargin), pixels(margin.rightMargin), pixels(margin.bottomMargin));
                if (margin.isMarginRelative()) { margin.setMarginStart(pixels(margin.getMarginStart())); margin.setMarginEnd(pixels(margin.getMarginEnd())); }
            }
            view.setLayoutParams(params);
        }
        view.setPaddingRelative(pixels(view.getPaddingStart()), pixels(view.getPaddingTop()), pixels(view.getPaddingEnd()), pixels(view.getPaddingBottom()));
        view.setMinimumWidth(pixels(view.getMinimumWidth())); view.setMinimumHeight(pixels(view.getMinimumHeight()));
        view.setElevation(view.getElevation() * densityRatio);
        Object padding = view.getTag(R.id.osservio_scroll_padding);
        if (padding instanceof int[]) {
            int[] values = (int[]) padding;
            for (int i = 0; i < values.length; i++) values[i] = pixels(values[i]);
        }
        Object margin = view.getTag(R.id.osservio_floating_margin);
        if (margin instanceof Integer) view.setTag(R.id.osservio_floating_margin, pixels((Integer) margin));
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (text.getMinHeight() >= 0) text.setMinHeight(pixels(text.getMinHeight()));
            if (text.getMinWidth() >= 0) text.setMinWidth(pixels(text.getMinWidth()));
            if (text.getMaxHeight() >= 0 && text.getMaxHeight() < Integer.MAX_VALUE) text.setMaxHeight(pixels(text.getMaxHeight()));
            if (text.getMaxWidth() >= 0 && text.getMaxWidth() < Integer.MAX_VALUE) text.setMaxWidth(pixels(text.getMaxWidth()));
            float sp = Build.VERSION.SDK_INT >= 34 ? FontScaling.sp(text.getTextSize(), before) : legacySp(text.getTextSize());
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, after));
            text.setCompoundDrawablePadding(pixels(text.getCompoundDrawablePadding()));
            text.setLineSpacing(text.getLineSpacingExtra() * densityRatio, text.getLineSpacingMultiplier());
            for (android.graphics.drawable.Drawable drawable : text.getCompoundDrawablesRelative()) {
                if (drawable == null) continue;
                android.graphics.Rect bounds = drawable.getBounds();
                drawable.setBounds(pixels(bounds.left), pixels(bounds.top), pixels(bounds.right), pixels(bounds.bottom));
            }
        }
        if (view instanceof LinearLayout) {
            LinearLayout row = (LinearLayout) view;
            android.graphics.drawable.Drawable divider = row.getDividerDrawable();
            if (divider instanceof android.graphics.drawable.GradientDrawable) {
                ((android.graphics.drawable.GradientDrawable) divider).setSize(pixels(divider.getIntrinsicWidth()), pixels(divider.getIntrinsicHeight()));
                row.setDividerDrawable(null); row.setDividerDrawable(divider);
            }
        }
        if (view instanceof InspectorToolbar) ((InspectorToolbar) view).pageActions.reconfigure(this);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) apply(group.getChildAt(i));
        }
        view.requestLayout(); view.invalidate();
    }
    @SuppressWarnings("deprecation") // Before API 34 the SP transform is linear.
    private float legacySp(float pixels) { return pixels / before.scaledDensity; }
    @RequiresApi(34)
    private static final class FontScaling {
        @DoNotInline static float sp(float pixels, DisplayMetrics metrics) {
            return TypedValue.deriveDimension(TypedValue.COMPLEX_UNIT_SP, pixels, metrics);
        }
    }
}
