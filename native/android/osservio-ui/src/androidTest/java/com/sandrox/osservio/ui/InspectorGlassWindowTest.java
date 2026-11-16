package com.sandrox.osservio.ui;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Canvas;
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
        InspectorMaterialTestActivity host = (InspectorMaterialTestActivity) instrumentation.startActivitySync(
            new Intent(instrumentation.getTargetContext(), InspectorMaterialTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            await(() -> host.bar.renderer != null);
            CountingRenderer[] renderer = new CountingRenderer[1];
            main(() -> { renderer[0] = new CountingRenderer(host.bar.renderer); host.bar.renderer = renderer[0]; host.content.setText("Changed source"); });
            await(() -> renderer[0].draws > 0);
            int[] staticDraws = new int[1]; main(() -> staticDraws[0] = renderer[0].draws);
            Thread.sleep(200); main(() -> assertEquals("Static content must not redraw itself", staticDraws[0], renderer[0].draws));
            main(() -> host.bar.setTranslationY(-30));
            await(() -> renderer[0].draws > staticDraws[0]);
            main(() -> {
                assertTrue(host.session.budget.pixels() > 0);
                host.container.setVisibility(View.INVISIBLE);
                assertNull(host.bar.renderer); assertEquals(0, host.session.budget.pixels()); assertEquals(1, renderer[0].releases);
                host.container.setVisibility(View.VISIBLE);
            });
            await(() -> host.bar.renderer != null);
            main(() -> {
                host.session.setEnabled(false); assertNull(host.bar.renderer); assertEquals(0, host.session.budget.pixels());
                host.session.setEnabled(true); host.bar.updateRegistration();
            });
            await(() -> host.bar.renderer != null);
            main(() -> { host.bar.setMaterialVisible(false); assertNull(host.bar.renderer); assertEquals(0, host.session.budget.pixels()); });
            main(() -> host.bar.setMaterialVisible(true));
            await(() -> host.bar.renderer != null);
            main(() -> {
                host.bar.renderer.release();
                host.bar.renderer = new InspectorGlassBar.Renderer() {
                    @Override public void draw(Canvas c, View s, View t, float r, int color) { throw new IllegalStateException("Test-owned drawing failure"); }
                    @Override public void release() { }
                };
                host.bar.invalidate();
            });
            await(() -> !host.session.enabled());
            main(() -> { assertNull(host.bar.renderer); assertEquals(0, host.session.budget.pixels()); host.content.setText("Still usable"); });
            Thread.sleep(100); main(() -> assertNull(host.bar.renderer));
        } finally { main(host::finish); instrumentation.waitForIdleSync(); }
    }
    private static final class CountingRenderer implements InspectorGlassBar.Renderer {
        final InspectorGlassBar.Renderer delegate; int draws, releases;
        CountingRenderer(InspectorGlassBar.Renderer delegate) { this.delegate = delegate; }
        @Override public void draw(Canvas canvas, View source, View target, float radius, int tint) { draws++; delegate.draw(canvas, source, target, radius, tint); }
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
