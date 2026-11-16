package com.sandrox.osservio.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.widget.FrameLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class InspectorGlassPolicyTest {
    @Test public void softwareAndUnattachedViewsKeepNativeBackgroundAndReleaseResources() {
        main(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            InspectorGlassSession session = new InspectorGlassSession(context);
            InspectorNavigation source = new InspectorNavigation(context, new View(context), new FrameLayout(context), new View(context));
            InspectorGlassBar bar = new InspectorGlassBar(context, source, session);
            ColorDrawable original = new ColorDrawable(Color.MAGENTA); bar.setBackground(original);
            bar.setPadding(3, 4, 5, 6); bar.layout(0, 0, 100, 40);
            int[] releases = {0};
            bar.renderer = new InspectorGlassBar.Renderer() {
                @Override public void draw(Canvas canvas, View from, View target, float radius, int tint) { fail("Software canvas must not sample"); }
                @Override public void release() { releases[0]++; }
            };
            session.budget.reserve(bar, 100, 40);
            Bitmap image = Bitmap.createBitmap(100, 40, Bitmap.Config.ARGB_8888);
            bar.draw(new Canvas(image));
            assertEquals(Color.MAGENTA, image.getPixel(50, 20)); assertSame(original, bar.getBackground());
            assertEquals(3, bar.getPaddingLeft()); assertEquals(6, bar.getPaddingBottom());
            assertNull(bar.renderer); assertEquals(1, releases[0]); assertEquals(0, session.budget.pixels());
            image.recycle(); session.dispose(); source.dispose();
        });
    }
    @SuppressWarnings("deprecation")
    @Test public void thermalHysteresisRecoversButFailureAndMemoryPressureLockThisViewer() {
        main(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            InspectorGlassSession session = new InspectorGlassSession(context);
            session.thermalChanged(3); assertFalse(session.allowed());
            session.thermalChanged(2); assertFalse(session.allowed());
            session.thermalChanged(1); // Other real system policies may still restrict rendering.
            session.fail(); session.thermalChanged(0); session.setEnabled(false); session.setEnabled(true);
            assertFalse(session.allowed()); assertFalse(session.enabled());
            InspectorGlassSession fresh = new InspectorGlassSession(context); assertTrue(fresh.enabled());
            fresh.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW);
            assertFalse(fresh.enabled()); fresh.dispose(); session.dispose();
        });
    }
    @Test public void rendererReleaseFailureCannotReenterOrLeaveBudgetReserved() {
        main(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            InspectorGlassSession session = new InspectorGlassSession(context);
            InspectorNavigation source = new InspectorNavigation(context, new View(context), new FrameLayout(context), new View(context));
            InspectorGlassBar bar = new InspectorGlassBar(context, source, session);
            bar.renderer = new InspectorGlassBar.Renderer() {
                @Override public void draw(Canvas c, View s, View t, float r, int color) { }
                @Override public void release() { throw new IllegalStateException("Test-owned renderer failure"); }
            };
            session.budget.reserve(bar, 100, 40); bar.release(); bar.release();
            assertNull(bar.renderer); assertEquals(0, session.budget.pixels()); assertFalse(session.enabled());
            source.dispose(); session.dispose();
        });
    }
    private static void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
}
