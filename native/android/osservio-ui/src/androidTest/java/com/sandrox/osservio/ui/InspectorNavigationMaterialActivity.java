package com.sandrox.osservio.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import com.sandrox.osservio.core.CaptureController;
import com.sandrox.osservio.core.HTTPPrivacyPolicy;
import com.sandrox.osservio.core.HTTPRecorder;
import com.sandrox.osservio.core.LogRecorder;
import com.sandrox.osservio.store.MemoryHTTPStore;
import com.sandrox.osservio.store.MemoryLogStore;

/** Real inspector Dialog with isolated memory stores and test-owned preferences. */
public final class InspectorNavigationMaterialActivity extends Activity {
    InspectorView inspector;
    InspectorSession presentation;
    private final CaptureController capture = new CaptureController();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); setContentView(new FrameLayout(this));
        MemoryLogStore logs = new MemoryLogStore(); MemoryHTTPStore http = new MemoryHTTPStore();
        SharedPreferences preferences = getSharedPreferences("navigation-material-test", MODE_PRIVATE);
        preferences.edit().putString("appearance", "light").putString("language", "en").apply();
        presentation = new InspectorSession(getApplication(), base -> {
            Context scoped = new ContextWrapper(base) {
                @Override public SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
            };
            inspector = new InspectorView(scoped, new LogRecorder(logs, capture), logs,
                new HTTPRecorder(http, capture, new HTTPPrivacyPolicy()), http);
            return inspector;
        });
        presentation.attach(this); presentation.expand();
    }
    InspectorTabBar bar() { return find(inspector, InspectorTabBar.class); }
    static <T> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            T found = find(((ViewGroup) view).getChildAt(i), type); if (found != null) return found;
        }
        return null;
    }
    @Override public void onDestroy() { presentation.dispose(); capture.close(); super.onDestroy(); }
}
