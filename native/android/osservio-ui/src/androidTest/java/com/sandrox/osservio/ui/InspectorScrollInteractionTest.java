package com.sandrox.osservio.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.Callable;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Window-attached input paths, including overflow created solely by navigation insets. */
@RunWith(AndroidJUnit4.class)
public final class InspectorScrollInteractionTest {
    @Test public void insetOverflowCanBeDraggedFromClickableAndSelectableContent() throws Exception {
        InspectorMaterialTestActivity host = InspectorWindowTestSupport.start(InspectorMaterialTestActivity.class);
        try {
            for (boolean selectable : new boolean[] {false, true}) {
                InspectorScroll.Page[] page = {null}; TextView[] content = {null}; int[] clicks = {0};
                main(() -> {
                    host.entry.detach(); host.container.removeAllViews();
                    page[0] = new InspectorScroll.Page(host);
                    page[0].setPadding(0, 12, 0, 180); page[0].setClipToPadding(false);
                    content[0] = new TextView(host); content[0].setText("Retained record\nSelectable metadata"); content[0].setHeight(570);
                    if (selectable) content[0].setTextIsSelectable(true);
                    else content[0].setOnClickListener(view -> clicks[0]++);
                    page[0].addView(content[0], new android.widget.ScrollView.LayoutParams(-1, 570));
                    host.container.addView(page[0], new FrameLayout.LayoutParams(-1, 600));
                });
                await(() -> page[0].getHeight() == 600 && content[0].getHeight() == 570);
                // Start on a child that owns DOWN. The parent must intercept a vertical MOVE.
                drag(content[0], -120);
                main(() -> {
                    assertTrue("Insets must not strand a drag on the child, selectable=" + selectable, page[0].getScrollY() > 0);
                    assertEquals(0, clicks[0]);
                    assertTrue(page[0].canScrollVertically(-1));
                });
                drag(content[0], 200);
                main(() -> { assertEquals(0, page[0].getScrollY()); assertFalse(page[0].canScrollVertically(-1)); });
                if (!selectable) {
                    // Let the native edge spring finish before starting a separate tap.
                    Thread.sleep(350);
                    tap(content[0]); await(() -> clicks[0] == 1);
                }
            }
        } finally { main(host::finish); }
    }

    @Test public void scrollReachabilityTracksPaddingContentAndBothEdges() {
        main(() -> {
            InspectorScroll.Page page = new InspectorScroll.Page(InstrumentationRegistry.getInstrumentation().getTargetContext());
            assertFalse(page.canScrollVertically(1));
            TextView content = new TextView(page.getContext()); page.addView(content);
            for (int top : new int[] {0, 12}) for (int bottom : new int[] {0, 180}) {
                page.setPadding(0, top, 0, bottom);
                for (int height : new int[] {300, 600 - top - bottom, 601 - top - bottom, 570, 900}) {
                    content.setHeight(height);
                    content.setLayoutParams(new android.widget.ScrollView.LayoutParams(-1, height));
                    page.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
                    page.layout(0, 0, 400, 600); page.scrollTo(0, 0);
                    int range = Math.max(0, height - (600 - top - bottom));
                    assertEquals("Leading edge: top=" + top + " bottom=" + bottom + " content=" + height, range > 0, page.canScrollVertically(1));
                    assertFalse(page.canScrollVertically(-1));
                    page.scrollTo(0, 2000);
                    assertEquals(range, page.getScrollY());
                    assertFalse(page.canScrollVertically(1));
                    assertEquals(range > 0, page.canScrollVertically(-1));
                }
            }
        });
    }

    @Test public void returningToThemedRowsDoesNotReplayUndrawnPressFeedback() throws Exception {
        assertRowFeedback(true);
    }

    @Test public void returningToLightRowsDoesNotReplayUndrawnPressFeedback() throws Exception {
        assertRowFeedback(false);
    }

