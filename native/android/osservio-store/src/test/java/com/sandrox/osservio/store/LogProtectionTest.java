package com.sandrox.osservio.store;

import com.sandrox.osservio.core.*;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;
import static org.junit.Assert.*;

public final class LogProtectionTest {
    private static final class Clock implements LogClock {
        long now = 100;
        @Override public long monotonicMillis() { return now; }
        @Override public long wallTimeMillis() { return 1_700_000_000_000L; }
    }
    private static List<String> messages(MemoryLogStore store) {
        return store.snapshot().events.stream().map(event -> event.message).collect(Collectors.toList());
    }
    @Test public void errorReserveIsFiniteAndDoesNotAdmitOrdinaryFlood() {
        Clock clock = new Clock(); MemoryLogStore store = new MemoryLogStore();
        LogRecorder recorder = new LogRecorder(store, 4096, 2, clock); recorder.start();
        for (int i = 0; i < 2; i++) assertEquals(LogRecorder.Result.RECORDED, recorder.record(LogLevel.INFO, "app", LogMessage.publicText("ordinary")));
        assertEquals(LogRecorder.Result.RATE_LIMITED, recorder.record(LogLevel.INFO, "app", LogMessage.publicText("limited")));
        assertEquals(LogRecorder.Result.RECORDED, recorder.record(LogLevel.ERROR, "app", LogMessage.publicText("failure")));
        assertEquals(LogRecorder.Result.RATE_LIMITED, recorder.record(LogLevel.ERROR, "app", LogMessage.publicText("error flood")));
        clock.now += 1000;
        assertEquals(LogRecorder.Result.RECORDED, recorder.record(LogLevel.INFO, "app", LogMessage.publicText("new window")));
        LogRecorder disabled = new LogRecorder(store, new CaptureController(false, clock), 4096, 1, 0); disabled.start();
        assertEquals(LogRecorder.Result.RECORDED, disabled.record(LogLevel.INFO, "app", LogMessage.publicText("ordinary")));
        assertEquals(LogRecorder.Result.RATE_LIMITED, disabled.record(LogLevel.ERROR, "app", LogMessage.publicText("limited")));
    }
    @Test public void recentErrorsSurviveFloodButNewErrorsReplaceOldErrors() {
        Clock clock = new Clock(); MemoryLogStore store = new MemoryLogStore(3, 524288, 1, 30000, clock);
        LogRecorder recorder = new LogRecorder(store, 4096, 200, clock); recorder.start();
        assertEquals(LogRecorder.Result.RECORDED, recorder.record(LogLevel.ERROR, "app", LogMessage.publicText("failure1")));
        for (String text : new String[] {"a", "b", "c"}) assertEquals(LogRecorder.Result.RECORDED, recorder.record(LogLevel.INFO, "app", LogMessage.publicText(text)));
        assertEquals(Arrays.asList("failure1", "b", "c"), messages(store));
        assertEquals(LogRecorder.Result.RECORDED, recorder.record(LogLevel.ERROR, "app", LogMessage.publicText("failure2")));
        assertEquals(Arrays.asList("b", "c", "failure2"), messages(store)); assertEquals(2, store.snapshot().evictedCount);
    }
    @Test public void protectedCapacityRejectsAtomicallyAndExpires() {
        Clock clock = new Clock(); MemoryLogStore store = new MemoryLogStore(4, 10, 1, 30000, clock);
        LogRecorder recorder = new LogRecorder(store, 4096, 200, clock); recorder.start();
        assertEquals(LogRecorder.Result.RECORDED, recorder.record(LogLevel.ERROR, "a", LogMessage.publicText("1234567")));
        assertEquals(LogRecorder.Result.RECORDED, recorder.record(LogLevel.INFO, "a", LogMessage.publicText("x")));
        LogSnapshot before = store.snapshot();
        assertEquals(LogRecorder.Result.PROTECTED_CAPACITY, recorder.record(LogLevel.INFO, "a", LogMessage.publicText("12345")));
        assertEquals(before.revision, store.snapshot().revision); assertEquals(Arrays.asList("1234567", "x"), messages(store));
        clock.now += 30000;
        assertEquals(LogRecorder.Result.RECORDED, recorder.record(LogLevel.INFO, "a", LogMessage.publicText("12345")));
        assertEquals(Arrays.asList("x", "12345"), messages(store)); assertEquals(8, store.snapshot().retainedTextBytes); assertEquals(2, before.events.size());
    }
    @Test public void protectedPrefixSurvivesRepeatedEvictionsWithoutReordering() {
        Clock clock = new Clock(); MemoryLogStore store = new MemoryLogStore(7, 100, 2, 30000, clock);
        LogRecorder recorder = new LogRecorder(store, 4096, 1000, clock); recorder.start();
        for (int i = 0; i < 1000; i++) {
            clock.now += 10;
            assertEquals(LogRecorder.Result.RECORDED, recorder.record(i % 5 == 0 ? LogLevel.ERROR : LogLevel.INFO, "a", LogMessage.publicText(Integer.toString(i))));
            List<LogEvent> events = store.snapshot().events; assertTrue(events.size() <= 7);
            for (int j = 1; j < events.size(); j++) assertTrue(events.get(j - 1).sequence < events.get(j).sequence);
            if (i >= 5) { assertTrue(messages(store).contains(Integer.toString(i / 5 * 5))); assertTrue(messages(store).contains(Integer.toString(i / 5 * 5 - 5))); }
        }
    }
}
