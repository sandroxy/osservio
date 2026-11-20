package com.sandrox.osservio.ui;

import android.content.Context;
import android.text.Layout;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A short filter value trails its field; a longer value uses the full line below. */
final class InspectorFilterRow extends LinearLayout {
    final TextView title, value;
    private final Labels labels;
    private final ImageView arrow;
    private boolean stacked;

    InspectorFilterRow(Context context) { this(context, false); }

    InspectorFilterRow(Context context, boolean heading) {
        super(context);
        setGravity(Gravity.CENTER_VERTICAL);
        setBaselineAligned(false);
        int inset = dp(16);
        setPadding(inset, dp(10), inset, dp(10));
        setMinimumHeight(dp(48));
        title = InspectorTheme.text(context, 17, heading);
        value = InspectorTheme.text(context, heading ? 13 : 14, false);
        labels = new Labels(context);
        labels.addView(title);
        labels.addView(value);
        addView(labels, new LayoutParams(0, -2, 1));
        arrow = new ImageView(context);
        arrow.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams arrowSize = new LayoutParams(dp(16), dp(20));
        arrowSize.setMarginStart(dp(10));
        addView(arrow, arrowSize);
        arrow.setVisibility(heading ? GONE : VISIBLE);
        setFocusable(!heading);
        if (heading && android.os.Build.VERSION.SDK_INT >= 28) setAccessibilityHeading(true);
        InspectorAppearance.style(this, () -> {
            value.setTextColor(InspectorTheme.secondary(context));
            arrow.setImageDrawable(InspectorWidgets.symbol(context, R.drawable.osservio_forward, 16, InspectorTheme.secondary(context)));
        });
    }

    void bind(String name, String detail, boolean stacked) {
        this.stacked = stacked;
        title.setText(name);
        value.setText(detail);
        value.setVisibility(detail.isEmpty() ? GONE : VISIBLE);
        labels.requestLayout();
    }

    void hideDisclosure() { arrow.setVisibility(GONE); }

    void disclosureRotation(float degrees) {
        arrow.setRotation(getLayoutDirection() == LAYOUT_DIRECTION_RTL ? -degrees : degrees);
    }

    private int dp(int size) { return InspectorTheme.dp(getContext(), size); }

    private final class Labels extends LinearLayout {
        private Boolean inline;
        Labels(Context context) { super(context); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            boolean fits = value.getVisibility() == GONE || (!stacked
                && getResources().getConfiguration().fontScale < 1.5f
                && android.text.TextUtils.indexOf(value.getText(), '\n') < 0
                && Layout.getDesiredWidth(title.getText(), title.getPaint()) + dp(12)
                    + Layout.getDesiredWidth(value.getText(), value.getPaint()) <= width);
            if (inline == null || inline != fits) {
                inline = fits;
                setOrientation(fits ? HORIZONTAL : VERTICAL);
                setGravity(fits ? Gravity.CENTER_VERTICAL : Gravity.START);
                LayoutParams name = (LayoutParams) title.getLayoutParams();
                name.width = fits ? 0 : LayoutParams.MATCH_PARENT;
                name.weight = fits ? 1 : 0;
                title.setLayoutParams(name);
                LayoutParams detail = (LayoutParams) value.getLayoutParams();
                detail.width = fits ? LayoutParams.WRAP_CONTENT : LayoutParams.MATCH_PARENT;
                detail.setMarginStart(fits ? dp(12) : 0);
                detail.topMargin = fits ? 0 : dp(3);
                value.setLayoutParams(detail);
                value.setGravity(fits ? Gravity.END : Gravity.START);
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }
}
