package com.sandrox.osservio.store;

import com.sandrox.osservio.core.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public final class LogPipelineTest {
    private static final class Clock implements LogClock {
        long now = 100L;
        @Override public long monotonicMillis() { return now; }
        @Override public long wallTimeMillis() { return 1_700_000_000_000L; }
    }

    @Test public void sharedPrivacyAndUnicodeCases() throws Exception {
        List<String> lines = Files.readAllLines(Paths.get(System.getProperty("osservio.fixtures"), "log-text-cases.tsv"), StandardCharsets.UTF_8);
        for (String line : lines) {
            String[] columns = line.split("\t", -1);
            assertEquals(6, columns.length);
            String input = new String(Base64.getDecoder().decode(columns[3]), StandardCharsets.UTF_8);
            String expected = new String(Base64.getDecoder().decode(columns[4]), StandardCharsets.UTF_8);
            MemoryLogStore store = new MemoryLogStore();
            LogRecorder recorder = new LogRecorder(store, Integer.parseInt(columns[2]), 200, LogClock.SYSTEM);
            recorder.start();
            boolean privacy = columns[1].equals("private");
            assertEquals(columns[0], LogRecorder.Result.RECORDED, recorder.record(LogLevel.INFO, "app",
                privacy ? LogMessage.privateText(input) : LogMessage.publicText(input)));
            LogEvent event = store.snapshot().events.get(0);
            assertEquals(columns[0], expected, event.message);
            assertEquals(columns[0], privacy, event.isPrivate);
            assertEquals(columns[0], Boolean.parseBoolean(columns[5]), event.isTruncated);
            assertEquals(columns[0], expected.getBytes(StandardCharsets.UTF_8).length + 3, event.textByteCount);
        }
    }

    @Test public void captureLifetimeAndHistoryAreIndependent() {
        Clock clock = new Clock();
        MemoryLogStore store = new MemoryLogStore();
        LogRecorder recorder = new LogRecorder(store, 4096, 200, clock);
        assertEquals(LogRecorder.Result.INACTIVE, recorder.record(LogLevel.INFO, "app", LogMessage.publicText("before")));
        recorder.start(1000L);
        assertEquals(LogRecorder.Result.RECORDED, recorder.record(LogLevel.INFO, "app", LogMessage.publicText("first")));
        LogEvent first = store.snapshot().events.get(0);
        clock.now = 1100L;
        assertEquals(LogRecorder.Result.INACTIVE, recorder.record(LogLevel.INFO, "app", LogMessage.publicText("expired")));
        assertFalse(recorder.status().isRecording);
        assertEquals(1, store.snapshot().events.size());
        recorder.start();
        assertEquals(LogRecorder.Result.RECORDED, recorder.record(LogLevel.INFO, "app", LogMessage.publicText("second")));
        LogEvent second = store.snapshot().events.get(1);
        assertNotEquals(first.sessionId, second.sessionId);
        assertEquals(1L, second.sequence);
        assertEquals(1_700_000_000_000L, second.timestampMillis);
        store.clear();
        assertTrue(recorder.status().isRecording);
        assertEquals(LogRecorder.Result.RECORDED, recorder.record(LogLevel.ERROR, "app", LogMessage.privateText("secret")));
        assertTrue(recorder.stop());
        assertEquals(1, store.snapshot().events.size());
    }

    @Test public void countEvictionAndSnapshotIsolation() {
        MemoryLogStore store = new MemoryLogStore(2, 524288);
        LogRecorder recorder = new LogRecorder(store);
        recorder.start();
        for (String value : new String[] {"one", "two"}) {
            assertEquals(LogRecorder.Result.RECORDED, recorder.record(LogLevel.DEBUG, "app", LogMessage.publicText(value)));
        }
        LogSnapshot before = store.snapshot();
        assertEquals(LogRecorder.Result.RECORDED, recorder.record(LogLevel.DEBUG, "app", LogMessage.publicText("three")));
        LogSnapshot after = store.snapshot();
        assertEquals(2L, after.events.get(0).sequence);
        assertEquals(3L, after.events.get(1).sequence);
        assertEquals(1L, after.evictedCount);
        assertEquals(after.events.stream().mapToInt(event -> event.textByteCount).sum(), after.retainedTextBytes);
        store.clear();
        assertEquals("one", before.events.get(0).message);
        assertEquals("two", after.events.get(0).message);
        assertEquals(0, store.snapshot().retainedTextBytes);
        assertEquals(1L, store.snapshot().evictedCount);
        assertThrows(UnsupportedOperationException.class, () -> after.events.clear());
    }

    @Test public void byteBudgetAndOversizedRejectionPreserveHistory() {
        MemoryLogStore store = new MemoryLogStore(10, 12);
        LogRecorder recorder = new LogRecorder(store, 9, 200, LogClock.SYSTEM);
        recorder.start();
        for (String value : new String[] {"1111", "2222"}) {
            assertEquals(LogRecorder.Result.RECORDED, recorder.record(LogLevel.INFO, "app", LogMessage.publicText(value)));
        }
        assertEquals("2222", store.snapshot().events.get(0).message);
        assertEquals(LogRecorder.Result.TOO_LARGE, recorder.record(LogLevel.INFO, "oversize", LogMessage.publicText("123456789")));
        assertEquals(1, store.snapshot().events.size());
        assertEquals("2222", store.snapshot().events.get(0).message);
        assertEquals(1L, store.snapshot().evictedCount);
    }

    @Test public void rateLimitBeforeStorageAndWindowReset() {
        Clock clock = new Clock();
        MemoryLogStore store = new MemoryLogStore();
        LogRecorder recorder = new LogRecorder(store, 4096, 2, clock);
        recorder.start(3000L);
        for (int i = 0; i < 2; i++) assertEquals(LogRecorder.Result.RECORDED, recorder.record(LogLevel.INFO, "app", LogMessage.publicText("ok")));
        clock.now = 1099L;
        assertEquals(LogRecorder.Result.RATE_LIMITED, recorder.record(LogLevel.INFO, "app", LogMessage.publicText("limited")));
        clock.now = 1100L;
        assertEquals(LogRecorder.Result.RECORDED, recorder.record(LogLevel.INFO, "app", LogMessage.publicText("next")));
        assertEquals(1L, recorder.status().rateLimitedCount);
        assertEquals(3, store.snapshot().events.size());
    }

    @Test public void invalidConfigurationAndInput() {
        MemoryLogStore store = new MemoryLogStore();
        assertThrows(IllegalArgumentException.class, () -> new MemoryLogStore(0, 100));
        assertThrows(IllegalArgumentException.class, () -> new MemoryLogStore(10, 0));
        assertThrows(IllegalArgumentException.class, () -> new LogRecorder(store, 8, 200, LogClock.SYSTEM));
        assertThrows(IllegalArgumentException.class, () -> new LogRecorder(store, 9, 0, LogClock.SYSTEM));
        LogRecorder recorder = new LogRecorder(store);
        assertThrows(IllegalArgumentException.class, () -> recorder.start(0));
        assertThrows(IllegalArgumentException.class, () -> recorder.start(86_400_001L));
        recorder.start();
        for (String category : new String[] {"", "用户", "user@email", new String(new char[65]).replace('\0', 'a')}) {
            assertEquals(LogRecorder.Result.INVALID_CATEGORY, recorder.record(LogLevel.INFO, category, LogMessage.publicText("value")));
        }
        assertEquals(LogRecorder.Result.INVALID_MESSAGE, recorder.record(LogLevel.INFO, "app", LogMessage.publicText("\uD800")));
        assertEquals(LogRecorder.Result.INVALID_MESSAGE, recorder.record(LogLevel.INFO, "app", null));
        assertEquals(LogRecorder.Result.INVALID_LEVEL, recorder.record(null, "app", LogMessage.publicText("value")));
        assertEquals(0, store.snapshot().events.size());
    }

    @Test public void contentionRejectsWithoutWaitingOrChangingSession() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<LogRecorder.Result> first = new AtomicReference<>();
        LogRecorder recorder = new LogRecorder(event -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            return LogSink.WriteResult.STORED;
        });
        recorder.start();
        Thread worker = new Thread(() -> first.set(recorder.record(LogLevel.INFO, "app", LogMessage.publicText("first"))));
        worker.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(LogRecorder.Result.BUSY, recorder.record(LogLevel.INFO, "app", LogMessage.publicText("second")));
            assertNull(recorder.status());
            assertFalse(recorder.stop());
            assertThrows(IllegalStateException.class, recorder::start);
        } finally { release.countDown(); worker.join(2000L); }
        assertFalse(worker.isAlive());
        assertEquals(LogRecorder.Result.RECORDED, first.get());
        assertTrue(recorder.status().isRecording);
    }

    @Test public void reentryAndSinkFailuresAreExplicit() {
        AtomicReference<LogRecorder> reference = new AtomicReference<>();
        LogRecorder recorder = new LogRecorder(event -> {
            assertEquals(LogRecorder.Result.BUSY, reference.get().record(LogLevel.INFO, "app", LogMessage.publicText("nested")));
            assertFalse(reference.get().stop());
            throw new IllegalStateException("storage failure");
        });
        reference.set(recorder);
        recorder.start();
        assertEquals(LogRecorder.Result.SINK_FAILURE, recorder.record(LogLevel.INFO, "app", LogMessage.privateText("secret")));
    }
}
