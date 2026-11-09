package com.sandrox.osservio.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.sandrox.osservio.core.*;
import com.sandrox.osservio.store.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Geometry and view-state checks; real gestures and attached windows are consumer tests. */
@RunWith(AndroidJUnit4.class)
public final class InspectorNavigationTest {
    @Test public void fourSectionsPreserveSearchAndCaptureInShortLargeTextLayouts() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
            Configuration configuration = new Configuration(base.getResources().getConfiguration()); configuration.fontScale = 2;
            Context context = base.createConfigurationContext(configuration);
            CaptureController capture = new CaptureController(); capture.startDetailed();
            MemoryLogStore logs = new MemoryLogStore(); MemoryHTTPStore http = new MemoryHTTPStore(); MemoryWebSocketStore sockets = new MemoryWebSocketStore();
            InspectorView inspector = new InspectorView(context, new LogRecorder(logs, capture), logs,
                new HTTPRecorder(http, capture, new HTTPPrivacyPolicy()), http,
                new WebSocketRecorder(sockets, capture, new WebSocketPrivacyPolicy()), sockets);
            layout(inspector, 568, 180);
            EditText search = first(inspector, EditText.class); assertNotNull(search); search.setText("retained query");
            for (int label : new int[] {R.string.osservio_network, R.string.osservio_incident_title, R.string.osservio_ui_app, R.string.osservio_logs}) {
                View tab = description(inspector, label); assertNotNull(tab); assertTrue(tab.performClick()); layout(inspector, 568, 180);
                assertTrue(tab.isSelected()); assertTrue(tab.getHeight() >= InspectorTheme.dp(context, 48));
            }
            assertEquals("retained query", first(inspector, EditText.class).getText().toString());
            int readingHeight = first(inspector, ListView.class).getHeight();
            assertTrue("Short-window reading height: " + readingHeight, readingHeight >= InspectorTheme.dp(context, 48));
            assertEquals(CaptureController.Mode.DETAILED, capture.status().mode);
            assertTrue(capture.stopAll());
        });
    }
    @Test public void contentRemainsReachableWithExpandedSearchAndLargeText() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
            Configuration configuration = new Configuration(base.getResources().getConfiguration()); configuration.fontScale = 2;
            Context context = InspectorTheme.context(base.createConfigurationContext(configuration));
            InspectorContentReader reader = new InspectorContentReader(context); reader.display("Line one\nLine two\nLine three", true);
            layout(reader, 568, 180);
            List<Button> buttons = new ArrayList<>(); collect(reader, Button.class, buttons);
            for (Button button : buttons) if (context.getString(R.string.osservio_ui_search_content).contentEquals(button.getText())) button.performClick();
            layout(reader, 568, 180);
            List<ScrollView> scrolling = new ArrayList<>(); collect(reader, ScrollView.class, scrolling);
            assertEquals(2, scrolling.size());
            for (ScrollView scroll : scrolling) assertTrue(scroll.getHeight() >= InspectorTheme.dp(context, 48));
        });
    }
    private static void layout(View view, int width, int height) {
        int w = InspectorTheme.dp(view.getContext(), width), h = InspectorTheme.dp(view.getContext(), height);
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, w, h);
    }
    private static View description(View view, int label) {
        if (view.getContentDescription() != null && view.getContext().getString(label).contentEquals(view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = description(((ViewGroup) view).getChildAt(i), label); if (found != null) return found;
        }
        return null;
    }
    private static <T extends View> T first(View view, Class<T> type) {
        List<T> values = new ArrayList<>(); collect(view, type, values); return values.isEmpty() ? null : values.get(0);
    }
    private static <T extends View> void collect(View view, Class<T> type, List<T> result) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (type.isInstance(view)) result.add(type.cast(view));
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collect(((ViewGroup) view).getChildAt(i), type, result);
    }
}
