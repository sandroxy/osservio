package com.sandrox.osservio.store;

import com.sandrox.osservio.core.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CaptureControllerTest {
    private static final class Clock implements LogClock {
        long now = 100L;
        @Override public long monotonicMillis() { return now; }
        @Override public long wallTimeMillis() { return 1_700_000_000_000L; }
    }

    @Test public void sharedLifecycleFixtures() throws Exception {
        List<String> fixture = Files.readAllLines(Paths.get(System.getProperty("osservio.fixtures"), "capture-lifecycle.tsv"), StandardCharsets.UTF_8);
        Clock clock = new Clock();
        CaptureController capture = new CaptureController(false, clock);
        String previousId = null;
        for (String row : fixture) {
            String[] fields = row.split("\t");
            assertEquals(7, fields.length);
            clock.now = Long.parseLong(fields[1]);
            switch (fields[2]) {
                case "status": break;
                case "baseline": assertTrue(fields[0], capture.setBaselineEnabled(Boolean.parseBoolean(fields[3]))); break;
                case "detailed": capture.startDetailed(Long.parseLong(fields[3])); break;
                case "stop_detailed": assertTrue(fields[0], capture.stopDetailed()); break;
                case "stop_all": assertTrue(fields[0], capture.stopAll()); break;
                default: fail("Unknown fixture operation: " + fields[2]);
            }
            CaptureController.Status status = capture.status();
            assertEquals(fields[0], fields[4], status.mode.name().toLowerCase(Locale.ROOT));
            assertEquals(fields[0], Long.parseLong(fields[5]), status.remainingMillis);
            assertEquals(fields[0], fields[6].equals("changed"), !Objects.equals(status.sessionId, previousId));
            previousId = status.sessionId;
        }
    }

    @Test public void baselineNeverAdmitsDetailedLogs() {
        Clock clock = new Clock();
        CaptureController capture = new CaptureController(true, clock);
        MemoryLogStore store = new MemoryLogStore();
        LogRecorder logger = new LogRecorder(store, capture);
        assertEquals(LogRecorder.Result.INACTIVE, logger.record(LogLevel.INFO, "app", LogMessage.publicText("hidden")));
        assertFalse(logger.status().isRecording);
        logger.start(10L);
        assertEquals(LogRecorder.Result.RECORDED, logger.record(LogLevel.INFO, "app", LogMessage.publicText("detail")));
        clock.now += 10;
        assertEquals(LogRecorder.Result.INACTIVE, logger.record(LogLevel.INFO, "app", LogMessage.publicText("after expiry")));
        assertEquals(CaptureController.Mode.BASELINE, capture.status().mode);
        assertEquals("detail", store.snapshot().events.get(0).message);
        assertTrue(capture.stopAll());
        assertEquals(1, store.snapshot().events.size());
    }

    @Test public void sharedSessionOrdersRecordersAndResetsRateWindows() {
        CaptureController capture = new CaptureController(false, new Clock());
        MemoryLogStore store = new MemoryLogStore();
        LogRecorder first = new LogRecorder(store, capture, 4096, 1);
        LogRecorder second = new LogRecorder(store, capture, 4096, 1);
        capture.startDetailed();
        assertEquals(LogRecorder.Result.RECORDED, first.record(LogLevel.INFO, "first", LogMessage.publicText("one")));
        assertEquals(LogRecorder.Result.RECORDED, second.record(LogLevel.INFO, "second", LogMessage.publicText("two")));
        assertEquals(LogRecorder.Result.RATE_LIMITED, first.record(LogLevel.INFO, "first", LogMessage.publicText("limited")));
        LogSnapshot before = store.snapshot();
        assertEquals(before.events.get(0).sessionId, before.events.get(1).sessionId);
        assertEquals(1L, before.events.get(0).sequence);
        assertEquals(2L, before.events.get(1).sequence);
        second.start();
        assertEquals(LogRecorder.Result.RECORDED, first.record(LogLevel.INFO, "first", LogMessage.publicText("new one")));
        assertEquals(LogRecorder.Result.RECORDED, second.record(LogLevel.INFO, "second", LogMessage.publicText("new two")));
        LogSnapshot after = store.snapshot();
        assertNotEquals(before.events.get(0).sessionId, after.events.get(3).sessionId);
        assertEquals(1L, after.events.get(2).sequence);
        assertEquals(2L, after.events.get(3).sequence);
        assertEquals(1L, first.status().rateLimitedCount);
    }

    @Test public void sharedContentionRejectsControlWithoutChangingHistory() throws Exception {
        CaptureController capture = new CaptureController(true, LogClock.SYSTEM);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<LogRecorder.Result> result = new AtomicReference<>();
        LogRecorder first = new LogRecorder(event -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            return LogSink.WriteResult.STORED;
        }, capture);
        LogRecorder second = new LogRecorder(new MemoryLogStore(), capture);
        capture.startDetailed();
        Thread worker = new Thread(() -> result.set(first.record(LogLevel.INFO, "app", LogMessage.privateText("secret"))));
        worker.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(LogRecorder.Result.BUSY, second.record(LogLevel.INFO, "app", LogMessage.publicText("busy")));
            assertNull(capture.status());
            assertFalse(capture.stopAll());
            assertFalse(capture.setBaselineEnabled(false));
        } finally { release.countDown(); worker.join(2000L); }
        assertFalse(worker.isAlive());
        assertEquals(LogRecorder.Result.RECORDED, result.get());
        assertTrue(capture.stopDetailed());
        assertEquals(CaptureController.Mode.BASELINE, capture.status().mode);
    }

    @Test public void invalidDurationPreservesBaselineAndIdentity() {
        Clock clock = new Clock();
        CaptureController capture = new CaptureController(true, clock);
        String before = capture.status().sessionId;
        for (long duration : new long[] {0L, 86_400_001L}) {
            assertThrows(IllegalArgumentException.class, () -> capture.startDetailed(duration));
        }
        clock.now = Long.MAX_VALUE;
        assertThrows(IllegalArgumentException.class, () -> capture.startDetailed(1));
        assertEquals(CaptureController.Mode.BASELINE, capture.status().mode);
        assertEquals(before, capture.status().sessionId);
    }

    @Test public void negativeMonotonicOriginsCanExpireAtZero() {
        Clock clock = new Clock();
        clock.now = -10;
        CaptureController capture = new CaptureController(false, clock);
        capture.startDetailed(10);
        assertEquals(CaptureController.Mode.DETAILED, capture.status().mode);
        assertEquals(10L, capture.status().remainingMillis);
        clock.now = 0;
        assertEquals(CaptureController.Mode.OFF, capture.status().mode);
    }

    @Test public void delayedFirstLogPreservesSessionRateWindowAnchor() {
        Clock clock = new Clock();
        CaptureController capture = new CaptureController(false, clock);
        LogRecorder logger = new LogRecorder(new MemoryLogStore(), capture, 4096, 1);
        capture.startDetailed();
        clock.now = 1_000;
        assertEquals(LogRecorder.Result.RECORDED, logger.record(LogLevel.INFO, "app", LogMessage.publicText("first")));
        clock.now = 1_100;
        assertEquals(LogRecorder.Result.RECORDED, logger.record(LogLevel.INFO, "app", LogMessage.publicText("next window")));
        assertEquals(LogRecorder.Result.RATE_LIMITED, logger.record(LogLevel.INFO, "app", LogMessage.publicText("limited")));
    }
}
