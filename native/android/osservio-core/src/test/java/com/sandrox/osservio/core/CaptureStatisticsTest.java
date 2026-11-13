package com.sandrox.osservio.core;

import java.util.concurrent.CountDownLatch;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CaptureStatisticsTest {
    @Test public void inactiveInvalidLimitedAndSinkFailuresRemainDistinctAcrossSessions() {
        CaptureController controller = new CaptureController();
        LogRecorder recorder = new LogRecorder(event -> LogSink.WriteResult.STORED, controller, 4096, 1, 0);
        assertEquals(LogRecorder.Result.INACTIVE, recorder.record(LogLevel.INFO, "app", LogMessage.publicText("off")));
        recorder.start();
        assertEquals(LogRecorder.Result.INVALID_CATEGORY, recorder.record(LogLevel.INFO, "bad label", LogMessage.publicText("invalid")));
        assertEquals(LogRecorder.Result.RECORDED, recorder.record(LogLevel.INFO, "app", LogMessage.publicText("one")));
        assertEquals(LogRecorder.Result.RATE_LIMITED, recorder.record(LogLevel.INFO, "app", LogMessage.publicText("two")));
        CaptureStatistics.Snapshot before = controller.statistics.snapshot();
        assertTrue(recorder.stop()); recorder.start();
        LogRecorder broken = new LogRecorder(event -> { throw new IllegalStateException("test failure"); }, controller);
        assertEquals(LogRecorder.Result.SINK_FAILURE, broken.record(LogLevel.ERROR, "app", LogMessage.publicText("error")));
        CaptureStatistics.Snapshot after = controller.statistics.snapshot();
        for (CaptureStatistics.Outcome outcome : new CaptureStatistics.Outcome[] {CaptureStatistics.Outcome.ACCEPTED,
            CaptureStatistics.Outcome.INACTIVE, CaptureStatistics.Outcome.INVALID, CaptureStatistics.Outcome.RATE_LIMITED, CaptureStatistics.Outcome.SINK_FAILURE}) {
            assertEquals(1, after.count(CaptureStatistics.Source.LOGS, outcome));
        }
        assertEquals(0, before.count(CaptureStatistics.Source.LOGS, CaptureStatistics.Outcome.SINK_FAILURE));
        assertEquals(before.startedAtMillis, after.startedAtMillis); assertFalse(after.isIncomplete);
    }

    @Test public void busyRejectionDoesNotWaitForCaptureLock() {
        LogRecorder[] box = new LogRecorder[1];
        LogRecorder recorder = new LogRecorder(event -> {
            assertEquals(LogRecorder.Result.BUSY, box[0].record(LogLevel.INFO, "app", LogMessage.publicText("reentrant")));
            return LogSink.WriteResult.STORED;
        });
        box[0] = recorder; recorder.start();
        assertEquals(LogRecorder.Result.RECORDED, recorder.record(LogLevel.INFO, "app", LogMessage.publicText("outer")));
        assertEquals(1, recorder.captureController.statistics.snapshot().count(CaptureStatistics.Source.LOGS, CaptureStatistics.Outcome.BUSY));
        assertEquals(1, recorder.captureController.statistics.snapshot().count(CaptureStatistics.Source.LOGS, CaptureStatistics.Outcome.ACCEPTED));
    }

    @Test public void everyRecorderResultHasAnAccountingOutcome() {
        CaptureStatistics statistics = new CaptureStatistics(123);
        for (LogRecorder.Result result : LogRecorder.Result.values()) assertEquals(result, statistics.observe(result));
        for (BreadcrumbRecorder.Result result : BreadcrumbRecorder.Result.values()) assertEquals(result, statistics.observe(result));
        for (HTTPRecorder.Result result : HTTPRecorder.Result.values()) assertEquals(result, statistics.observe(result));
        for (WebSocketRecorder.Result result : WebSocketRecorder.Result.values()) assertEquals(result, statistics.observe(result));
        for (CaptureStatistics.Source source : CaptureStatistics.Source.values()) assertEquals(1, statistics.snapshot().count(source, CaptureStatistics.Outcome.ACCEPTED));
    }

    @Test public void concurrentAccountingIsBoundedAndDeclaresSkippedBookkeeping() throws Exception {
        CaptureStatistics statistics = new CaptureStatistics(123); CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[8];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                try { start.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); return; }
                for (int count = 0; count < 2000; count++) statistics.record(CaptureStatistics.Source.WEB_SOCKET, CaptureStatistics.Outcome.BUSY);
            }); threads[i].start();
        }
        start.countDown(); for (Thread thread : threads) thread.join(5000);
        for (Thread thread : threads) assertFalse(thread.isAlive());
        CaptureStatistics.Snapshot value = statistics.snapshot();
        long count = value.count(CaptureStatistics.Source.WEB_SOCKET, CaptureStatistics.Outcome.BUSY);
        assertTrue(count > 0 && count <= 16000); assertTrue(count == 16000 || value.isIncomplete);
        assertEquals(0, value.count(CaptureStatistics.Source.LOGS, CaptureStatistics.Outcome.BUSY));
    }
}
