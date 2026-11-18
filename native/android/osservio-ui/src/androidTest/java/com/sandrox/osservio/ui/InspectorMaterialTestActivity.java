package com.sandrox.osservio.ui;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.widget.FrameLayout;
import android.widget.TextView;

/** A hardware window containing only test-owned views, with no capture or archive. */
public final class InspectorMaterialTestActivity extends Activity {
    InspectorGlassSession session;
    InspectorFloatingEntry entry;
    TextView content;
    FrameLayout container;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        container = new FrameLayout(this);
        content = new TextView(this); content.setText("Backdrop source\n\nNavigation material");
        content.setTextSize(26); content.setBackgroundColor(Color.CYAN);
        container.addView(content, new FrameLayout.LayoutParams(-1, -1));
        setContentView(container);
        session = new InspectorGlassSession(this);
        entry = new InspectorFloatingEntry(this, session, () -> { }, () -> { }, () -> { });
        entry.attach(getWindow(), true);
    }
    @Override public void onDestroy() { entry.detach(); session.dispose(); super.onDestroy(); }
}
