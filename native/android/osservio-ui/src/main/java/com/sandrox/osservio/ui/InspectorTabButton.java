package com.sandrox.osservio.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;

/** One native focus/click/accessibility owner; its actual children also supply the lens ink. */
@android.annotation.SuppressLint("ViewConstructor")
final class InspectorTabButton extends LinearLayout {
    InspectorTabButton(Context context, int index) {
        super(context);
        setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @SuppressWarnings("deprecation") // Keep collection semantics on API 23 without loading the API 33 builder.
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
                info.setCollectionItemInfo(AccessibilityNodeInfo.CollectionItemInfo.obtain(0, 1, index, 1, false, host.isSelected()));
            }
        });
    }
    void drawContent(Canvas canvas) { super.dispatchDraw(canvas); }
}
