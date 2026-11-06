package com.sandrox.osservio.runtime;

import com.sandrox.osservio.core.SystemDiagnostic;
import com.sandrox.osservio.core.SystemDiagnosticBatch;
import com.sandrox.osservio.core.SystemDiagnosticProvider;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SystemDiagnosticQueryTest {
    @Test public void busyAdmissionCloseAndCompletionReentry() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SystemDiagnostics query = new SystemDiagnostics(() -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test did not release source"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            return new SystemDiagnosticBatch(SystemDiagnostic.Source.ANDROID_EXIT_INFO, 42, Collections.emptyList(), 0, 0, false);
        }, Executors.newSingleThreadExecutor());
        try {
            query.read((batch, error) -> {
                try {
                    assertNull(error); assertEquals(42, batch.observedAtMillis);
                    query.read((ignored, rejected) -> assertEquals(SystemDiagnosticProvider.Error.CLOSED, rejected));
                } catch (Throwable assertion) { failure.set(assertion); }
                finally { finished.countDown(); }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            query.read((batch, error) -> { assertNull(batch); assertEquals(SystemDiagnosticProvider.Error.BUSY, error); });
            query.close(); query.close(); release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS)); assertNull(failure.get());
        } finally { release.countDown(); query.close(); }
    }
    @Test public void unsupportedAndSourceFailureStayExplicitAndRetryable() throws Exception {
        try (SystemDiagnostics unsupported = new SystemDiagnostics(null, Executors.newSingleThreadExecutor())) {
            unsupported.read((batch, error) -> { assertNull(batch); assertEquals(SystemDiagnosticProvider.Error.UNSUPPORTED, error); });
        }
        CountDownLatch twice = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (SystemDiagnostics query = new SystemDiagnostics(() -> { throw new IllegalStateException("synthetic-secret"); }, Executors.newSingleThreadExecutor())) {
            query.read((batch, error) -> {
                try {
                    assertNull(batch); assertEquals(SystemDiagnosticProvider.Error.UNAVAILABLE, error);
                    query.read((retry, retryError) -> {
                        try { assertNull(retry); assertEquals(SystemDiagnosticProvider.Error.UNAVAILABLE, retryError); }
                        catch (Throwable assertion) { failure.set(assertion); }
                        finally { twice.countDown(); }
                    });
                } catch (Throwable assertion) { failure.set(assertion); twice.countDown(); }
            });
            assertTrue(twice.await(5, TimeUnit.SECONDS)); assertNull(failure.get());
        }
    }
}
