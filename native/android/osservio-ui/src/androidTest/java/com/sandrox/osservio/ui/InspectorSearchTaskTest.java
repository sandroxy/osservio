package com.sandrox.osservio.ui;

import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public final class InspectorSearchTaskTest {
    @Test public void slowUiCoalescesProgressAndCancelledWorkCannotDeliver() {
        for (boolean cancel : new boolean[] {false, true}) {
            AtomicInteger deliveries = new AtomicInteger(); CountDownLatch produced = new CountDownLatch(1);
            InspectorRecordSearchTask[] task = new InspectorRecordSearchTask[1];
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                task[0] = InspectorRecordSearchTask.submitProgressive((stopped, progress) -> {
                    for (int i = 1; i <= 100; i++) {
                        InspectorRecordSearch.Result value = new InspectorRecordSearch.Result();
                        value.total = 100; value.scanned = i; value.complete = i == 100; progress.publish(value);
                    }
                    produced.countDown(); return null;
                }, result -> { assertTrue(result.complete); assertEquals(100, result.scanned); deliveries.incrementAndGet(); });
                assertNotNull(task[0]);
                try { assertTrue(produced.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
                if (cancel) task[0].cancel();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(cancel ? 0 : 1, deliveries.get()); task[0].cancel();
        }
    }
}
