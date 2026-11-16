package com.sandrox.osservio.ui;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/** A hardware window containing only test-owned views, with no capture or archive. */
public final class InspectorMaterialTestActivity extends Activity {
    InspectorGlassSession session;
    InspectorGlassBar bar;
    TextView content;
    FrameLayout container;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        container = new FrameLayout(this);
        content = new TextView(this); content.setText("Backdrop source\n\nNavigation material");
        content.setTextSize(26); content.setBackgroundColor(Color.CYAN);
        InspectorNavigation source = new InspectorNavigation(this, content, container, new View(this));
        container.addView(source, new FrameLayout.LayoutParams(-1, -1));
        session = new InspectorGlassSession(this);
        bar = new InspectorGlassBar(this, source, session); bar.setBackgroundColor(Color.WHITE);
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(-1, 150, android.view.Gravity.BOTTOM);
        container.addView(bar, position); setContentView(container);
    }
    @Override public void onDestroy() { session.dispose(); super.onDestroy(); }
}
