package com.sandrox.osservio.ui;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.sandrox.osservio.core.*;
import com.sandrox.osservio.store.MemoryBreadcrumbStore;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class BreadcrumbActionsTest {
    @Test public void clearUsesRetainedWindowAndRejectsUnavailableReads() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InspectorTheme.context(InstrumentationRegistry.getInstrumentation().getTargetContext());
            MemoryBreadcrumbStore store = new MemoryBreadcrumbStore(); boolean[] unavailable = {false};
            BreadcrumbHistory history = new BreadcrumbHistory() {
                @Override public BreadcrumbSnapshot snapshot() { return unavailable[0] ? null : store.snapshot(); }
                @Override public void clear() { fail("Checking action availability must not clear history"); }
            };
            CaptureController capture = new CaptureController(false, true, LogClock.SYSTEM);
            try {
                BreadcrumbRecorder recorder = new BreadcrumbRecorder(store, capture);
                BreadcrumbInspectorView viewer = new BreadcrumbInspectorView(context, recorder, history);
                try {
                    java.lang.reflect.Field field = BreadcrumbInspectorView.class.getDeclaredField("header"); field.setAccessible(true);
                    InspectorFeedHeader header = (InspectorFeedHeader) field.get(viewer);
                    Method refresh = BreadcrumbInspectorView.class.getDeclaredMethod("refreshView"); refresh.setAccessible(true);
                    assertNotNull(header); assertFalse(header.actions.isEnabled());
                    refresh.invoke(viewer); assertFalse(header.actions.isEnabled());
                    assertEquals(BreadcrumbRecorder.Result.RECORDED, recorder.record(BreadcrumbEvent.Kind.HOST_MARKER, "retained"));
                    refresh.invoke(viewer); assertTrue(header.actions.isEnabled());
                    header.search.setText("no matching observation"); refresh.invoke(viewer);
                    assertTrue("Filtering must not change the clear target", header.actions.isEnabled());
                    unavailable[0] = true; refresh.invoke(viewer); assertFalse(header.actions.isEnabled());
                    unavailable[0] = false; refresh.invoke(viewer); assertTrue(header.actions.isEnabled());
                    store.clear(); refresh.invoke(viewer); assertFalse(header.actions.isEnabled());
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            } finally { capture.stopAll(); }
        });
    }
}