    private static void assertRowFeedback(boolean dark) throws Exception {
        InspectorMaterialTestActivity host = InspectorWindowTestSupport.start(InspectorMaterialTestActivity.class);
        try {
            InspectorScroll.List[] list = {null}; FrameLayout[] parent = {null}; int[] clicks = {0};
            main(() -> {
                host.entry.detach(); host.container.removeAllViews();
                Context context = InspectorTheme.context(host, dark);
                parent[0] = new FrameLayout(context); list[0] = new InspectorScroll.List(context);
                parent[0].setBackgroundColor(InspectorTheme.canvas(context));
                list[0].setAdapter(new BaseAdapter() {
                    @Override public int getCount() { return 3; }
                    @Override public Object getItem(int position) { return position; }
                    @Override public long getItemId(int position) { return position; }
                    @Override public View getView(int position, View recycled, ViewGroup owner) {
                        InspectorWidgets.EventRow row = recycled instanceof InspectorWidgets.EventRow
                            ? (InspectorWidgets.EventRow) recycled : new InspectorWidgets.EventRow(context);
                        row.bind("INFO", "Record " + position, "Retained metadata", 1, InspectorTheme.accent(context));
                        row.grouped(position == 0, position == 2); return row;
                    }
                });
                list[0].setOnItemClickListener((owner, view, position, id) -> { clicks[0]++; parent[0].setVisibility(View.GONE); });
                parent[0].addView(list[0]);
                FrameLayout.LayoutParams placement = new FrameLayout.LayoutParams(-1, -1);
                placement.topMargin = InspectorTheme.dp(context, 40);
                host.container.addView(parent[0], placement);
            });
            await(() -> list[0].getChildCount() == 3);
            View row = list[0].getChildAt(0); Rect area = bounds(row);
            // Activity launch includes a system window transition, outside row feedback.
            Thread.sleep(800);
            Bitmap baseline = capture(area);
            try {
                // A press can finish and navigate away before HWUI ever draws its ripple.
                // Test the real row background; the SDK theme disables the ListView selector.
                for (int hidden : new int[] {View.GONE, View.INVISIBLE}) {
                    main(() -> { row.setPressed(true); row.setPressed(false); parent[0].setVisibility(hidden); });
                    Thread.sleep(200);
                    main(() -> parent[0].setVisibility(View.VISIBLE));
                    assertNoPulse(baseline, area);
                    main(() -> assertSame(row, list[0].getChildAt(0)));
                }
                main(() -> { row.setPressed(true); row.setPressed(false); row.jumpDrawablesToCurrentState(); });
                assertNoPulse(baseline, area);
                Rect touch = bounds(row); long down = SystemClock.uptimeMillis();
                input(down, MotionEvent.ACTION_DOWN, touch.centerX(), touch.centerY());
                Thread.sleep(200);
                main(() -> { assertSame(row, list[0].getChildAt(0)); assertTrue("A real touch must press the visible row", row.isPressed()); });
                Bitmap pressed = capture(area);
                try {
                    int changed = changedPixels(baseline, pressed, 2);
                    assertTrue("Normal held touches must still have visible feedback: " + changed, changed > baseline.getWidth() * baseline.getHeight() / 200);
                }
                finally { pressed.recycle(); }
                input(down, MotionEvent.ACTION_UP, touch.centerX(), touch.centerY());
                await(() -> clicks[0] == 1);
                Thread.sleep(200);
                main(() -> parent[0].setVisibility(View.VISIBLE));
                assertNoPulse(baseline, area);
                tap(row); await(() -> clicks[0] == 2);
                main(() -> parent[0].setVisibility(View.VISIBLE));
                assertNoPulse(baseline, area);
                main(() -> { row.setPressed(true); row.setPressed(false); host.container.removeView(parent[0]); });
                Thread.sleep(200);
                main(() -> host.container.addView(parent[0]));
                assertNoPulse(baseline, area);
            } finally { baseline.recycle(); }
        } finally { main(host::finish); }
    }

    private static void assertNoPulse(Bitmap baseline, Rect area) throws Exception {
        for (int frame = 0; frame < 8; frame++) {
            Thread.sleep(45);
            Bitmap current = capture(area);
            try {
                int changed = changedPixels(baseline, current, 8);
                if (changed > baseline.getWidth() * baseline.getHeight() / 200) {
                    save(baseline, "feedback-before.png"); save(current, "feedback-after.png");
                }
                assertTrue("Returning must not start a new press pulse, sample=" + frame + " changed=" + changed,
                    changed <= baseline.getWidth() * baseline.getHeight() / 200);
            }
            finally { current.recycle(); }
        }
    }
    private static void save(Bitmap image, String name) throws Exception {
        java.io.File file = new java.io.File(InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(), name);
        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(file)) { image.compress(Bitmap.CompressFormat.PNG, 100, stream); }
    }
    private static Bitmap capture(Rect area) {
        Bitmap screen = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(screen);
        try { return Bitmap.createBitmap(screen, area.left, area.top, area.width(), area.height()); }
        finally { screen.recycle(); }
    }
    private static int changedPixels(Bitmap before, Bitmap after, int tolerance) {
        int count = 0;
        for (int y = 0; y < before.getHeight(); y++) for (int x = 0; x < before.getWidth(); x++) {
            int a = before.getPixel(x, y), b = after.getPixel(x, y);
            if (Math.abs(Color.red(a) - Color.red(b)) > tolerance || Math.abs(Color.green(a) - Color.green(b)) > tolerance || Math.abs(Color.blue(a) - Color.blue(b)) > tolerance) count++;
        }
        return count;
    }

    private static void tap(View view) throws Exception {
        Rect rect = bounds(view); long down = SystemClock.uptimeMillis();
        input(down, MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY());
        Thread.sleep(120); input(down, MotionEvent.ACTION_UP, rect.centerX(), rect.centerY());
    }
    private static void drag(View view, int distance) throws Exception {
        Rect rect = bounds(view); float x = rect.centerX(), y = rect.centerY(); long down = SystemClock.uptimeMillis();
        input(down, MotionEvent.ACTION_DOWN, x, y);
        for (int step = 1; step <= 8; step++) { Thread.sleep(24); input(down, MotionEvent.ACTION_MOVE, x, y + distance * step / 8f); }
        input(down, MotionEvent.ACTION_CANCEL, x, y + distance);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private static Rect bounds(View view) {
        Rect rect = new Rect(); main(() -> assertTrue(view.getGlobalVisibleRect(rect))); return rect;
    }
    private static void input(long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        try { InstrumentationRegistry.getInstrumentation().sendPointerSync(event); } finally { event.recycle(); }
    }
    private static void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 4000;
        do {
            boolean[] ready = {false}; main(() -> { try { ready[0] = condition.call(); } catch (Exception failure) { throw new AssertionError(failure); } });
            if (ready[0]) return; Thread.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("View did not reach the expected input state");
    }
}
