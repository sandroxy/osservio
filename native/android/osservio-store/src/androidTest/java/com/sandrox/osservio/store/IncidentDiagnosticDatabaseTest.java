package com.sandrox.osservio.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.sandrox.osservio.core.IncidentSnapshot;
import com.sandrox.osservio.core.LogClock;
import com.sandrox.osservio.core.LogMessage;
import com.sandrox.osservio.core.PersistenceStatus;
import com.sandrox.osservio.core.SystemDiagnostic;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import static com.sandrox.osservio.store.PersistenceFixture.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class IncidentDiagnosticDatabaseTest {
    private static final String LEGACY_ID = "12345678-1234-1234-1234-123456789abc";
    private static File file(PersistenceFixture fixture) { return new File(fixture.directory, "incidents.sqlite"); }
    private static SystemDiagnostic evidence(String family) {
        if (family.equals("android")) return SystemDiagnostic.androidExit(100, 4, -1, 123, 100, Long.MAX_VALUE, null);
        Map<SystemDiagnostic.MetadataKey, SystemDiagnostic.Text> metadata = new EnumMap<>(SystemDiagnostic.MetadataKey.class);
        StringBuilder version = new StringBuilder(); for (int i = 0; i < 65; i++) version.append("😀");
        metadata.put(SystemDiagnostic.MetadataKey.APP_VERSION, SystemDiagnostic.Text.capturePublic(version.toString()));
        metadata.put(SystemDiagnostic.MetadataKey.APP_BUILD, SystemDiagnostic.Text.capturePublic("build\u0000例"));
        return SystemDiagnostic.metricKit(-100, 200, SystemDiagnostic.Kind.CRASH, metadata, 11, Long.MAX_VALUE, "18446744073709551615", null);
    }
    @Test public void roundTripKeepsRecoveryTimeSeparateAndPreservesExactFacts() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            IncidentPersistenceOptions options = new IncidentPersistenceOptions();
            IncidentSnapshot metric = IncidentSnapshot.captureDiagnostic(evidence("metric"), 42);
            IncidentSnapshot android = IncidentSnapshot.captureDiagnostic(evidence("android"), 43);
            IncidentSnapshot hang = IncidentSnapshot.captureDiagnostic(SystemDiagnostic.metricKit(0, Long.MAX_VALUE, SystemDiagnostic.Kind.HANG,
                Collections.emptyMap(), null, null, null, Long.MAX_VALUE), Long.MIN_VALUE);
            try (IncidentDatabase writer = new IncidentDatabase(file(fixture), options, 0)) {
                for (IncidentSnapshot item : Arrays.asList(metric, android, hang)) writer.save(item, 1);
            }
            try (IncidentDatabase reader = new IncidentDatabase(file(fixture), options, 2)) {
                IncidentSnapshot saved = reader.read(metric.id); assertNotNull(saved);
                assertEquals(42, saved.timestampMillis); assertEquals(-100, saved.systemDiagnostic.intervalStartMillis); assertEquals(200, saved.systemDiagnostic.intervalEndMillis);
                assertEquals("18446744073709551615", saved.systemDiagnostic.metricKit.exceptionCode);
                assertEquals(Long.valueOf(Long.MAX_VALUE), saved.systemDiagnostic.metricKit.exceptionType);
                assertTrue(saved.systemDiagnostic.metricKit.metadata.get(SystemDiagnostic.MetadataKey.APP_VERSION).isTruncated);
                assertEquals("build\u0000例", saved.systemDiagnostic.metricKit.metadata.get(SystemDiagnostic.MetadataKey.APP_BUILD).value);
                assertEquals(metric.textByteCount, saved.textByteCount); assertNull(saved.environment); assertNull(saved.breadcrumbs); assertNull(saved.logs);
                assertEquals(Long.valueOf(Long.MAX_VALUE), reader.read(android.id).systemDiagnostic.androidExit.pssKiB);
                assertNull(reader.read(android.id).systemDiagnostic.androidExit.rssKiB);
                assertEquals(Long.valueOf(Long.MAX_VALUE), reader.read(hang.id).systemDiagnostic.metricKit.hangDurationMillis);
                assertEquals(SystemDiagnostic.Kind.HANG, reader.summaries().get(0).diagnosticKind);
                assertEquals(SystemDiagnostic.Kind.MANAGED_CRASH, reader.summaries().get(1).diagnosticKind);
                assertEquals(SystemDiagnostic.Kind.CRASH, reader.summaries().get(2).diagnosticKind);
            }
        }
    }
    @Test public void separateCountLimitsAgeAndCascadingDeletion() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            IncidentPersistenceOptions options = new IncidentPersistenceOptions(1, 60000, 131072, 16777216, 524288, 2);
            try (IncidentDatabase store = new IncidentDatabase(file(fixture), options, 0)) {
                IncidentSnapshot manual = IncidentSnapshot.capture(LogMessage.publicText("keep manual"), null, null);
                store.save(manual, 1);
                IncidentSnapshot[] records = new IncidentSnapshot[3];
                for (int i = 0; i < 3; i++) { records[i] = IncidentSnapshot.captureDiagnostic(evidence("metric"), i); store.save(records[i], i + 2); }
                assertNotNull(store.read(manual.id)); assertNull(store.read(records[0].id)); assertEquals(3, store.summaries().size());
                store.save(IncidentSnapshot.capture(null, null, null), 5);
                assertNull(store.read(manual.id)); assertNotNull(store.read(records[1].id));
                store.delete(records[1].id);
            }
            try (IncidentDatabase expired = new IncidentDatabase(file(fixture), options, 60006)) { assertTrue(expired.summaries().isEmpty()); }
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                assertEquals(0, EvidenceSQLite.scalar(raw, "SELECT count(*) FROM incident_diagnostics"));
                assertEquals(0, EvidenceSQLite.scalar(raw, "SELECT count(*) FROM diagnostic_metadata"));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new IncidentPersistenceOptions(10, 60000, 64, 1048576, 64, 0));
        assertThrows(IllegalArgumentException.class, () -> new IncidentPersistenceOptions(10, 60000, 64, 1048576, 64, 17));
    }
    @Test public void sharedCorruptionIsRejectedWithoutRepair() throws Exception {
        for (String line : IncidentDetailFixture.shared("incident-diagnostic-corruption.tsv").split("\n")) {
            String[] row = line.split("\t");
            try (PersistenceFixture fixture = new PersistenceFixture()) {
                IncidentPersistenceOptions options = new IncidentPersistenceOptions();
                IncidentSnapshot snapshot = IncidentSnapshot.captureDiagnostic(evidence(row[1]), 500);
                try (IncidentDatabase writer = new IncidentDatabase(file(fixture), options, 0)) { writer.save(snapshot, 1); }
                try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, 0)) { raw.execSQL(row[2]); }
                byte[] before = read(file(fixture));
                try (IncidentDatabase reader = new IncidentDatabase(file(fixture), options, 2)) {
                    expect(PersistenceStatus.Error.INVALID_RECORD, () -> reader.read(snapshot.id));
                }
                assertArrayEquals(row[0], before, read(file(fixture)));
            }
        }
    }
    @Test public void metadataWriteFailureRollsBackRetentionAndPartialEvidence() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            IncidentPersistenceOptions options = new IncidentPersistenceOptions(10, 604800000, 131072, 16777216, 524288, 1);
            IncidentSnapshot original = IncidentSnapshot.captureDiagnostic(evidence("metric"), 1);
            IncidentSnapshot failed = IncidentSnapshot.captureDiagnostic(evidence("metric"), 2);
            try (IncidentDatabase first = new IncidentDatabase(file(fixture), options, 0)) { first.save(original, 1); }
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, 0)) {
                for (String table : new String[] {"incident_diagnostics", "diagnostic_metadata"}) raw.execSQL("ALTER TABLE " + table + " ADD COLUMN optional_future TEXT");
                raw.execSQL("CREATE TRIGGER reject_metadata BEFORE INSERT ON diagnostic_metadata WHEN NEW.key='appVersion' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END");
            }
            try (IncidentDatabase store = new IncidentDatabase(file(fixture), options, 2)) {
                expect(PersistenceStatus.Error.INVALID_RECORD, () -> store.save(failed, 3));
                assertEquals(1, store.summaries().size()); assertEquals(original.id, store.summaries().get(0).id);
                assertEquals("18446744073709551615", store.read(original.id).systemDiagnostic.metricKit.exceptionCode);
                assertNull(store.read(failed.id));
            }
        }
    }
    private static void legacy(File file) throws Exception {
        try (SQLiteDatabase raw = SQLiteDatabase.openOrCreateDatabase(file, null)) {
            for (String name : new String[] {"incident-v1.sql", "incident-v2.sql"}) {
                for (String sql : IncidentDetailFixture.shared(name).split(";")) if (!sql.trim().isEmpty()) raw.execSQL(sql);
            }
        }
    }
    @Test public void independentRevisionTwoMigrationPreservesAttachmentsAndSaveTime() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            legacy(file(fixture));
            try (IncidentDatabase store = new IncidentDatabase(file(fixture), new IncidentPersistenceOptions(), 101)) {
                IncidentSnapshot original = store.read(LEGACY_ID); assertNotNull(original);
                assertEquals("legacy", original.note.text); assertEquals(59, original.textByteCount);
                assertEquals(9007199254740993L, original.logs.events.get(0).sequence);
                assertEquals("[private]", original.logs.events.get(0).message); assertNull(original.systemDiagnostic);
                IncidentSnapshot added = IncidentSnapshot.captureDiagnostic(evidence("metric"), 102);
                store.save(added, 102); assertNotNull(store.read(added.id).systemDiagnostic);
            }
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                assertEquals(8, EvidenceSQLite.scalar(raw, "PRAGMA user_version"));
                assertEquals(100, EvidenceSQLite.scalar(raw, "SELECT saved_at FROM incidents WHERE id=?", LEGACY_ID));
            }
        }
    }
    @Test public void failedRevisionTwoMigrationRollsBackColumnAndTables() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            legacy(file(fixture));
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, 0)) { raw.execSQL("CREATE TABLE diagnostic_metadata(unrelated TEXT)"); }
            expect(PersistenceStatus.Error.UNAVAILABLE, () -> new IncidentDatabase(file(fixture), new IncidentPersistenceOptions(), 101));
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                assertEquals(2, EvidenceSQLite.scalar(raw, "PRAGMA user_version"));
                assertEquals(0, EvidenceSQLite.scalar(raw, "SELECT count(*) FROM sqlite_master WHERE name='incident_diagnostics'"));
                try (Cursor columns = raw.rawQuery("PRAGMA table_info(incidents)", null)) {
                    while (columns.moveToNext()) assertNotEquals("diagnostic_kind", columns.getString(1));
                }
                assertEquals(1, EvidenceSQLite.scalar(raw, "SELECT count(*) FROM incident_logs WHERE message='[private]'"));
            }
        }
    }
    @Test public void asynchronousCommitAndClosePreserveDiagnosticAndAdmissionBudget() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            IncidentSnapshot snapshot = IncidentSnapshot.captureDiagnostic(evidence("metric"), 42);
            SQLiteIncidentStore store = new SQLiteIncidentStore(fixture.directory, new IncidentPersistenceOptions(), LogClock.SYSTEM);
            Result<Void> saved = new Result<>(); store.save(snapshot, error -> saved.complete(null, error)); saved.success();
            Result<Void> closed = new Result<>(); store.close(error -> closed.complete(null, error)); closed.success();
            SQLiteIncidentStore reopened = new SQLiteIncidentStore(fixture.directory, new IncidentPersistenceOptions(10, 604800000, 64, 16777216, 524288), LogClock.SYSTEM);
            try {
                Result<IncidentSnapshot> restored = new Result<>(); reopened.readIncident(snapshot.id, restored::complete);
                assertEquals("18446744073709551615", restored.success().systemDiagnostic.metricKit.exceptionCode);
                Result<Void> rejected = new Result<>(); reopened.save(snapshot, error -> rejected.complete(null, error)); rejected.await();
                assertEquals(PersistenceStatus.Error.INVALID_RECORD, rejected.error);
            } finally {
                Result<Void> finished = new Result<>(); reopened.close(error -> finished.complete(null, error)); finished.success();
            }
        }
    }
}
