package com.sandrox.osservio.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.widget.Button;
import android.widget.LinearLayout;

/** Wrap action titles and give large-font actions the full available width. */
// Actions are supplied by the owning inspector; this is not an XML-inflatable view.
@SuppressLint("ViewConstructor")
final class InspectorControls extends LinearLayout {
    InspectorControls(Context context, Button... buttons) {
        super(context);
        setBaselineAligned(false); setGravity(android.view.Gravity.CENTER_VERTICAL);
        for (Button button : buttons) {
            button.setSingleLine(false);
            button.setEllipsize(null);
            addView(button);
        }
        updateOrientation();
    }
    @Override protected void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration); updateOrientation();
    }
    private void updateOrientation() {
        boolean vertical = getResources().getConfiguration().fontScale >= 1.5f;
        setOrientation(vertical ? VERTICAL : HORIZONTAL);
        for (int index = 0; index < getChildCount(); index++) {
            getChildAt(index).setLayoutParams(vertical ? new LayoutParams(-1, -2) : new LayoutParams(0, -2, 1));
        }
    }
}
