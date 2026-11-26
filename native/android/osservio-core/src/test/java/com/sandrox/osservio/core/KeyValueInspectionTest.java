package com.sandrox.osservio.core;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class KeyValueInspectionTest {
    private static class Source implements KeyValueSource {
        final AtomicInteger reads = new AtomicInteger();
        @Override public boolean visitKeys(KeyVisitor visitor) { for (String key : Arrays.asList("b", "a", "a", "")) if (!visitor.visit(key)) return false; return true; }
        @Override public KeyValueInspection.Value read(String key) { reads.incrementAndGet(); return KeyValueInspection.Value.text(KeyValueInspection.Kind.STRING, "value"); }
    }
    private static KeyValueInspection inspection(KeyValueSource source) {
        return new KeyValueInspection(UUID.randomUUID(), Collections.singletonList(new KeyValueInspection.Registration(
            new KeyValueInspection.Domain("settings", "Settings", "Preferences", "account-A"), source)));
    }
    private static void await(CountDownLatch latch) throws InterruptedException { assertTrue(latch.await(3, TimeUnit.SECONDS)); }
    @Test public void keysAreSortedUniqueAndDoNotReadValues() throws Exception {
        Source source = new Source(); try (KeyValueInspection storage = inspection(source)) {
            CountDownLatch done = new CountDownLatch(1); AtomicReference<KeyValueInspection.Keys> result = new AtomicReference<>();
            storage.keys("settings", (value, error) -> { assertNull(error); result.set(value); done.countDown(); }); await(done);
            assertEquals(Arrays.asList("", "a", "b"), result.get().keys); assertFalse(result.get().limited); assertEquals(0, source.reads.get());
        }
    }
    @Test public void enumerationStopsAtCountBudget() throws Exception {
        AtomicInteger visited = new AtomicInteger();
        try (KeyValueInspection storage = inspection(new Source() {
            @Override public boolean visitKeys(KeyVisitor visitor) { for (int i = 0; i < 100_000; i++) { visited.incrementAndGet(); if (!visitor.visit("key" + i)) return false; } return true; }
        })) {
            CountDownLatch done = new CountDownLatch(1); AtomicReference<KeyValueInspection.Keys> result = new AtomicReference<>();
            storage.keys("settings", (value, error) -> { result.set(value); done.countDown(); }); await(done);
            assertEquals(2048, result.get().keys.size()); assertTrue(result.get().limited); assertEquals(2049, visited.get());
        }
    }
    @Test public void oversizedKeyIsNotRewrittenIntoAnotherKey() throws Exception {
        String huge = new String(new char[1025]).replace('\0', 'x');
        try (KeyValueInspection storage = inspection(new Source() {
            @Override public boolean visitKeys(KeyVisitor visitor) { return visitor.visit(huge); }
        })) {
            CountDownLatch done = new CountDownLatch(1);
            storage.keys("settings", (value, error) -> { assertTrue(value.keys.isEmpty()); assertTrue(value.limited); done.countDown(); }); await(done);
            AtomicReference<KeyValueInspection.Error> error = new AtomicReference<>(); storage.read("settings", huge, (value, failure) -> error.set(failure));
            assertEquals(KeyValueInspection.Error.INVALID_KEY, error.get());
        }
    }
    @Test public void valuesBoundUTF8WithoutSplittingScalarAndPreserveEmpty() {
        String text = new String(new char[65535]).replace('\0', 'a') + "🙂";
        KeyValueInspection.Value value = KeyValueInspection.Value.text(KeyValueInspection.Kind.STRING, text);
        assertTrue(value.truncated); assertEquals(65535, value.text.length());
        assertEquals(KeyValueInspection.State.AVAILABLE, KeyValueInspection.Value.text(KeyValueInspection.Kind.STRING, "").state);
    }
    @Test public void closeRejectsLateResultAndBusyDoesNotQueue() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger(); AtomicReference<KeyValueInspection.Error> first = new AtomicReference<>();
        KeyValueInspection storage = inspection(new KeyValueSource() {
            @Override public boolean visitKeys(KeyVisitor visitor) { return true; }
            @Override public KeyValueInspection.Value read(String key) throws Exception { reads.incrementAndGet(); entered.countDown(); await(release); return KeyValueInspection.Value.text(KeyValueInspection.Kind.STRING, "old secret"); }
        });
        storage.read("settings", "a", (value, error) -> { assertNull(value); first.set(error); done.countDown(); }); await(entered);
        AtomicReference<KeyValueInspection.Error> second = new AtomicReference<>(); storage.read("settings", "a", (value, error) -> second.set(error));
        assertEquals(KeyValueInspection.Error.BUSY, second.get()); storage.close(); release.countDown(); await(done);
        assertEquals(KeyValueInspection.Error.CLOSED, first.get()); assertEquals(1, reads.get());
        storage.keys("settings", (value, error) -> second.set(error)); assertEquals(KeyValueInspection.Error.CLOSED, second.get());
    }
    @Test public void cancellationStopsEnumerationWithoutDelivering() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), exited = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        try (KeyValueInspection storage = inspection(new KeyValueSource() {
            @Override public boolean visitKeys(KeyVisitor visitor) throws Exception { entered.countDown(); await(release); assertFalse(visitor.visit("a")); exited.countDown(); return false; }
            @Override public KeyValueInspection.Value read(String key) { throw new AssertionError(); }
        })) {
            KeyValueInspection.Request request = storage.keys("settings", (value, error) -> callbacks.incrementAndGet()); await(entered);
            request.close(); release.countDown(); await(exited); assertEquals(0, callbacks.get());
        }
    }
    @Test public void providerFailureAndUnknownDomainAreExplicit() throws Exception {
        try (KeyValueInspection storage = inspection(new Source() { @Override public KeyValueInspection.Value read(String key) { throw new IllegalStateException("provider failed"); } })) {
            CountDownLatch done = new CountDownLatch(1);
            storage.read("settings", "a", (value, error) -> { assertNull(value); assertEquals(KeyValueInspection.Error.UNAVAILABLE, error); done.countDown(); }); await(done);
            storage.keys("other", (value, error) -> { assertNull(value); assertEquals(KeyValueInspection.Error.UNKNOWN_DOMAIN, error); });
        }
    }
    @Test public void closeObservationIsOneShotCancellableAndReentrant() {
        KeyValueInspection storage = inspection(new Source()); AtomicInteger calls = new AtomicInteger();
        KeyValueInspection.Request cancelled = storage.observeClose(() -> fail("Cancelled observer")); cancelled.close();
        storage.observeClose(() -> { assertTrue(storage.isClosed()); calls.incrementAndGet(); storage.close(); });
        storage.close(); storage.close(); assertEquals(1, calls.get());
        storage.observeClose(calls::incrementAndGet).close(); assertEquals(2, calls.get());
    }
}
