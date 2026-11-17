package com.sandrox.osservio.runtime;

import com.sandrox.osservio.core.*;
import com.sandrox.osservio.store.MemoryBreadcrumbStore;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NetworkPathSignalTest {
    @Test public void diagnosticClockFailureDoesNotEscapeOrSuppressTheNextObservation() {
        boolean[] failing = {false}; int[] writes = {0};
        LogClock clock = new LogClock() {
            @Override public long monotonicMillis() { return 1; }
            @Override public long wallTimeMillis() { if (failing[0]) throw new IllegalStateException("fixture clock"); return 1; }
        };
        BreadcrumbRecorder recorder = new BreadcrumbRecorder(event -> { writes[0]++; return BreadcrumbSink.WriteResult.STORED; },
            new CaptureController(true, clock));
        NetworkPathSignals.Lease lease = new NetworkPathSignals.Lease(recorder);
        failing[0] = true; lease.record("status=available", false); assertEquals(0, writes[0]);
        failing[0] = false; lease.record("status=available", false); assertEquals(1, writes[0]);
    }
    @Test public void changesAreDeduplicatedButNotAcrossOffOrRegistrationLifetimes() {
        CaptureController capture = new CaptureController(true, LogClock.SYSTEM);
        MemoryBreadcrumbStore history = new MemoryBreadcrumbStore();
        BreadcrumbRecorder recorder = new BreadcrumbRecorder(history, capture);
        NetworkPathSignals.Lease old = new NetworkPathSignals.Lease(recorder);
        old.record("status=available; interfaces=wifi", false);
        old.record("status=available; interfaces=wifi", false);
        old.record("status=unavailable", true); // A late startup snapshot cannot replace a callback.
        assertEquals(1, history.snapshot().events.size());
        capture.stopAll(); old.record("status=unavailable", false); assertEquals(1, history.snapshot().events.size());
        capture.setBaselineEnabled(true); old.record("status=available; interfaces=wifi", false);
        assertEquals(2, history.snapshot().events.size()); old.invalidate(); old.record("status=unavailable", false);
        new NetworkPathSignals.Lease(recorder).record("status=unavailable", false);
        assertEquals(3, history.snapshot().events.size());
        assertEquals(BreadcrumbEvent.Kind.NETWORK_PATH, history.snapshot().events.get(2).kind);
        assertEquals(BreadcrumbEvent.Retention.RESTART_RELEVANT, history.snapshot().events.get(2).retention);
    }
    @Test public void failedAdmissionIsNotRememberedAsARecordedPath() {
        int[] writes = {0};
        BreadcrumbRecorder recorder = new BreadcrumbRecorder(event -> ++writes[0] == 1 ? BreadcrumbSink.WriteResult.BUSY : BreadcrumbSink.WriteResult.STORED,
            new CaptureController(true, LogClock.SYSTEM));
        NetworkPathSignals.Lease lease = new NetworkPathSignals.Lease(recorder);
        lease.record("status=available", false); lease.record("status=available", false); lease.record("status=available", false);
        assertEquals(2, writes[0]);
    }
    @Test public void hostSinkCanInvalidateWithoutDeadlockOrRecursiveDelivery() {
        NetworkPathSignals.Lease[] lease = new NetworkPathSignals.Lease[1]; int[] writes = {0};
        BreadcrumbRecorder recorder = new BreadcrumbRecorder(event -> {
            writes[0]++; lease[0].record("recursive", false); lease[0].invalidate(); return BreadcrumbSink.WriteResult.STORED;
        }, new CaptureController(true, LogClock.SYSTEM));
        lease[0] = new NetworkPathSignals.Lease(recorder); lease[0].record("status=unavailable", false); lease[0].record("late", false);
        assertEquals(1, writes[0]);
    }
}
