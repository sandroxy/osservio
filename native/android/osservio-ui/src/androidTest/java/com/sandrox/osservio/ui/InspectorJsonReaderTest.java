package com.sandrox.osservio.ui;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.sandrox.osservio.core.*;
import com.sandrox.osservio.store.MemoryHTTPStore;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Row geometry and immutable reads; actual taps and retained windows are consumer tests. */
@RunWith(AndroidJUnit4.class)
public final class InspectorJsonReaderTest {
    @Test public void wrappedNoticesLeaveReaderActionsFullyVisible() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = context();
            InspectorContentReader reader = new InspectorContentReader(context);
            reader.display(new InspectorReadContent("{}", "轻点对象或数组展开，长按可复制节点。省略内容可切换原文查看。", false), true);
            android.widget.ScrollView controls = (android.widget.ScrollView) reader.getChildAt(0);
            for (int width : new int[] {390, 320, 390}) {
                int pixels = InspectorTheme.dp(context, width), height = InspectorTheme.dp(context, 600);
                reader.measure(View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                reader.layout(0, 0, pixels, height);
                assertTrue("A wrapped notice must not hide actions in a full-height reader", controls.getChildAt(0).getHeight() <= controls.getHeight());
            }
        });
    }
    @Test public void fullRowExpansionSearchAndLargeArraysReuseNativeRows() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = context();
            InspectorJsonTreeView tree = new InspectorJsonTreeView(context);
            String source = "{\"profile\":{\"name\":\"你好🙂\"},\"items\":[1,2],\"id\":900719925474099312345}";
            tree.display(parse(source)); layout(tree);
            assertEquals(5, tree.getAdapter().getCount());
            View row = tree.getAdapter().getView(1, null, tree);
            assertFalse(((TextView) row).getText().toString().contains("▸"));
            tree.performItemClick(row, 1, tree.getAdapter().getItemId(1)); layout(tree);
            assertEquals(7, tree.getAdapter().getCount());
            tree.focus(source.indexOf("name"), true); layout(tree);
            assertTrue(((TextView) tree.getAdapter().getView(2, null, tree)).getText().toString().contains("name"));
            StringBuilder large = new StringBuilder("[");
            for (int i = 0; i < 3_000; i++) { if (i > 0) large.append(','); large.append(i); }
            large.append(']'); tree.display(parse(large.toString())); layout(tree);
            assertEquals(3_002, tree.getAdapter().getCount()); assertTrue(tree.getChildCount() > 0); assertTrue(tree.getChildCount() < 40);
            tree.setSelection(2_500); layout(tree); assertTrue(tree.getChildCount() < 40);
        });
    }
    @Test public void detailDoesNotPollOrOfferRefreshAndPayloadStatesStaySeparate() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = context(); CaptureController capture = new CaptureController(); capture.startDetailed();
            MemoryHTTPStore store = new MemoryHTTPStore(); HTTPRecorder recorder = new HTTPRecorder(store, capture, new HTTPPrivacyPolicy());
            HTTPRecorder.Token token = recorder.begin("GET", "https://example.com", java.util.Collections.emptyList()).token; assertNotNull(token);
            HTTPEvent initial = store.snapshot().events.get(0);
            HTTPHistory forbidden = new HTTPHistory() {
                @Override public HTTPSnapshot snapshot() { throw new AssertionError("A detail must not poll history"); }
                @Override public BodyLookup body(String id) { throw new AssertionError("Body lookup must be explicit"); }
                @Override public void clear() { throw new AssertionError("Must not clear history"); }
            };
            HTTPDetailDialog detail = new HTTPDetailDialog(context, initial, forbidden);
            assertFalse(text(detail.dialog.view).contains(context.getString(R.string.osservio_http_refresh)));
            assertTrue(text(detail.dialog.view).contains(context.getString(R.string.osservio_http_pending)));
            assertTrue(capture.stopAll());
            assertTrue(text(detail.dialog.view).contains(context.getString(R.string.osservio_http_pending)));
            InspectorReadContent missing = InspectorReadContent.saved(context, null, "state", true);
            assertEquals("", missing.text); assertFalse(missing.allowsJson);
            assertTrue(missing.notice().contains(context.getString(R.string.osservio_incident_payload_missing)));
        });
    }
    @Test public void contentNoticesRelocalizeWithoutAnotherBodyLookup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
            boolean english = InspectorPreferences.english(base);
            try {
                InspectorPreferences.setEnglish(base, false);
                android.content.MutableContextWrapper context = new android.content.MutableContextWrapper(InspectorTheme.context(base));
                CaptureController capture = new CaptureController(false, true, LogClock.SYSTEM);
                MemoryHTTPStore store = new MemoryHTTPStore();
                HTTPRecorder recorder = new HTTPRecorder(store, capture,
                    new HTTPPrivacyPolicy(true, java.util.Collections.emptySet(), HTTPPrivacyPolicy.BodyPolicy.PUBLIC_UTF8, 1024));
                HTTPRecorder.Token token = recorder.begin("GET", "https://example.com", java.util.Collections.emptyList()).token;
                assertNotNull(token);
                assertEquals(HTTPRecorder.Result.RECORDED, recorder.receiveResponse(token, 200,
                    java.util.Collections.singletonList(new HTTPHeader("Content-Type", "application/json")), true));
                assertEquals(HTTPRecorder.Result.RECORDED, recorder.observeBody(token, HTTPRecorder.Direction.RESPONSE, 2, limit -> new byte[] {123, 125}));
                assertEquals(HTTPRecorder.Result.RECORDED, recorder.finish(token, HTTPEvent.State.COMPLETED, null, true, true));
                int[] reads = {0};
                HTTPHistory history = new HTTPHistory() {
                    @Override public HTTPSnapshot snapshot() { throw new AssertionError("No metadata refresh"); }
                    @Override public BodyLookup body(String id) { reads[0]++; return store.body(id); }
                    @Override public void clear() { throw new AssertionError("No clearing"); }
                };
                InspectorReadContent content = HTTPPresentation.body(context, store.snapshot().events.get(0).responseBody, history);
                String original = content.notice(); assertEquals("{}", content.text);
                InspectorPreferences.setEnglish(base, true); context.setBaseContext(InspectorTheme.context(base));
                assertNotEquals(original, content.notice()); assertEquals("{}", content.text); assertEquals(1, reads[0]);
                capture.stopAll();
            } finally { InspectorPreferences.setEnglish(base, english); }
        });
    }
    private static InspectorJsonDocument parse(String source) {
        try { return InspectorJsonDocument.parse(source, () -> false); }
        catch (InspectorJsonDocument.ParseFailure error) { throw new AssertionError(error); }
    }
    private static Context context() { return new ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext(), android.R.style.Theme_Material_Light_NoActionBar); }
    private static String text(View view) {
        StringBuilder result = new StringBuilder(view instanceof TextView ? ((TextView) view).getText() : "");
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) result.append('\n').append(text(((ViewGroup) view).getChildAt(i)));
        return result.toString();
    }
    private static void layout(View view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 390, 600);
    }
}
