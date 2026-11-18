package com.sandrox.osservio.ui;

import android.os.IBinder;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class InspectorSessionWindowTest {
    @Test public void systemBackgroundReleasesMaterialWithoutDiscardingExpandedWindow() throws Exception {
        InspectorNavigationMaterialActivity host = InspectorWindowTestSupport.start(InspectorNavigationMaterialActivity.class);
        View[] root = new View[1]; IBinder[] token = new IBinder[1];
        try {
            await(() -> host.inspector != null && host.inspector.hasWindowFocus() && host.inspector.getAlpha() == 1);
            main(() -> { root[0] = host.inspector.getRootView(); token[0] = host.inspector.getWindowToken(); });
            assertTrue(InstrumentationRegistry.getInstrumentation().getUiAutomation().performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME));
            await(() -> !host.inspector.hasWindowFocus() && host.bar().renderer == null);
            main(() -> {
                assertSame(root[0], host.inspector.getRootView()); assertSame(token[0], host.inspector.getWindowToken());
                assertEquals(InspectorSession.Presentation.EXPANDED, host.presentation.presentation());
            });
            String component = host.getPackageName() + "/" + host.getClass().getName();
            try (android.os.ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .executeShellCommand("am start -W -f 0x30000000 -n " + component);
                 java.io.InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                byte[] buffer = new byte[1024]; while (input.read(buffer) != -1) { }
            }
            await(() -> host.inspector.hasWindowFocus());
            main(() -> {
                assertSame(root[0], host.inspector.getRootView()); assertSame(token[0], host.inspector.getWindowToken());
                assertEquals(1, host.inspector.getAlpha(), 0);
            });
        } finally { main(host::finish); }
    }
    @Test public void interruptionRetainsWindowAndFilterDraftWithoutReplayingEntry() throws Exception {
        InspectorNavigationMaterialActivity host = InspectorWindowTestSupport.start(InspectorNavigationMaterialActivity.class);
        try {
            await(() -> host.inspector != null && host.inspector.hasWindowFocus() && host.inspector.getAlpha() == 1);
            Thread.sleep(400);
            main(() -> {
                IBinder token = host.inspector.getWindowToken(); View root = host.inspector.getRootView();
                InspectorScreen filter = InspectorFilterSheet.show(host.inspector.getContext(), new InspectorQuery(),
                    java.util.Collections.emptyList(), ignored -> fail("Interruption must not apply a draft"));
                host.presentation.onActivityPaused(host);
                assertTrue("Pausing must keep the inspector in the window composition", host.inspector.isAttachedToWindow());
                assertSame(token, host.inspector.getWindowToken());
                assertTrue(filter.isShowing());
                host.presentation.onActivityStopped(host);
                host.presentation.onActivityStarted(host);
                host.presentation.onActivityResumed(host);
                assertSame(root, host.inspector.getRootView());
                assertEquals("Restoration must not start from transparent", 1, host.inspector.getAlpha(), 0);
                assertTrue(filter.isShowing());
                assertEquals(InspectorSession.Presentation.EXPANDED, host.presentation.presentation());
                filter.dismiss();
            });
        } finally { main(host::finish); }
    }
    @Test public void explicitHideWhilePausedDoesNotReopenOnResume() throws Exception {
        InspectorNavigationMaterialActivity host = InspectorWindowTestSupport.start(InspectorNavigationMaterialActivity.class);
        try {
            await(() -> host.inspector != null && host.inspector.hasWindowFocus());
            main(() -> {
                host.presentation.onActivityPaused(host);
                host.presentation.hideEntry();
                assertFalse(host.inspector.isAttachedToWindow());
                host.presentation.onActivityResumed(host);
                assertFalse(host.inspector.isAttachedToWindow());
                assertEquals(InspectorSession.Presentation.HIDDEN, host.presentation.presentation());
            });
        } finally { main(host::finish); }
    }
    @Test public void floatingEntryMaterialFollowsTheSessionAndItsIndependentOptOut() throws Exception {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 33);
        InspectorNavigationMaterialActivity host = InspectorWindowTestSupport.start(InspectorNavigationMaterialActivity.class);
        try {
            await(() -> host.inspector != null && host.inspector.hasWindowFocus());
            main(host.presentation::collapse);
            InspectorFloatingEntry[] entry = new InspectorFloatingEntry[1];
            await(() -> {
                entry[0] = InspectorNavigationMaterialActivity.find(host.getWindow().getDecorView(), InspectorFloatingEntry.class);
                return entry[0] != null && entry[0].material.renderer != null && entry[0].hasWindowFocus();
            });
            main(() -> {
                host.presentation.setFloatingEntryMaterialEnabled(false);
                assertNull(entry[0].material.renderer);
                assertEquals(InspectorSession.Presentation.ENTRY, host.presentation.presentation());
                host.presentation.setFloatingEntryMaterialEnabled(true);
            });
            await(() -> entry[0].material.renderer != null);
            main(() -> {
                host.presentation.onActivityPaused(host);
                assertNull(entry[0].material.renderer);
                host.presentation.onActivityResumed(host);
            });
            await(() -> entry[0].material.renderer != null);
            main(() -> { assertTrue(entry[0].performClick()); assertNull(entry[0].material.renderer); });
            await(() -> host.inspector.hasWindowFocus() && host.presentation.presentation() == InspectorSession.Presentation.EXPANDED);
        } finally { main(host::finish); }
    }
    private static void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private static void await(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long deadline = android.os.SystemClock.uptimeMillis() + 5000;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            boolean[] ready = {false}; main(() -> { try { ready[0] = condition.call(); } catch (Exception failure) { throw new AssertionError(failure); } });
            if (ready[0]) return;
            Thread.sleep(30);
        }
        fail("Inspector did not become ready");
    }
}
