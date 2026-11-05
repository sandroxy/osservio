package com.sandrox.osservio.runtime;

import com.sandrox.osservio.core.*;
import com.sandrox.osservio.store.MemoryBreadcrumbStore;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RuntimeSignalTest {
    @Test public void invalidatedCallbackCannotEnterReplacementLease() {
        CaptureController capture = new CaptureController(true, LogClock.SYSTEM);
        MemoryBreadcrumbStore history = new MemoryBreadcrumbStore();
        BreadcrumbRecorder recorder = new BreadcrumbRecorder(history, capture);
        RuntimeSignals.Lease old = new RuntimeSignals.Lease(recorder);
        old.record(BreadcrumbEvent.Kind.APP_ACTIVE, "app.active", null, null); old.invalidate();
        RuntimeSignals.Lease replacement = new RuntimeSignals.Lease(recorder);
        old.record(BreadcrumbEvent.Kind.APP_BACKGROUND, "late", null, null);
        replacement.record(BreadcrumbEvent.Kind.APP_FOREGROUND, "new", null, null);
        assertEquals(2, history.snapshot().events.size());
        assertEquals("new", history.snapshot().events.get(1).label);
        assertEquals(BreadcrumbEvent.Retention.RESTART_RELEVANT, history.snapshot().events.get(1).retention);
        capture.stopAll(); replacement.record(BreadcrumbEvent.Kind.APP_BACKGROUND, "off", null, null);
        assertEquals(2, history.snapshot().events.size());
    }
    @Test public void hostSinkControlReentryCannotDeadlockSignalDelivery() {
        RuntimeSignals.Lease[] lease = new RuntimeSignals.Lease[1];
        int[] count = {0};
        BreadcrumbRecorder recorder = new BreadcrumbRecorder(event -> {
            lease[0].invalidate(); count[0]++; return BreadcrumbSink.WriteResult.STORED;
        }, new CaptureController(true, LogClock.SYSTEM));
        lease[0] = new RuntimeSignals.Lease(recorder);
        lease[0].record(BreadcrumbEvent.Kind.APP_ACTIVE, "first", null, null);
        lease[0].record(BreadcrumbEvent.Kind.APP_BACKGROUND, "after-stop", null, null);
        assertEquals(1, count[0]);
    }
}
