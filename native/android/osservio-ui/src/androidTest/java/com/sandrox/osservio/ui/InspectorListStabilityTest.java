package com.sandrox.osservio.ui;

import android.app.Instrumentation;
import android.content.Context;
import android.content.res.Configuration;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import com.sandrox.osservio.core.*;
import com.sandrox.osservio.store.MemoryWebSocketStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public final class InspectorListStabilityTest {
    @Test public void previewSlotKeepsGeometryAndResetsForNaturalContent() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
            for (int density : new int[] {160, 420}) for (float scale : new float[] {1, 2}) for (int width : new int[] {240, 400}) {
                Configuration config = new Configuration(base.getResources().getConfiguration()); config.fontScale = scale; config.densityDpi = density;
                Context context = InspectorTheme.context(base.createConfigurationContext(config));
                InspectorWidgets.EventRow row = new InspectorWidgets.EventRow(context);
                int reserved = height(row, "文本消息", true, width);
                for (String text : new String[] {"", "missing", String.join(" ", Collections.nCopies(30, "long 内容")), String.join(" ", Collections.nCopies(30, "🙂👨‍👩‍👧‍👦汉Å"))}) {
                    assertEquals("density=" + density + ", scale=" + scale + ", width=" + width + ", text=" + text,
                        reserved, height(row, text, true, width));
                }
                assertTrue(height(row, "short", false, width) < reserved);
                assertTrue("Three lines of fallback glyphs must fit", reserved >= height(row, "汉🙂\n汉🙂\n汉🙂", false, width));
                assertEquals("Reusing a natural row must restore the preview slot", reserved, height(row, "missing", true, width));
            }
        });
    }
    private static int height(InspectorWidgets.EventRow row, String text, boolean reserve, int width) {
        row.reservePreview(reserve); row.bind("Socket", text, "stream", 1, 0xff555555);
        row.measure(View.MeasureSpec.makeMeasureSpec(InspectorTheme.dp(row.getContext(), width), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return row.getMeasuredHeight();
    }
    @Test public void insertionsAndRemovedAnchorKeepTheNextVisibleRecordInPlace() throws Exception {
        Instrumentation runner = InstrumentationRegistry.getInstrumentation();
        InspectorMaterialTestActivity host = InspectorWindowTestSupport.start(InspectorMaterialTestActivity.class);
        InspectorScroll.List[] list = new InspectorScroll.List[1]; IdentityRows[] rows = new IdentityRows[1];
        String[] id = new String[1]; int[] top = new int[1];
        try {
            runner.runOnMainSync(() -> {
                host.entry.detach(); list[0] = new InspectorScroll.List(host); rows[0] = new IdentityRows();
                TextView header = new TextView(host); header.setHeight(150); list[0].addHeaderView(header, null, false);
                list[0].setAdapter(rows[0]); host.setContentView(list[0]); list[0].setSelectionFromTop(41, -11);
            });
            runner.waitForIdleSync();
            runner.runOnMainSync(() -> {
                int first = list[0].getFirstVisiblePosition() - list[0].getHeaderViewsCount();
                id[0] = rows[0].ids.get(first + 1); top[0] = list[0].getChildAt(1).getTop();
                list[0].preservingReadingPosition(rows[0], () -> {
                    rows[0].ids.remove(first); rows[0].ids.add(0, "new-a"); rows[0].ids.add(0, "new-b"); rows[0].notifyDataSetChanged();
                });
            });
            runner.waitForIdleSync();
            runner.runOnMainSync(() -> {
                int position = rows[0].ids.indexOf(id[0]) + list[0].getHeaderViewsCount();
                assertEquals(top[0], list[0].getChildAt(position - list[0].getFirstVisiblePosition()).getTop());
            });
        } finally { runner.runOnMainSync(host::finish); }
    }
    @Test public void delayedPreviewsKeepEveryVisibleRowAndBoundReads() throws Exception {
        Instrumentation runner = InstrumentationRegistry.getInstrumentation();
        InspectorMaterialTestActivity host = InspectorWindowTestSupport.start(InspectorMaterialTestActivity.class);
        MemoryWebSocketStore store = new MemoryWebSocketStore(); CaptureController capture = new CaptureController(); capture.startDetailed();
        WebSocketRecorder recorder = new WebSocketRecorder(store, capture, new WebSocketPrivacyPolicy());
        WebSocketRecorder.Token token = recorder.begin("wss://example.test/stream", Collections.emptyList(), WebSocketEvent.Source.HOST).token; assertNotNull(token);
        for (int i = 0; i < 80; i++) assertEquals(WebSocketRecorder.Result.RECORDED, recorder.text(token, WebSocketEvent.Direction.INCOMING, WebSocketEvent.Outcome.RECEIVED,
            "payload-" + i + String.join(" ", Collections.nCopies(30, "mixed 内容"))));
        PreviewHistory history = new PreviewHistory(store); NetworkInspectorView[] viewer = new NetworkInspectorView[1];
        InspectorScroll.List[] list = new InspectorScroll.List[1]; Map<Integer, int[]> frames = new LinkedHashMap<>();
        try {
            runner.runOnMainSync(() -> {
                host.entry.detach(); NetworkInspectorView.State state = new NetworkInspectorView.State(); state.sockets = store.snapshot(); state.paused = true;
                viewer[0] = new NetworkInspectorView(host, null, null, recorder, history, state); host.setContentView(viewer[0]);
                list[0] = findList(viewer[0]); assertNotNull(list[0]); list[0].setSelectionFromTop(21, -11);
            });
            runner.waitForIdleSync();
            runner.runOnMainSync(() -> {
                assertTrue(list[0].getChildCount() > 1);
                for (int i = 0; i < list[0].getChildCount(); i++) frames.put(list[0].getFirstVisiblePosition() + i, new int[] {list[0].getChildAt(i).getTop(), list[0].getChildAt(i).getHeight()});
            });
            history.release(); boolean[] loaded = {false};
            for (int attempt = 0; attempt < 150 && !loaded[0]; attempt++) {
                Thread.sleep(20);
                runner.runOnMainSync(() -> {
                    loaded[0] = true;
                    for (int i = 0; i < list[0].getChildCount(); i++) loaded[0] &= hasPayload(list[0].getChildAt(i));
                });
            }
            assertTrue("Visible previews must arrive", loaded[0]); runner.waitForIdleSync();
            runner.runOnMainSync(() -> {
                for (Map.Entry<Integer, int[]> entry : frames.entrySet()) {
                    View child = list[0].getChildAt(entry.getKey() - list[0].getFirstVisiblePosition()); assertNotNull(child);
                    assertEquals(entry.getValue()[0], child.getTop()); assertEquals(entry.getValue()[1], child.getHeight());
                }
            });
            Thread.sleep(600); assertTrue("Do not read the full retained payload window", history.reads.get() <= 40);
            runner.runOnMainSync(() -> viewer[0].setVisibility(View.GONE)); runner.waitForIdleSync();
            int hidden = history.reads.get(); Thread.sleep(600); assertEquals(hidden, history.reads.get());
        } finally { history.release(); runner.runOnMainSync(host::finish); capture.stopAll(); }
    }
    private static InspectorScroll.List findList(View view) {
        if (view instanceof InspectorScroll.List) return (InspectorScroll.List) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            InspectorScroll.List found = findList(((ViewGroup) view).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
    private static boolean hasPayload(View view) {
        if (view instanceof TextView && ((TextView) view).getText().toString().startsWith("payload-")) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) if (hasPayload(((ViewGroup) view).getChildAt(i))) return true;
        return false;
    }
    private static final class IdentityRows extends BaseAdapter implements InspectorScroll.List.Records {
        final ArrayList<String> ids = new ArrayList<>();
        IdentityRows() { for (int i = 0; i < 100; i++) ids.add(String.valueOf(i)); }
        public int count() { return ids.size(); } public String key(int index) { return ids.get(index); }
        public int getCount() { return count(); } public Object getItem(int position) { return key(position); } public long getItemId(int position) { return position; }
        public View getView(int position, View reused, ViewGroup parent) {
            TextView row = reused instanceof TextView ? (TextView) reused : new TextView(parent.getContext()); row.setHeight(150); row.setText(key(position)); return row;
        }
    }
    private static final class PreviewHistory implements WebSocketHistory {
        final MemoryWebSocketStore store; final Semaphore gate = new Semaphore(0); final AtomicInteger reads = new AtomicInteger(); volatile boolean released;
        PreviewHistory(MemoryWebSocketStore store) { this.store = store; }
        void release() { released = true; gate.release(); }
        public WebSocketSnapshot snapshot() { return store.snapshot(); }
        public PayloadLookup payload(String id) {
            assertNotEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper()); reads.incrementAndGet();
            if (!released) try { gate.tryAcquire(4, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            return store.payload(id);
        }
        public void clear() { fail("Preview work must not delete records"); }
    }
}
