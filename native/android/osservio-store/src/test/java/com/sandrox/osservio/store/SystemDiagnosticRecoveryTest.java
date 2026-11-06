package com.sandrox.osservio.store;

import com.sandrox.osservio.core.CaptureController;
import com.sandrox.osservio.core.DiagnosticRecoveryArchive;
import com.sandrox.osservio.core.DiagnosticRecoveryReceipt;
import com.sandrox.osservio.core.LogClock;
import com.sandrox.osservio.core.PersistenceStatus;
import com.sandrox.osservio.core.SystemDiagnostic;
import com.sandrox.osservio.core.SystemDiagnosticBatch;
import com.sandrox.osservio.core.SystemDiagnosticProvider;
import com.sandrox.osservio.core.SystemDiagnosticRecovery;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SystemDiagnosticRecoveryTest {
    private static final class Source implements SystemDiagnosticProvider {
        final List<Completion> deliveries = new ArrayList<>();
        @Override public void read(Completion completion) { deliveries.add(completion); }
    }
    private static final class Archive implements DiagnosticRecoveryArchive {
        final List<SystemDiagnosticBatch> batches = new ArrayList<>();
        final List<RecoveryCompletion> deliveries = new ArrayList<>();
        @Override public void recoverDiagnostics(SystemDiagnosticBatch batch, RecoveryCompletion completion) { batches.add(batch); deliveries.add(completion); }
    }
    private static final class Result implements SystemDiagnosticRecovery.Completion {
        int count; DiagnosticRecoveryReceipt receipt; SystemDiagnosticRecovery.Error error;
        @Override public void complete(DiagnosticRecoveryReceipt value, SystemDiagnosticRecovery.Error failure) { count++; receipt = value; error = failure; }
        void failure(SystemDiagnosticRecovery.Error.Kind kind) { assertNotNull(error); assertEquals(kind, error.kind); assertNull(receipt); }
    }
    private static final class Clock implements LogClock {
        long time;
        @Override public long monotonicMillis() { return time; }
        @Override public long wallTimeMillis() { return 100; }
    }
    private static SystemDiagnosticBatch batch() {
        SystemDiagnostic diagnostic = SystemDiagnostic.metricKit(10, 20, SystemDiagnostic.Kind.CRASH, Collections.emptyMap(), null, null, null, null);
        return new SystemDiagnosticBatch(SystemDiagnostic.Source.METRIC_KIT, 42, Collections.singletonList(diagnostic), 0, 0, false);
    }
    private static DiagnosticRecoveryReceipt receipt(SystemDiagnosticBatch batch) { return new DiagnosticRecoveryReceipt(batch, 1, 0, 0, 0, 0); }
    @Test public void offStopAndSessionReplacementRejectUnadmittedHistory() {
        CaptureController capture = new CaptureController(); Source source = new Source(); Archive archive = new Archive(); Result result = new Result();
        SystemDiagnosticRecovery recovery = new SystemDiagnosticRecovery(capture, archive);
        recovery.recover(source, result); result.failure(SystemDiagnosticRecovery.Error.Kind.INACTIVE); assertTrue(source.deliveries.isEmpty());
        assertTrue(capture.setBaselineEnabled(true)); recovery.recover(source, result);
        assertTrue(capture.stopAll()); source.deliveries.get(0).complete(batch(), null); result.failure(SystemDiagnosticRecovery.Error.Kind.INACTIVE);
        assertTrue(capture.setBaselineEnabled(true)); recovery.recover(source, result);
        assertTrue(capture.stopAll()); assertTrue(capture.setBaselineEnabled(true)); source.deliveries.get(1).complete(batch(), null);
        result.failure(SystemDiagnosticRecovery.Error.Kind.CAPTURE_CHANGED); assertTrue(archive.batches.isEmpty()); assertEquals(3, result.count);
    }
    @Test public void busyCloseAndLateCallbacksCannotReplayHistory() {
        Source source = new Source(); Archive archive = new Archive(); Result result = new Result(), rejected = new Result();
        SystemDiagnosticRecovery recovery = new SystemDiagnosticRecovery(new CaptureController(true, LogClock.SYSTEM), archive);
        recovery.recover(source, result); recovery.recover(batch(), rejected); rejected.failure(SystemDiagnosticRecovery.Error.Kind.BUSY);
        recovery.close(); recovery.close(); result.failure(SystemDiagnosticRecovery.Error.Kind.CLOSED);
        source.deliveries.get(0).complete(batch(), null); source.deliveries.get(0).complete(null, SystemDiagnosticProvider.Error.UNAVAILABLE);
        assertTrue(archive.batches.isEmpty()); assertEquals(1, result.count);
        recovery.recover(source, rejected); rejected.failure(SystemDiagnosticRecovery.Error.Kind.CLOSED); assertEquals(1, source.deliveries.size());
    }
    @Test public void acceptedPersistenceFinishesAfterStopAndCloseWithoutInventingContext() {
        CaptureController capture = new CaptureController(true, LogClock.SYSTEM); Archive archive = new Archive(); Result result = new Result();
        SystemDiagnosticRecovery recovery = new SystemDiagnosticRecovery(capture, archive); SystemDiagnosticBatch records = batch();
        recovery.recover(records, result); assertEquals(1, archive.batches.size()); assertSame(records, archive.batches.get(0));
        assertTrue(capture.stopAll()); recovery.close(); assertEquals(0, result.count);
        DiagnosticRecoveryReceipt value = receipt(records); archive.deliveries.get(0).complete(value, null); archive.deliveries.get(0).complete(value, null);
        assertEquals(1, result.receipt.savedCount); assertEquals(1, result.count); assertNull(result.error);
    }
    @Test public void typedFailuresPermitExplicitRetryAndCompletionReentry() {
        Source source = new Source(); Archive archive = new Archive(); Result result = new Result();
        SystemDiagnosticRecovery recovery = new SystemDiagnosticRecovery(new CaptureController(true, LogClock.SYSTEM), archive);
        recovery.recover(source, result); source.deliveries.get(0).complete(null, SystemDiagnosticProvider.Error.UNSUPPORTED);
        result.failure(SystemDiagnosticRecovery.Error.Kind.SOURCE); assertEquals(SystemDiagnosticProvider.Error.UNSUPPORTED, result.error.sourceError);
        SystemDiagnosticBatch records = batch();
        recovery.recover(records, (value, error) -> { result.complete(value, error); recovery.recover(source, result); });
        archive.deliveries.get(0).complete(null, PersistenceStatus.Error.STORAGE_FULL);
        result.failure(SystemDiagnosticRecovery.Error.Kind.PERSISTENCE); assertEquals(PersistenceStatus.Error.STORAGE_FULL, result.error.persistenceError);
        assertEquals(2, source.deliveries.size());
        source.deliveries.get(1).complete(records, null); source.deliveries.get(1).complete(null, SystemDiagnosticProvider.Error.UNAVAILABLE);
        assertEquals(2, archive.batches.size()); assertEquals(2, result.count);
        archive.deliveries.get(1).complete(receipt(records), null); assertEquals(1, result.receipt.savedCount); assertEquals(3, result.count);
    }
    @Test public void detailedExpiryAndReplacementInvalidateAnUnadmittedRead() {
        Clock clock = new Clock(); CaptureController capture = new CaptureController(false, clock);
        Source source = new Source(); Archive archive = new Archive(); Result result = new Result();
        SystemDiagnosticRecovery recovery = new SystemDiagnosticRecovery(capture, archive);
        capture.startDetailed(1); recovery.recover(source, result); clock.time = 1; source.deliveries.get(0).complete(batch(), null);
        result.failure(SystemDiagnosticRecovery.Error.Kind.INACTIVE);
        assertTrue(capture.setBaselineEnabled(true)); recovery.recover(source, result); capture.startDetailed(); source.deliveries.get(1).complete(batch(), null);
        result.failure(SystemDiagnosticRecovery.Error.Kind.CAPTURE_CHANGED); assertTrue(archive.batches.isEmpty());
    }
}
