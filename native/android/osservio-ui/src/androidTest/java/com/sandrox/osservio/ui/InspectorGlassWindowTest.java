package com.sandrox.osservio.ui;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.Gravity;
import android.view.TextureView;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public final class InspectorGlassWindowTest {
    @Test public void hardwareSamplingUpdatesWithoutIdleLoopAndReleasesOnHide() throws Exception {
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 33);
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        InspectorMaterialTestActivity host = InspectorWindowTestSupport.start(InspectorMaterialTestActivity.class);
        try {
            await(() -> host.entry.isLaidOut() && host.entry.hasWindowFocus());
            main(() -> {
                assertFalse(host.session.enabled()); assertNull(host.entry.material.renderer);
                assertEquals(0, host.session.budget.pixels());
                host.content.setText("Native backdrop update");
            });
            instrumentation.waitForIdleSync();
            main(() -> {
                assertNull(host.entry.material.renderer); assertEquals(0, host.session.budget.pixels());
                host.session.setEnabled(true); host.entry.material.updateRegistration();
            });
            await(() -> host.entry.material.renderer != null);
            CountingRenderer[] renderer = new CountingRenderer[1];
            main(() -> { renderer[0] = new CountingRenderer(host.entry.material.renderer); host.entry.material.renderer = renderer[0]; host.content.setText("Changed source"); });
            await(() -> renderer[0].draws > 0);
            int[] staticDraws = new int[1]; main(() -> staticDraws[0] = renderer[0].draws);
            Thread.sleep(200); main(() -> assertEquals("Static content must not redraw itself", staticDraws[0], renderer[0].draws));
            main(() -> host.entry.setTranslationY(host.entry.getY() - 30));
            await(() -> renderer[0].draws > staticDraws[0]);
            main(() -> {
                assertTrue(host.session.budget.pixels() > 0);
                host.entry.setVisibility(View.INVISIBLE);
                assertNull(host.entry.material.renderer); assertEquals(0, host.session.budget.pixels()); assertEquals(1, renderer[0].releases);
                host.entry.setVisibility(View.VISIBLE);
            });
            await(() -> host.entry.material.renderer != null);
            main(() -> {
                host.session.setEnabled(false); assertNull(host.entry.material.renderer); assertEquals(0, host.session.budget.pixels());
                host.session.setEnabled(true); host.entry.material.updateRegistration();
            });
            await(() -> host.entry.material.renderer != null);
            main(() -> { host.entry.material.bind(null); assertNull(host.entry.material.renderer); assertEquals(0, host.session.budget.pixels()); });
            main(() -> host.entry.material.bind(host.getWindow()));
            await(() -> host.entry.material.renderer != null);
            main(() -> {
                host.entry.material.renderer.release();
                host.entry.material.renderer = new InspectorEntryMaterial.Renderer() {
                    @Override public void draw(Canvas c, View s, View t, int x, int y, int base, int color) { throw new IllegalStateException("Test-owned drawing failure"); }
                    @Override public void release() { }
                };
                host.entry.invalidate();
            });
            await(() -> !host.session.enabled());
            main(() -> { assertNull(host.entry.material.renderer); assertEquals(0, host.session.budget.pixels()); host.content.setText("Still usable"); });
            Thread.sleep(100); main(() -> assertNull(host.entry.material.renderer));
        } finally { main(host::finish); instrumentation.waitForIdleSync(); }
    }
    @Test public void pixelsFollowHostChangesAndTheDockedPosition() throws Exception {
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 33);
        InspectorMaterialTestActivity host = InspectorWindowTestSupport.start(InspectorMaterialTestActivity.class);
        try {
            await(() -> host.entry.isLaidOut() && host.entry.hasWindowFocus());
            View[] right = new View[1];
            main(() -> {
                host.content.setBackgroundColor(Color.RED);
                right[0] = new View(host);
                right[0].setBackgroundColor(Color.BLUE);
                host.container.addView(right[0], new FrameLayout.LayoutParams(host.container.getWidth() / 2, -1, Gravity.RIGHT));
                host.session.setEnabled(true); host.entry.material.updateRegistration();
            });
            await(() -> host.entry.material.renderer != null);
            main(() -> host.entry.setX(host.container.getWidth() - host.entry.getWidth() - 12));
            awaitLensColor(host, Color.BLUE);
            main(() -> right[0].setBackgroundColor(Color.GREEN));
            awaitLensColor(host, Color.GREEN);
            main(() -> host.entry.setX(12));
            awaitLensColor(host, Color.RED);
        } finally { main(host::finish); }
    }
    private static void awaitLensColor(InspectorMaterialTestActivity host, int expected) throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        int[] point = new int[2];
        int actual = 0;
        long end = android.os.SystemClock.uptimeMillis() + 4000;
        do {
            main(() -> {
                host.entry.getLocationOnScreen(point);
                // Inside the lens, away from the symbol and specular edge.
                point[0] += host.entry.getWidth() / 6; point[1] += host.entry.getHeight() / 2;
            });
            Bitmap frame = instrumentation.getUiAutomation().takeScreenshot();
            assertNotNull(frame);
            actual = frame.getPixel(point[0], point[1]); frame.recycle();
            int r = Color.red(actual), g = Color.green(actual), b = Color.blue(actual);
            if ((expected == Color.RED && r > Math.max(g, b) + 60)
                || (expected == Color.GREEN && g > Math.max(r, b) + 60)
                || (expected == Color.BLUE && b > Math.max(r, g) + 60)) return;
            Thread.sleep(30);
        } while (android.os.SystemClock.uptimeMillis() < end);
        fail("Expected lens hue " + Integer.toHexString(expected) + ", got " + Integer.toHexString(actual));
    }

    @Test public void secureWindowsAndOverlappingExternalSurfacesKeepTheNativeButton() throws Exception {
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 33);
        InspectorMaterialTestActivity host = InspectorWindowTestSupport.start(InspectorMaterialTestActivity.class);
        try {
            await(() -> host.entry.hasWindowFocus());
            main(() -> { host.session.setEnabled(true); host.entry.material.updateRegistration(); });
            await(() -> host.entry.material.renderer != null);
            main(() -> host.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE));
            await(() -> host.entry.material.renderer == null && host.session.budget.pixels() == 0);
            main(() -> host.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE));
            await(() -> host.entry.material.renderer != null);
            TextureView[] external = new TextureView[1];
            main(() -> {
                external[0] = new TextureView(host);
                host.container.addView(external[0], new FrameLayout.LayoutParams(-1, -1));
            });
            await(() -> host.entry.material.renderer == null && host.session.budget.pixels() == 0);
            main(() -> {
                assertTrue(host.entry.isClickable());
                external[0].setLayoutParams(new FrameLayout.LayoutParams(8, 8, Gravity.TOP | Gravity.LEFT));
            });
            await(() -> host.entry.material.renderer != null);
        } finally { main(host::finish); }
    }
    private static final class CountingRenderer implements InspectorEntryMaterial.Renderer {
        final InspectorEntryMaterial.Renderer delegate; int draws, releases;
        CountingRenderer(InspectorEntryMaterial.Renderer delegate) { this.delegate = delegate; }
        @Override public void draw(Canvas canvas, View source, View target, int x, int y, int base, int tint) { draws++; delegate.draw(canvas, source, target, x, y, base, tint); }
        @Override public void release() { releases++; delegate.release(); }
    }
    private static void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private static void await(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long deadline = android.os.SystemClock.uptimeMillis() + 4000;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            boolean[] value = {false}; main(() -> { try { value[0] = condition.call(); } catch (Exception failure) { throw new AssertionError(failure); } });
            if (value[0]) return; Thread.sleep(30);
        }
        fail("Timed out waiting for material state");
    }
}
