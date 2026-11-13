package com.sandrox.osservio.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.text.Layout;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A short decision, with explicit cancellation and no retained navigation state. */
@android.annotation.SuppressLint("ViewConstructor")
final class InspectorConfirmation extends InspectorScroll.Page implements InspectorScreen.Content {
    private InspectorScreen screen;

    InspectorConfirmation(Context context, int title, int message, int confirm, int cancel, DialogInterface.OnClickListener action) {
        super(context);
        setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        int inset = InspectorTheme.dp(context, 24), gap = InspectorTheme.dp(context, 8);
        content.setPadding(inset, inset, inset, inset);
        TextView heading = InspectorTheme.text(context, 17, true);
        InspectorAppearance.text(heading, title);
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        content.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        TextView description = InspectorTheme.text(context, 15, false);
        InspectorAppearance.text(description, message);
        InspectorAppearance.style(description, () -> description.setTextColor(InspectorTheme.secondary(context)));
        LinearLayout.LayoutParams body = new LinearLayout.LayoutParams(-1, -2); body.topMargin = gap; body.bottomMargin = inset;
        content.addView(description, body);
        Button negative = InspectorWidgets.button(context, cancel, () -> { if (screen != null) screen.cancel(); });
        Button positive = InspectorWidgets.button(context, confirm, () -> {
            if (screen == null || !screen.isShowing()) return;
            screen.dismiss(); action.onClick(screen, DialogInterface.BUTTON_POSITIVE);
        });
        style(negative, false); style(positive, true);
        LinearLayout actions = new LinearLayout(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int column = Math.max(0, (View.MeasureSpec.getSize(widthSpec) - gap) / 2);
                boolean stacked = desiredWidth(negative) > column || desiredWidth(positive) > column;
                int direction = stacked ? VERTICAL : HORIZONTAL;
                if (getOrientation() != direction) {
                    setOrientation(direction);
                    LayoutParams first = (LayoutParams) negative.getLayoutParams(), second = (LayoutParams) positive.getLayoutParams();
                    first.width = second.width = stacked ? -1 : 0;
                    first.weight = second.weight = stacked ? 0 : 1;
                    first.topMargin = stacked ? gap : 0;
                    second.topMargin = 0; second.setMarginStart(stacked ? 0 : gap);
                    negative.setLayoutParams(first);
                    positive.setLayoutParams(second);
                    bringChildToFront(stacked ? negative : positive);
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        // Start vertically so the first measure also installs weighted columns when they fit.
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.addView(positive, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(-1, -2); second.topMargin = gap;
        actions.addView(negative, second);
        content.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        addView(content, new android.widget.ScrollView.LayoutParams(-1, -2));
    }
    @Override public void bindScreen(InspectorScreen value) { screen = value; }
    private static float desiredWidth(Button button) {
        return Layout.getDesiredWidth(button.getText(), button.getPaint()) + button.getPaddingLeft() + button.getPaddingRight();
    }
    private static void style(Button button, boolean destructive) {
        Context context = button.getContext();
        InspectorAppearance.style(button, () -> {
            button.setTextColor(destructive ? InspectorTheme.color(context, R.color.osservio_error) : InspectorTheme.primary(context));
            button.setBackgroundTintList(null);
            button.setBackground(InspectorTheme.ripple(context, InspectorTheme.shape(context, InspectorTheme.canvas(context), 1000, false), null));
        });
    }
}
