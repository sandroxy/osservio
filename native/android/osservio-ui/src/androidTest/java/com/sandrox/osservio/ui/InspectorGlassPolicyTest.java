package com.sandrox.osservio.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class InspectorGlassPolicyTest {
    @Test public void entryAppearanceBelongsToHostWhilePaletteAndLanguageRemainLocal() {
        main(() -> {
            Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
            android.content.res.Configuration original = new android.content.res.Configuration(base.getResources().getConfiguration());
            android.content.SharedPreferences preferences = base.getSharedPreferences("entry-appearance-test", Context.MODE_PRIVATE);
            Context context = new android.content.ContextWrapper(base) {
                @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
            };
            InspectorGlassSession session = new InspectorGlassSession(context);
            try {
                InspectorPreferences.setDark(context, true); InspectorPreferences.setPalette(context, InspectorPalette.PURPLE);
                InspectorPreferences.setEnglish(context, false);
                InspectorFloatingEntry entry = new InspectorFloatingEntry(context, false, session, () -> { }, () -> { }, () -> { });
                assertFalse(InspectorTheme.dark(entry.getContext()));
                assertEquals(InspectorPalette.PURPLE.light, entry.getImageTintList().getDefaultColor());
                assertTrue(InspectorTheme.dark(InspectorTheme.context(context)));
                InspectorPreferences.setDark(context, false);
                assertTrue("Inspector appearance alone must not replace the entry", entry.matchesPreferences(false));
                assertFalse(entry.matchesPreferences(true));
                InspectorFloatingEntry darkEntry = new InspectorFloatingEntry(context, true, session, () -> { }, () -> { }, () -> { });
                assertTrue(InspectorTheme.dark(darkEntry.getContext()));
                assertEquals(InspectorPalette.PURPLE.dark, darkEntry.getImageTintList().getDefaultColor());
                InspectorPreferences.setEnglish(context, true);
                assertFalse(darkEntry.matchesPreferences(true));
                InspectorPreferences.setEnglish(context, false); InspectorPreferences.setPalette(context, InspectorPalette.TEAL);
                assertFalse(darkEntry.matchesPreferences(true));
                assertEquals("Scoped overrides must not alter host resources", original, base.getResources().getConfiguration());
            } finally { session.dispose(); }
        });
    }
    @Test public void declaredHostThemeWinsOverAnOppositeSystemMode() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29);
        main(() -> {
            Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
            android.content.res.Configuration configuration = new android.content.res.Configuration(base.getResources().getConfiguration());
            configuration.uiMode = (configuration.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                | android.content.res.Configuration.UI_MODE_NIGHT_YES;
            Context light = new android.view.ContextThemeWrapper(base.createConfigurationContext(configuration), android.R.style.Theme_Material_Light_NoActionBar);
            assertTrue(InspectorTheme.dark(light)); assertFalse(InspectorTheme.hostDark(light));
            configuration.uiMode = (configuration.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                | android.content.res.Configuration.UI_MODE_NIGHT_NO;
            Context dark = new android.view.ContextThemeWrapper(base.createConfigurationContext(configuration), android.R.style.Theme_Material_NoActionBar);
            assertFalse(InspectorTheme.dark(dark)); assertTrue(InspectorTheme.hostDark(dark));
        });
    }
    @Test public void softwareAndUnattachedViewsKeepNativeBackgroundAndReleaseResources() {
        main(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            InspectorGlassSession session = new InspectorGlassSession(context);
            session.setEnabled(true);
            InspectorFloatingEntry entry = new InspectorFloatingEntry(context, InspectorTheme.hostDark(context), session, () -> { }, () -> { }, () -> { });
            InspectorEntryMaterial material = entry.material;
            ColorDrawable original = new ColorDrawable(Color.MAGENTA); entry.setBackground(original);
            entry.setPadding(3, 4, 5, 6); entry.layout(0, 0, 100, 40);
            int[] releases = {0};
            material.renderer = new InspectorEntryMaterial.Renderer() {
                @Override public void draw(Canvas canvas, View from, View target, int x, int y, int base, int tint) { fail("Software canvas must not sample"); }
                @Override public void release() { releases[0]++; }
            };
            session.budget.reserve(material, 100, 40);
            Bitmap image = Bitmap.createBitmap(100, 40, Bitmap.Config.ARGB_8888);
            entry.draw(new Canvas(image));
            assertEquals(Color.MAGENTA, image.getPixel(12, 20)); assertSame(original, entry.getBackground());
            assertEquals(3, entry.getPaddingLeft()); assertEquals(6, entry.getPaddingBottom());
            assertNull(material.renderer); assertEquals(1, releases[0]); assertEquals(0, session.budget.pixels());
            image.recycle(); session.dispose();
        });
    }
    @SuppressWarnings("deprecation")
    @Test public void thermalHysteresisRecoversButFailureAndMemoryPressureLockThisViewer() {
        main(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            InspectorGlassSession session = new InspectorGlassSession(context);
            session.setEnabled(true);
            session.thermalChanged(3); assertFalse(session.allowed());
            session.thermalChanged(2); assertFalse(session.allowed());
            session.thermalChanged(1); // Other real system policies may still restrict rendering.
            session.fail(); session.thermalChanged(0); session.setEnabled(false); session.setEnabled(true);
            assertFalse(session.allowed()); assertFalse(session.enabled());
            InspectorGlassSession fresh = new InspectorGlassSession(context);
            assertFalse(fresh.enabled()); fresh.setEnabled(true); assertTrue(fresh.enabled());
            fresh.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW);
            assertFalse(fresh.enabled()); fresh.dispose(); session.dispose();
        });
    }
    @Test public void rendererReleaseFailureCannotReenterOrLeaveBudgetReserved() {
        main(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            InspectorGlassSession session = new InspectorGlassSession(context);
            session.setEnabled(true);
            InspectorFloatingEntry entry = new InspectorFloatingEntry(context, InspectorTheme.hostDark(context), session, () -> { }, () -> { }, () -> { });
            InspectorEntryMaterial material = entry.material;
            material.renderer = new InspectorEntryMaterial.Renderer() {
                @Override public void draw(Canvas c, View s, View t, int x, int y, int base, int color) { }
                @Override public void release() { throw new IllegalStateException("Test-owned renderer failure"); }
            };
            session.budget.reserve(material, 100, 40); material.release(); material.release();
            assertNull(material.renderer); assertEquals(0, session.budget.pixels()); assertFalse(session.enabled());
            session.dispose();
        });
    }
    private static void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
}
