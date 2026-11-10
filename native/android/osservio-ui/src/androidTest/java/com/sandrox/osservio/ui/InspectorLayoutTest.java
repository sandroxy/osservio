package com.sandrox.osservio.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.sandrox.osservio.core.*;
import com.sandrox.osservio.store.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Attached application gestures are verified separately by the maintained consumer. */
@RunWith(AndroidJUnit4.class)
public final class InspectorLayoutTest {
    @Test public void logsRemainReachableInCompactAndLargeTextLayouts() { check("log"); }
    @Test public void httpRemainsReachableInCompactAndLargeTextLayouts() { check("http"); }
    @Test public void socketsRemainReachableInCompactAndLargeTextLayouts() { check("websocket"); }
    @Test public void breadcrumbsRemainReachableInCompactAndLargeTextLayouts() { check("breadcrumb"); }

    private void check(String kind) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
            for (float scale : new float[] {1, 2}) for (int[] size : new int[][] {{320, 240}, {568, 180}}) {
                Configuration configuration = new Configuration(base.getResources().getConfiguration());
                configuration.fontScale = scale;
                Context context = new ContextThemeWrapper(base.createConfigurationContext(configuration), android.R.style.Theme_Material_Light_NoActionBar);
                CaptureController capture = new CaptureController(); capture.startDetailed();
                View viewer = viewer(context, kind, capture);
                int width = dp(context, size[0]), height = dp(context, size[1]);
                layout(viewer, width, height);
                EditText search = first(viewer, EditText.class);
                ListView list = first(viewer, ListView.class);
                assertNotNull(search); assertNotNull(list);
                if (kind.equals("breadcrumb")) { search.setText("no-match"); search.setText(""); }
                // Pure layout uses an injected retained snapshot; attached search is covered by consumer tests.
                layout(viewer, width, height);
                String label = kind + " scale=" + scale + " width=" + size[0] + " height=" + size[1];
                assertTrue(label, list.getHeight() >= dp(context, 48));
                assertEquals(label, kind.equals("websocket") ? 2 : 1, list.getCount() - list.getHeaderViewsCount() - list.getFooterViewsCount());
                List<Button> buttons = new ArrayList<>(); collect(viewer, Button.class, buttons);
                buttons.removeAll(hidden(buttons));
                assertEquals(label, 1, buttons.size());
                for (Button button : buttons) {
                    assertTrue(label, button.getHeight() >= dp(context, 48));
                    assertNotNull(label, button.getLayout());
                    for (int line = 0; line < button.getLineCount(); line++) assertEquals(label, 0, button.getLayout().getEllipsisCount(line));
                    assertTrue(label, button.getHeight() >= button.getLayout().getHeight() + button.getCompoundPaddingTop() + button.getCompoundPaddingBottom());
                }
                List<android.widget.ImageButton> icons = new ArrayList<>(); collect(viewer, android.widget.ImageButton.class, icons);
                icons.removeAll(hidden(icons));
                assertEquals(label, kind.equals("breadcrumb") ? 2 : 1, icons.size());
                for (android.widget.ImageButton icon : icons) {
                    assertTrue(label, icon.getWidth() >= dp(context, 48));
                    assertTrue(label, icon.getHeight() >= dp(context, 48));
                    assertNotNull(label, icon.getContentDescription());
                    assertTrue(label, icon.getContentDescription().length() > 0);
                }
                list.setSelectionFromTop(list.getHeaderViewsCount(), 0); layout(viewer, width, height);
                View row = list.getChildAt(list.getHeaderViewsCount() - list.getFirstVisiblePosition());
                assertNotNull(label, row); assertTrue(label, row.getBottom() > 0 && row.getTop() < list.getHeight());
                assertFalse(label, list.getAdapter().isEnabled(0));
                // A header click has no data row, even if dispatched directly by a host.
                list.performItemClick(null, 0, 0);
                assertEquals(CaptureController.Mode.DETAILED, capture.status().mode);
                assertTrue(capture.stopAll());
            }
        });
    }

    private static View viewer(Context context, String kind, CaptureController capture) {
        switch (kind) {
            case "log": {
                MemoryLogStore store = new MemoryLogStore(); LogRecorder recorder = new LogRecorder(store, capture);
                assertEquals(LogRecorder.Result.RECORDED, recorder.record(LogLevel.INFO, "layout", LogMessage.publicText("Synthetic layout evidence")));
                LogInspectorView.State state = new LogInspectorView.State(); state.snapshot = store.snapshot();
                return new LogInspectorView(context, recorder, store, state);
            }
            case "http": {
                MemoryHTTPStore store = new MemoryHTTPStore(); HTTPRecorder recorder = new HTTPRecorder(store, capture, new HTTPPrivacyPolicy());
                HTTPRecorder.Token token = recorder.begin("GET", "https://example.com", Collections.emptyList()).token;
                assertNotNull(token); assertEquals(HTTPRecorder.Result.RECORDED, recorder.receiveResponse(token, 200, Collections.emptyList(), false));
                assertEquals(HTTPRecorder.Result.RECORDED, recorder.finish(token, HTTPEvent.State.COMPLETED, null, true, true));
                NetworkInspectorView.State state = new NetworkInspectorView.State(); state.http = store.snapshot();
                return new NetworkInspectorView(context, recorder, store, null, null, state);
            }
            case "websocket": {
                MemoryWebSocketStore store = new MemoryWebSocketStore(); WebSocketRecorder recorder = new WebSocketRecorder(store, capture, new WebSocketPrivacyPolicy());
                WebSocketRecorder.Token token = recorder.begin("wss://example.com", Collections.emptyList(), WebSocketEvent.Source.HOST).token;
                assertNotNull(token); assertEquals(WebSocketRecorder.Result.RECORDED, recorder.lifecycle(token, WebSocketEvent.Kind.CLOSED, Collections.emptyList(), null, 1000, null));
                NetworkInspectorView.State state = new NetworkInspectorView.State(); state.sockets = store.snapshot();
                return new NetworkInspectorView(context, null, null, recorder, store, state);
            }
            default: {
                MemoryBreadcrumbStore store = new MemoryBreadcrumbStore(); BreadcrumbRecorder recorder = new BreadcrumbRecorder(store, capture);
                assertEquals(BreadcrumbRecorder.Result.RECORDED, recorder.record(BreadcrumbEvent.Kind.HOST_MARKER, "layout.ready"));
                return new BreadcrumbInspectorView(context, recorder, store);
            }
        }
    }
    private static <T extends View> List<T> hidden(List<T> views) {
        List<T> result = new ArrayList<>(); for (T view : views) if (view.getVisibility() != View.VISIBLE) result.add(view); return result;
    }
    private static void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }
    private static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private static <T extends View> T first(View view, Class<T> type) {
        List<T> values = new ArrayList<>(); collect(view, type, values); return values.isEmpty() ? null : values.get(0);
    }
    private static <T extends View> void collect(View view, Class<T> type, List<T> values) {
        if (type.isInstance(view)) values.add(type.cast(view));
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) collect(((ViewGroup) view).getChildAt(index), type, values);
    }
}
