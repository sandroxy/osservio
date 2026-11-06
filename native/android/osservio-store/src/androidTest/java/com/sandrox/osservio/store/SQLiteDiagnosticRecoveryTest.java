package com.sandrox.osservio.store;

import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.sandrox.osservio.core.DiagnosticRecoveryReceipt;
import com.sandrox.osservio.core.IncidentSummary;
import com.sandrox.osservio.core.LogClock;
import com.sandrox.osservio.core.PersistenceStatus;
import com.sandrox.osservio.core.SystemDiagnostic;
import com.sandrox.osservio.core.SystemDiagnosticBatch;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import static com.sandrox.osservio.store.PersistenceFixture.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SQLiteDiagnosticRecoveryTest {
    private static final LogClock CLOCK = new LogClock() {
        @Override public long monotonicMillis() { return 0; }
        @Override public long wallTimeMillis() { return 1000000; }
    };
    private static SystemDiagnosticBatch batch() {
        char[] text = new char[32]; Arrays.fill(text, 'x');
        SystemDiagnostic diagnostic = SystemDiagnostic.metricKit(999000, 1000000, SystemDiagnostic.Kind.CRASH,
            Collections.singletonMap(SystemDiagnostic.MetadataKey.APP_VERSION, SystemDiagnostic.Text.capturePublic(new String(text))), null, null, null, null);
        return new SystemDiagnosticBatch(SystemDiagnostic.Source.METRIC_KIT, 42, Arrays.asList(diagnostic, diagnostic), 0, 0, false);
    }
    private static Result<Void> close(SQLiteIncidentStore store) throws InterruptedException {
        Result<Void> result = new Result<>(); store.close(error -> result.complete(null, error)); return result.await();
    }
    private static Result<List<IncidentSummary>> read(SQLiteIncidentStore store) throws InterruptedException {
        Result<List<IncidentSummary>> result = new Result<>(); store.readIncidents(result::complete); return result.await();
    }
    @Test public void admissionAccountingCloseAndReentryPreserveAcceptedBatch() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            IncidentPersistenceOptions options = new IncidentPersistenceOptions(10, 604800000, 131072, 16777216, 64);
            SQLiteIncidentStore store = new SQLiteIncidentStore(fixture.directory, options, CLOCK); read(store).success();
            CountDownLatch entered = new CountDownLatch(1), gate = new CountDownLatch(1);
            store.readIncidents((values, error) -> {
                entered.countDown(); try { gate.await(10, TimeUnit.SECONDS); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            try {
                SystemDiagnosticBatch records = batch();
                Result<DiagnosticRecoveryReceipt> saved = new Result<>(), busy = new Result<>();
                store.recoverDiagnostics(records, saved::complete); store.recoverDiagnostics(records, busy::complete);
                assertEquals(PersistenceStatus.Error.BUSY, busy.await().error);
                assertEquals(2, store.persistenceStatus().queuedEvents); assertEquals(64, store.persistenceStatus().queuedTextBytes);
                Result<Void> closed = new Result<>(); store.close(error -> closed.complete(null, error)); gate.countDown();
                assertEquals(2, saved.success().savedCount); closed.success();
                assertEquals(PersistenceStatus.State.CLOSED, store.persistenceStatus().state);
                assertEquals(0, store.persistenceStatus().queuedEvents); assertEquals(0, store.persistenceStatus().queuedTextBytes);
                assertEquals(2, store.persistenceStatus().committedEvents); assertEquals(2, store.persistenceStatus().discardedEvents);
                Result<DiagnosticRecoveryReceipt> rejected = new Result<>(); store.recoverDiagnostics(records, rejected::complete);
                assertEquals(PersistenceStatus.Error.CLOSED, rejected.await().error);
                SQLiteIncidentStore reopened = new SQLiteIncidentStore(fixture.directory, options, CLOCK);
                try {
                    assertEquals(2, read(reopened).success().size());
                    Result<DiagnosticRecoveryReceipt> repeated = new Result<>(); Result<List<IncidentSummary>> reentered = new Result<>();
                    reopened.recoverDiagnostics(records, (receipt, error) -> {
                        repeated.complete(receipt, error); reopened.readIncidents(reentered::complete);
                    });
                    DiagnosticRecoveryReceipt receipt = repeated.success();
                    assertEquals(0, receipt.savedCount); assertEquals(2, receipt.alreadyProcessedCount); assertEquals(2, reentered.success().size());
                    assertEquals(0, reopened.persistenceStatus().committedEvents); assertEquals(2, reopened.persistenceStatus().discardedEvents);
                } finally { close(reopened).success(); }
            } finally { gate.countDown(); close(store).success(); }
        }
    }
    @Test public void recoveryFailureClosesStoreAndAccountsForWholeBatch() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            File file = new File(fixture.directory, "incidents.sqlite");
            try (IncidentDatabase initial = new IncidentDatabase(file, new IncidentPersistenceOptions(), 1000000)) { assertTrue(initial.summaries().isEmpty()); }
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file.getPath(), null, 0)) {
                raw.execSQL("CREATE TRIGGER reject_ack BEFORE INSERT ON diagnostic_acknowledgements BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END");
            }
            SQLiteIncidentStore store = new SQLiteIncidentStore(fixture.directory, new IncidentPersistenceOptions(), CLOCK); read(store).success();
            try {
                Result<DiagnosticRecoveryReceipt> result = new Result<>(); store.recoverDiagnostics(batch(), result::complete);
                assertEquals(PersistenceStatus.Error.INVALID_RECORD, result.await().error);
                assertEquals(PersistenceStatus.State.FAILED, store.persistenceStatus().state); assertEquals(1, store.persistenceStatus().failedBatches);
                assertEquals(0, store.persistenceStatus().committedEvents); assertEquals(2, store.persistenceStatus().discardedEvents);
            } finally { close(store); }
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                assertEquals(0, EvidenceSQLite.scalar(raw, "SELECT count(*) FROM incidents"));
                assertEquals(0, EvidenceSQLite.scalar(raw, "SELECT count(*) FROM diagnostic_acknowledgements"));
            }
        }
    }
}
