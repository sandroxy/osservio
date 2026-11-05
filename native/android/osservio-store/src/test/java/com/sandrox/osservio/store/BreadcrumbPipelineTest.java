package com.sandrox.osservio.store;

import com.sandrox.osservio.core.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Locale;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BreadcrumbPipelineTest {
    private static final class Clock implements LogClock {
        long now;
        @Override public long wallTimeMillis() { return 1000 + now; }
        @Override public long monotonicMillis() { return now; }
    }
    private static String decode(String input) { return input.equals("~") ? "" : new String(Base64.getDecoder().decode(input), StandardCharsets.UTF_8); }
    private static String enumName(String value) { return value.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT); }
    @Test public void sharedPrivacyLabelAndRetentionFixtures() throws Exception {
        for (String line : Files.readAllLines(Paths.get(System.getProperty("osservio.fixtures"), "breadcrumb-cases.tsv"), StandardCharsets.UTF_8)) {
            String[] row = line.split("\t");
            MemoryBreadcrumbStore store = new MemoryBreadcrumbStore();
            CaptureController capture = new CaptureController(true, LogClock.SYSTEM);
            BreadcrumbRecorder recorder = new BreadcrumbRecorder(store, capture, Integer.parseInt(row[4]), 20);
            String raw = decode(row[3]);
            BreadcrumbRecorder.Result result = recorder.record(BreadcrumbEvent.Kind.HOST_MARKER, row[1],
                row[2].equals("private") ? LogMessage.privateText(raw) : LogMessage.publicText(raw), null,
                BreadcrumbEvent.Retention.valueOf(enumName(row[8])));
            assertEquals(row[0], enumName(row[5]), result.name());
            if (row[6].equals("-")) { assertTrue(store.snapshot().events.isEmpty()); continue; }
            BreadcrumbEvent event = store.snapshot().events.get(0);
            assertEquals(row[0], decode(row[6]), event.message);
            assertEquals(Boolean.parseBoolean(row[7]), event.isTruncated);
            assertEquals(row[2].equals("private"), event.isPrivate);
            assertEquals(enumName(row[8]), event.retention.name());
        }
    }
    @Test public void baselineOffAndDetailedOrderingAreIndependentFromRetention() {
        MemoryBreadcrumbStore store = new MemoryBreadcrumbStore();
        CaptureController capture = new CaptureController();
        BreadcrumbRecorder recorder = new BreadcrumbRecorder(store, capture);
        MemoryLogStore logs = new MemoryLogStore(); LogRecorder log = new LogRecorder(logs, capture);
        assertEquals(BreadcrumbRecorder.Result.INACTIVE, recorder.record(BreadcrumbEvent.Kind.APP_ACTIVE, "app.active"));
        assertTrue(capture.setBaselineEnabled(true));
        assertEquals(BreadcrumbRecorder.Result.RECORDED, recorder.record(BreadcrumbEvent.Kind.APP_ACTIVE, "app.active", null, null, BreadcrumbEvent.Retention.RESTART_RELEVANT));
        assertEquals(LogRecorder.Result.INACTIVE, log.record(LogLevel.INFO, "app", LogMessage.publicText("detail")));
        capture.startDetailed();
        assertTrue(capture.status().baselineEnabled);
        assertTrue(capture.setBaselineEnabled(false)); assertFalse(capture.status().baselineEnabled);
        assertEquals(CaptureController.Mode.DETAILED, capture.status().mode);
        assertTrue(capture.setBaselineEnabled(true));
        log.record(LogLevel.INFO, "app", LogMessage.publicText("detail"));
        recorder.record(BreadcrumbEvent.Kind.HOST_MARKER, "host.ready");
        BreadcrumbEvent event = store.snapshot().events.get(1);
        assertEquals(2, event.sequence); assertEquals(logs.snapshot().events.get(0).sessionId, event.sessionId);
        assertTrue(capture.stopDetailed());
        assertEquals(BreadcrumbRecorder.Result.RECORDED, recorder.record(BreadcrumbEvent.Kind.APP_BACKGROUND, "app.background"));
        assertTrue(capture.stopAll());
        assertEquals(BreadcrumbRecorder.Result.INACTIVE, recorder.record(BreadcrumbEvent.Kind.MEMORY_WARNING, "memory.warning"));
        assertEquals(3, store.snapshot().events.size());
    }
    @Test public void rateWindowClockProvenanceAndReplacement() {
        Clock clock = new Clock(); CaptureController capture = new CaptureController(true, clock);
        MemoryBreadcrumbStore store = new MemoryBreadcrumbStore();
        BreadcrumbRecorder recorder = new BreadcrumbRecorder(store, capture, 1024, 1);
        assertEquals(BreadcrumbRecorder.Result.RECORDED, recorder.record(BreadcrumbEvent.Kind.MEMORY_TRIM, "memory.trim", null, 80L, BreadcrumbEvent.Retention.MEMORY_ONLY));
        assertEquals(BreadcrumbRecorder.Result.RATE_LIMITED, recorder.record(BreadcrumbEvent.Kind.HOST_MARKER, "host.ready"));
        clock.now = 1000;
        assertEquals(BreadcrumbRecorder.Result.RECORDED, recorder.record(BreadcrumbEvent.Kind.APP_ACTIVE, "app.active"));
        assertEquals(Long.valueOf(1000), store.snapshot().events.get(1).sessionElapsedMillis);
        assertEquals(2000, store.snapshot().events.get(1).timestampMillis);
        assertEquals(Long.valueOf(80), store.snapshot().events.get(0).value);
        capture.startDetailed();
        assertEquals(BreadcrumbRecorder.Result.RECORDED, recorder.record(BreadcrumbEvent.Kind.APP_ACTIVE, "app.active"));
        assertEquals(1, recorder.status().rateLimitedCount);
    }
    @Test public void storeBudgetsSnapshotsAndOversizedRejection() {
        MemoryBreadcrumbStore store = new MemoryBreadcrumbStore(2, 10);
        BreadcrumbRecorder recorder = new BreadcrumbRecorder(store, new CaptureController(true, LogClock.SYSTEM));
        assertEquals(BreadcrumbRecorder.Result.RECORDED, recorder.record(BreadcrumbEvent.Kind.HOST_MARKER, "a", LogMessage.publicText("1234"), null, BreadcrumbEvent.Retention.MEMORY_ONLY));
        BreadcrumbSnapshot old = store.snapshot();
        recorder.record(BreadcrumbEvent.Kind.HOST_MARKER, "b", LogMessage.publicText("5678"), null, BreadcrumbEvent.Retention.MEMORY_ONLY);
        assertEquals(BreadcrumbRecorder.Result.TOO_LARGE, recorder.record(BreadcrumbEvent.Kind.HOST_MARKER, "x", LogMessage.publicText("1234567890"), null, BreadcrumbEvent.Retention.MEMORY_ONLY));
        assertEquals(2, store.snapshot().events.size());
        recorder.record(BreadcrumbEvent.Kind.HOST_MARKER, "c", LogMessage.publicText("abcd"), null, BreadcrumbEvent.Retention.MEMORY_ONLY);
        assertEquals("b", store.snapshot().events.get(0).label); assertEquals(1, store.snapshot().evictedCount);
        assertEquals(1, old.events.size()); store.clear(); assertEquals(0, store.snapshot().retainedTextBytes);
        assertEquals(BreadcrumbRecorder.Result.RECORDED, recorder.record(BreadcrumbEvent.Kind.HOST_MARKER, "d"));
    }
    @Test public void sinkFailureAndReentryDoNotBlock() {
        CaptureController capture = new CaptureController(true, LogClock.SYSTEM);
        BreadcrumbRecorder busy = new BreadcrumbRecorder(event -> { assertNull(capture.status()); return BreadcrumbSink.WriteResult.BUSY; }, capture);
        assertEquals(BreadcrumbRecorder.Result.BUSY, busy.record(BreadcrumbEvent.Kind.APP_ACTIVE, "app.active"));
        BreadcrumbRecorder failed = new BreadcrumbRecorder(event -> { throw new IllegalStateException("synthetic sink failure"); }, capture);
        assertEquals(BreadcrumbRecorder.Result.SINK_FAILURE, failed.record(BreadcrumbEvent.Kind.APP_ACTIVE, "app.active"));
        assertThrows(IllegalArgumentException.class, () -> new BreadcrumbRecorder(new MemoryBreadcrumbStore(), capture, 8, 20));
    }
}
