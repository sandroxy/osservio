package com.sandrox.osservio.ui;

import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public final class InspectorTabMaterialTest {
    @Test public void realButtonsKeepSelectionDuringPreviewAndCancelFromNonFirstTab() throws Exception {
        InspectorNavigationMaterialActivity host = start();
        try {
            await(() -> host.bar().materialReady());
            main(() -> host.bar().getChildAt(1).performClick()); await(() -> !host.bar().motion.running());
            main(() -> {
                InspectorTabBar bar = host.bar(); assertEquals(1, bar.motion.selected());
                for (int i = 0; i < 4; i++) {
                    AccessibilityNodeInfo info = bar.getChildAt(i).createAccessibilityNodeInfo();
                    assertEquals("android.widget.Button", info.getClassName()); assertEquals(i == 1, info.isSelected());
                    assertEquals(i, info.getCollectionItemInfo().getColumnIndex()); assertTrue(info.isClickable());
                }
                touch(bar, MotionEvent.ACTION_DOWN, 1); touch(bar, MotionEvent.ACTION_MOVE, 3);
                assertTrue(bar.motion.held()); assertEquals(1, bar.motion.selected()); assertEquals(3, bar.motion.destination());
            });
            Thread.sleep(160);
            main(() -> touch(host.bar(), MotionEvent.ACTION_CANCEL, 3)); await(() -> !host.bar().motion.running());
            main(() -> {
                assertEquals(1, host.bar().motion.selected()); assertEquals(1, host.bar().motion.position.value, .001);
                touch(host.bar(), MotionEvent.ACTION_DOWN, 1); touch(host.bar(), MotionEvent.ACTION_MOVE, 3);
                touch(host.bar(), MotionEvent.ACTION_UP, 3); assertEquals(3, host.bar().motion.selected());
                assertTrue(host.bar().getChildAt(3).isSelected());
            });
            await(() -> !host.bar().motion.running());
        } finally { finish(host); }
    }
    @Test public void nativeFallbackSelectionRestoresAndFailureStaysLockedAcrossReopen() throws Exception {
        InspectorNavigationMaterialActivity host = start();
        try {
            await(() -> host.bar().materialReady());
            main(() -> {
                host.inspector.setEnhancedMaterialsEnabled(false); assertNull(host.bar().renderer);
                assertEquals(InspectorTheme.dp(host.bar().getContext(), 1), host.bar().getElevation(), 0);
                host.bar().getChildAt(2).performClick(); assertEquals(2, host.bar().motion.selected());
                host.inspector.setEnhancedMaterialsEnabled(true);
            });
            await(() -> host.bar().materialReady());
            main(() -> {
                assertEquals(2, host.bar().motion.position.value, .001);
                host.bar().renderer.release();
                host.bar().renderer = new InspectorTabBar.Renderer() {
                    @Override public void draw(Canvas canvas, InspectorTabBar bar) { throw new IllegalStateException("test-owned drawing failure"); }
                    @Override public void release() { }
                };
                host.bar().invalidate();
            });
            await(() -> host.bar().renderer == null);
            main(() -> {
                host.bar().getChildAt(3).performClick(); assertEquals(3, host.bar().motion.selected());
                host.presentation.collapse();
            });
            await(() -> !host.bar().isAttachedToWindow()); main(host.presentation::expand);
            await(() -> host.bar().hasWindowFocus());
            main(() -> {
                assertNull(host.bar().renderer); assertFalse(host.bar().materialReady());
                host.bar().getChildAt(0).performClick(); assertEquals(0, host.bar().motion.selected());
            });
        } finally { finish(host); }
    }
    @Test public void themeRebuildPaletteAndHiddenWindowsReleaseOldRenderer() throws Exception {
        InspectorNavigationMaterialActivity host = start();
        try {
            await(() -> host.bar().materialReady()); main(() -> host.bar().getChildAt(3).performClick());
            for (int i = 0; i < 6; i++) {
                InspectorTabBar[] previous = new InspectorTabBar[1]; final boolean dark = i % 2 == 0;
                main(() -> { previous[0] = host.bar(); InspectorPreferences.setDark(host.inspector.getContext(), dark); });
                await(() -> host.bar() != previous[0] && host.bar().materialReady());
                main(() -> {
                    assertNull(previous[0].renderer); assertFalse(previous[0].motion.running());
                    assertEquals(3, host.bar().motion.selected()); assertEquals(3, host.bar().motion.position.value, .001);
                });
            }
            InspectorTabBar[] bar = new InspectorTabBar[1];
            main(() -> {
                bar[0] = host.bar();
                InspectorPreferences.setPalette(host.inspector.getContext(), InspectorPalette.TEAL);
            });
            await(() -> host.bar().materialReady() && ((android.widget.ImageView) ((android.view.ViewGroup) host.bar().getChildAt(3)).getChildAt(0))
                .getImageTintList().getDefaultColor() == InspectorPalette.TEAL.light);
            main(() -> { assertSame(bar[0], host.bar()); assertEquals(3, host.bar().motion.selected()); });
            main(() -> { bar[0] = host.bar(); host.presentation.collapse(); });
            await(() -> !bar[0].isAttachedToWindow());
            main(() -> { assertNull(bar[0].renderer); assertFalse(bar[0].motion.running()); host.presentation.expand(); });
            await(() -> host.bar().materialReady()); main(() -> assertEquals(3, host.bar().motion.selected()));
        } finally { finish(host); }
    }
    @Test public void repeatedCollapseRestoresSelectionAndReleasesEachRendererOnce() throws Exception {
        InspectorNavigationMaterialActivity host = start();
        try {
            await(() -> host.bar().materialReady()); main(() -> host.bar().getChildAt(2).performClick());
            await(() -> !host.bar().motion.running());
            for (int i = 0; i < 32; i++) {
                int[] released = {0};
                main(() -> {
                    InspectorTabBar bar = host.bar(); InspectorTabBar.Renderer delegate = bar.renderer;
                    bar.renderer = new InspectorTabBar.Renderer() {
                        @Override public void draw(Canvas canvas, InspectorTabBar view) { delegate.draw(canvas, view); }
                        @Override public void release() { released[0]++; delegate.release(); }
                    };
                    touch(bar, MotionEvent.ACTION_DOWN, 2); touch(bar, MotionEvent.ACTION_MOVE, 0);
                    host.presentation.collapse();
                });
                await(() -> !host.bar().isAttachedToWindow());
                main(() -> {
                    assertEquals(1, released[0]); assertNull(host.bar().renderer); assertFalse(host.bar().motion.running());
                    assertEquals(2, host.bar().motion.selected()); host.presentation.expand();
                });
                await(() -> host.bar().materialReady());
                main(() -> { assertEquals(2, host.bar().motion.position.value, .001); assertEquals(1, released[0]); });
            }
        } finally { finish(host); }
    }
    @Test public void pointerHandoffDoesNotJumpOrCommitUntilRelease() throws Exception {
        InspectorNavigationMaterialActivity host = start();
        try {
            await(() -> host.bar().materialReady());
            main(() -> {
                InspectorTabBar bar = host.bar(); touch(bar, MotionEvent.ACTION_DOWN, 0);
                multiTouch(bar, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), new int[] {0, 9}, new int[] {0, 1});
                multiTouch(bar, MotionEvent.ACTION_POINTER_UP, new int[] {0, 9}, new int[] {0, 1});
                assertEquals(0, bar.motion.destination());
                multiTouch(bar, MotionEvent.ACTION_MOVE, new int[] {9}, new int[] {3});
                assertEquals(0, bar.motion.selected()); assertEquals(2, bar.motion.destination());
                multiTouch(bar, MotionEvent.ACTION_UP, new int[] {9}, new int[] {3});
                assertEquals(2, bar.motion.selected());
            });
            await(() -> !host.bar().motion.running());
        } finally { finish(host); }
    }
    private static void multiTouch(InspectorTabBar bar, int action, int[] ids, int[] tabs) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[ids.length];
        for (int i = 0; i < ids.length; i++) {
            properties[i] = new MotionEvent.PointerProperties(); properties[i].id = ids[i]; properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords(); View child = bar.getChildAt(tabs[i]);
            coordinates[i].x = child.getLeft() + child.getWidth() / 2f; coordinates[i].y = bar.getHeight() / 2f;
            coordinates[i].pressure = 1; coordinates[i].size = 1;
        }
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, ids.length, properties, coordinates,
            0, 0, 1, 1, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
        try { assertTrue(bar.dispatchTouchEvent(event)); } finally { event.recycle(); }
    }
    @Test public void interruptionAndRtlPreviewKeepTheNativeSelection() throws Exception {
        InspectorNavigationMaterialActivity host = start();
        try {
            await(() -> host.bar().materialReady()); main(() -> host.bar().getChildAt(3).performClick());
            await(() -> !host.bar().motion.running());
            main(() -> {
                touch(host.bar(), MotionEvent.ACTION_DOWN, 3); touch(host.bar(), MotionEvent.ACTION_MOVE, 0);
                host.bar().setMaterialVisible(false);
                assertFalse(host.bar().motion.held()); assertFalse(host.bar().motion.running()); assertNull(host.bar().renderer);
                assertEquals(3, host.bar().motion.selected()); host.bar().setMaterialVisible(true);
            });
            await(() -> host.bar().materialReady());
            main(() -> host.bar().setLayoutDirection(View.LAYOUT_DIRECTION_RTL));
            await(() -> host.bar().materialReady() && host.bar().getChildAt(3).getLeft() < host.bar().getChildAt(0).getLeft());
            main(() -> {
                touch(host.bar(), MotionEvent.ACTION_DOWN, 3); touch(host.bar(), MotionEvent.ACTION_MOVE, 1);
                assertEquals(3, host.bar().motion.selected()); assertEquals(1, host.bar().motion.destination());
                touch(host.bar(), MotionEvent.ACTION_UP, 1); assertEquals(1, host.bar().motion.selected());
            });
            await(() -> !host.bar().motion.running());
        } finally { finish(host); }
    }
    private static void touch(InspectorTabBar bar, int action, int tab) {
        View child = bar.getChildAt(tab); long time = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(time, time, action, child.getLeft() + child.getWidth() / 2f, bar.getHeight() / 2f, 0);
        try { assertTrue(bar.dispatchTouchEvent(event)); } finally { event.recycle(); }
    }
    private static InspectorNavigationMaterialActivity start() throws Exception {
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 33);
        return InspectorWindowTestSupport.start(InspectorNavigationMaterialActivity.class);
    }
    private static void finish(InspectorNavigationMaterialActivity host) { main(host::finish); InstrumentationRegistry.getInstrumentation().waitForIdleSync(); }
    private static void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private static void await(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < deadline) {
            boolean[] result = {false}; main(() -> { try { result[0] = condition.call(); } catch (Exception error) { throw new AssertionError(error); } });
            if (result[0]) return; Thread.sleep(25);
        }
        fail("Timed out waiting for navigation material state");
    }
}
