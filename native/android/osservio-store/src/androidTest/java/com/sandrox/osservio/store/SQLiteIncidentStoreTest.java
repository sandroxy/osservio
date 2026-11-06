package com.sandrox.osservio.store;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.sandrox.osservio.core.IncidentSnapshot;
import com.sandrox.osservio.core.IncidentSummary;
import com.sandrox.osservio.core.LogClock;
import com.sandrox.osservio.core.LogMessage;
import com.sandrox.osservio.core.PersistenceStatus;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import static com.sandrox.osservio.store.PersistenceFixture.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SQLiteIncidentStoreTest {
    private static SQLiteIncidentStore store(PersistenceFixture fixture) { return new SQLiteIncidentStore(fixture.directory, new IncidentPersistenceOptions(), LogClock.SYSTEM); }
    private static Result<Void> close(SQLiteIncidentStore store) throws InterruptedException {
        Result<Void> result = new Result<>(); store.close(error -> result.complete(null, error)); return result.await();
    }
    private static Result<Void> save(SQLiteIncidentStore store, IncidentSnapshot snapshot) throws InterruptedException {
        Result<Void> result = new Result<>(); store.save(snapshot, error -> result.complete(null, error)); return result.await();
    }
    private static Result<List<IncidentSummary>> summaries(SQLiteIncidentStore store) throws InterruptedException {
        Result<List<IncidentSummary>> result = new Result<>(); store.readIncidents(result::complete); return result.await();
    }
    @Test public void saveCloseReopenAndDeletionPreserveIdentityAndPrivacy() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            IncidentSnapshot snapshot = IncidentDetailFixture.snapshot();
            SQLiteIncidentStore store = store(fixture); save(store, snapshot).success();
            assertEquals(snapshot.id, summaries(store).success().get(0).id); assertEquals(1, store.persistenceStatus().committedEvents);
            close(store).success();
            SQLiteIncidentStore reopened = store(fixture);
            try {
                Result<IncidentSnapshot> restored = new Result<>(); reopened.readIncident(snapshot.id, restored::complete);
                IncidentSnapshot value = restored.success();
                assertEquals("[private]", value.note.text);
                assertEquals(IncidentDetailFixture.BODY, value.http.payloads.get(IncidentDetailFixture.id(10) + ".response").text);
                assertEquals(2, value.logs.events.size()); assertEquals(5, value.webSockets.events.size());
                Result<Void> deleted = new Result<>(); reopened.deleteIncident(snapshot.id, error -> deleted.complete(null, error)); deleted.success();
                Result<IncidentSnapshot> missing = new Result<>(); reopened.readIncident(snapshot.id, missing::complete); assertNull(missing.success());
                save(reopened, snapshot).success();
                Result<Void> cleared = new Result<>(); reopened.deleteAllIncidents(error -> cleared.complete(null, error)); cleared.success();
                assertTrue(summaries(reopened).success().isEmpty());
            } finally { close(reopened).success(); }
        }
    }
    @Test public void boundedAdmissionAndClosePreserveAcceptedWork() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            SQLiteIncidentStore store = new SQLiteIncidentStore(fixture.directory, new IncidentPersistenceOptions(10, 604800000, 131072, 16777216, 64), LogClock.SYSTEM);
            summaries(store).success();
            CountDownLatch entered = new CountDownLatch(1), gate = new CountDownLatch(1);
            store.readIncidents((values, error) -> {
                entered.countDown(); try { gate.await(10, TimeUnit.SECONDS); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            try {
                IncidentSnapshot first = IncidentSnapshot.capture(LogMessage.publicText("12345678901234567890123456789012"), null, null);
                IncidentSnapshot second = IncidentSnapshot.capture(LogMessage.publicText("12345678901234567890123456789012"), null, null);
                Result<Void> savedFirst = new Result<>(), savedSecond = new Result<>();
                store.save(first, error -> savedFirst.complete(null, error)); store.save(second, error -> savedSecond.complete(null, error));
                assertEquals(PersistenceStatus.Error.BUSY, save(store, first).error);
                assertEquals(2, store.persistenceStatus().queuedEvents); assertEquals(64, store.persistenceStatus().queuedTextBytes);
                Result<List<IncidentSummary>> readFirst = new Result<>(), readSecond = new Result<>();
                store.readIncidents(readFirst::complete); store.readIncidents(readSecond::complete);
                assertEquals(PersistenceStatus.Error.BUSY, summaries(store).error);
                Result<Void> closed = new Result<>(); store.close(error -> closed.complete(null, error));
                gate.countDown(); savedFirst.success(); savedSecond.success();
                assertEquals(2, readFirst.success().size()); assertEquals(2, readSecond.success().size()); closed.success();
                assertEquals(PersistenceStatus.State.CLOSED, store.persistenceStatus().state);
                assertEquals(0, store.persistenceStatus().queuedEvents); assertEquals(2, store.persistenceStatus().committedEvents);
                assertEquals(PersistenceStatus.Error.CLOSED, save(store, first).error);
            } finally { gate.countDown(); close(store).success(); }
        }
    }
    @Test public void duplicateOwnerAndCorruptFileFailWithoutReset() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            SQLiteIncidentStore owner = store(fixture); summaries(owner).success();
            SQLiteIncidentStore contender = store(fixture);
            assertEquals(PersistenceStatus.Error.BUSY, summaries(contender).error);
            assertEquals(PersistenceStatus.State.FAILED, contender.persistenceStatus().state); close(contender); close(owner).success();
            File file = new File(fixture.directory, "incidents.sqlite");
            byte[] damaged = "synthetic damaged archive".getBytes(StandardCharsets.UTF_8); write(file, damaged);
            SQLiteIncidentStore reader = store(fixture);
            assertEquals(PersistenceStatus.Error.CORRUPT, summaries(reader).error);
            assertArrayEquals(damaged, read(file)); close(reader);
        }
    }
    @Test public void rejectedDuplicateKeepsArchiveUsableAndCallbacksCanReenter() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            SQLiteIncidentStore store = store(fixture);
            try {
                IncidentSnapshot snapshot = IncidentSnapshot.capture(null, null, null);
                save(store, snapshot).success(); assertEquals(PersistenceStatus.Error.INVALID_RECORD, save(store, snapshot).error);
                assertEquals(PersistenceStatus.State.READY, store.persistenceStatus().state);
                Result<IncidentSnapshot> reentered = new Result<>();
                store.readIncidents((values, error) -> {
                    if (error == null) store.readIncident(snapshot.id, reentered::complete);
                    else reentered.complete(null, error);
                });
                assertEquals(snapshot.id, reentered.success().id);
            } finally { close(store).success(); }
        }
    }
}
