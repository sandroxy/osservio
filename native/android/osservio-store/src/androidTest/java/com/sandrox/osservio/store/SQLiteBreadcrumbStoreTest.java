package com.sandrox.osservio.store;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.sandrox.osservio.core.BreadcrumbEvent;
import com.sandrox.osservio.core.BreadcrumbSink;
import com.sandrox.osservio.core.LogClock;
import com.sandrox.osservio.core.PersistenceStatus;
import com.sandrox.osservio.core.RetainedBreadcrumbs;
import com.sandrox.osservio.core.RetainedRun;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import static com.sandrox.osservio.store.PersistenceFixture.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SQLiteBreadcrumbStoreTest {
    private SQLiteBreadcrumbStore store(PersistenceFixture fixture) { return new SQLiteBreadcrumbStore(fixture.directory, new BreadcrumbPersistenceOptions(), LogClock.SYSTEM); }
    private static Result<Void> flush(SQLiteBreadcrumbStore store) throws InterruptedException {
        Result<Void> result = new Result<>(); store.flush(error -> result.complete(null, error)); return result.await();
    }
    private static Result<Void> close(SQLiteBreadcrumbStore store) throws InterruptedException {
        Result<Void> result = new Result<>(); store.close(error -> result.complete(null, error)); return result.await();
    }
    @Test public void committedPrivateEvidenceSurvivesLiveClearAndReopen() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            SQLiteBreadcrumbStore writer = store(fixture); flush(writer).success();
            writer.append(events(1, "live", BreadcrumbEvent.Retention.MEMORY_ONLY).get(0));
            BreadcrumbEvent secret = BreadcrumbEvent.restore(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1,
                1000, 0L, BreadcrumbEvent.Kind.HOST_MARKER, "private", "[private]", true, false, null, BreadcrumbEvent.Retention.RESTART_RELEVANT);
            assertEquals(BreadcrumbSink.WriteResult.STORED, writer.append(secret)); flush(writer).success();
            writer.clear(); assertTrue(writer.snapshot().events.isEmpty()); close(writer).success();
            expect(PersistenceStatus.Error.CLOSED, () -> writer.append(secret));
            SQLiteBreadcrumbStore reader = store(fixture);
            try {
                Result<RetainedBreadcrumbs> result = new Result<>(); reader.readRun(writer.currentRunId, result::complete);
                RetainedBreadcrumbs history = result.success(); assertNotNull(history);
                assertFalse(history.run.isCurrent); assertEquals(1, history.events.size()); assertEquals("[private]", history.events.get(0).message);
                assertTrue(reader.snapshot().events.isEmpty());
            } finally { close(reader).success(); }
        }
    }
    @Test public void eventAndQueryAdmissionRemainBoundedDuringSlowStorage() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            BreadcrumbPersistenceOptions options = new BreadcrumbPersistenceOptions(200, 65536, 4, 604800000, 4194304, 2, 64, 5000);
            SQLiteBreadcrumbStore store = new SQLiteBreadcrumbStore(fixture.directory, options, LogClock.SYSTEM); flush(store).success();
            CountDownLatch entered = new CountDownLatch(1), gate = new CountDownLatch(1);
            Result<List<RetainedRun>> held = new Result<>();
            store.readRuns((runs, error) -> {
                held.complete(runs, error); entered.countDown();
                try { gate.await(10, TimeUnit.SECONDS); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(10, TimeUnit.SECONDS)); held.success();
            try {
                for (BreadcrumbEvent event : events(4, "bounded")) assertEquals(BreadcrumbSink.WriteResult.STORED, store.append(event));
                assertEquals(4, store.snapshot().events.size()); assertEquals(2, store.persistenceStatus().queuedEvents); assertEquals(2, store.persistenceStatus().discardedEvents);
                List<Result<List<RetainedRun>>> accepted = new ArrayList<>();
                for (int i = 0; i < 4; i++) { Result<List<RetainedRun>> result = new Result<>(); accepted.add(result); store.readRuns(result::complete); }
                Result<List<RetainedRun>> busy = new Result<>(); store.readRuns(busy::complete); assertEquals(PersistenceStatus.Error.BUSY, busy.await().error);
                gate.countDown(); for (Result<List<RetainedRun>> result : accepted) result.success();
                flush(store).success(); assertEquals(2, store.persistenceStatus().committedEvents); assertEquals(0, store.persistenceStatus().queuedEvents);
            } finally { gate.countDown(); close(store).success(); }
        }
    }
    @Test public void exclusiveWriterFailureKeepsLiveHistoryAndReleasesTheLease() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            SQLiteBreadcrumbStore owner = store(fixture); flush(owner).success();
            SQLiteBreadcrumbStore contender = store(fixture);
            assertEquals(PersistenceStatus.Error.BUSY, flush(contender).error);
            assertEquals(BreadcrumbSink.WriteResult.STORED, contender.append(events(1, "local").get(0)));
            assertEquals(1, contender.snapshot().events.size()); assertEquals(1, contender.persistenceStatus().discardedEvents);
            close(contender); close(owner).success();
            SQLiteBreadcrumbStore replacement = store(fixture); flush(replacement).success(); close(replacement).success();
        }
    }
    @Test public void corruptFileIsPreservedAndLiveRecordingContinues() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            byte[] invalid = "synthetic corrupt evidence".getBytes(StandardCharsets.UTF_8); write(fixture.file, invalid);
            SQLiteBreadcrumbStore store = store(fixture);
            assertEquals(PersistenceStatus.Error.CORRUPT, flush(store).error);
            assertEquals(BreadcrumbSink.WriteResult.STORED, store.append(events(1, "local").get(0)));
            assertEquals(1, store.snapshot().events.size()); assertEquals(PersistenceStatus.State.FAILED, store.persistenceStatus().state);
            assertArrayEquals(invalid, read(fixture.file)); close(store);
        }
    }
    @Test public void closeDrainsAdmittedQueriesAndWritesBeforeReturningOwnership() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            SQLiteBreadcrumbStore store = store(fixture); flush(store).success();
            assertEquals(BreadcrumbSink.WriteResult.STORED, store.append(events(1, "retained").get(0)));
            Result<Void> flushed = new Result<>(); store.flush(error -> flushed.complete(null, error));
            Result<List<RetainedRun>> query = new Result<>(); store.readRuns(query::complete);
            close(store).success(); flushed.success(); assertEquals(1, query.success().size());
            Result<List<RetainedRun>> closed = new Result<>(); store.readRuns(closed::complete); assertEquals(PersistenceStatus.Error.CLOSED, closed.await().error);
            assertEquals(PersistenceStatus.State.CLOSED, store.persistenceStatus().state);
            close(store).success();
        }
    }
    @Test public void explicitDeletionDrainsPendingWritesAndKeepsLiveHistory() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            SQLiteBreadcrumbStore previous = store(fixture); flush(previous).success();
            assertEquals(BreadcrumbSink.WriteResult.STORED, previous.append(events(1, "previous").get(0))); close(previous).success();
            SQLiteBreadcrumbStore store = store(fixture); flush(store).success();
            assertEquals(BreadcrumbSink.WriteResult.STORED, store.append(events(1, "current").get(0)));
            Result<Void> deleted = new Result<>(); store.deleteRun(previous.currentRunId, error -> deleted.complete(null, error)); deleted.success();
            Result<List<RetainedRun>> remaining = new Result<>(); store.readRuns(remaining::complete);
            assertEquals(1, remaining.success().size()); assertEquals(store.currentRunId, remaining.value.get(0).id);
            assertEquals(1, store.snapshot().events.size());
            assertEquals(BreadcrumbSink.WriteResult.STORED, store.append(events(1, "pending").get(0)));
            Result<Void> cleared = new Result<>(); store.deleteAllRuns(error -> cleared.complete(null, error)); cleared.success();
            Result<List<RetainedRun>> empty = new Result<>(); store.readRuns(empty::complete); assertTrue(empty.success().isEmpty());
            assertEquals(2, store.snapshot().events.size());
            assertEquals(BreadcrumbSink.WriteResult.STORED, store.append(events(1, "after.delete").get(0))); flush(store).success();
            Result<RetainedBreadcrumbs> recreated = new Result<>(); store.readRun(store.currentRunId, recreated::complete);
            assertEquals(1, recreated.success().events.size()); assertEquals("after.delete", recreated.value.events.get(0).message);
            close(store).success();
        }
    }
}
