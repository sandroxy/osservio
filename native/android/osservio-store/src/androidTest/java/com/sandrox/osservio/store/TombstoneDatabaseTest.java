package com.sandrox.osservio.store;

import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.sandrox.osservio.core.AndroidTombstone;
import com.sandrox.osservio.core.DiagnosticRecoveryReceipt;
import com.sandrox.osservio.core.IncidentSnapshot;
import com.sandrox.osservio.core.IncidentSummary;
import com.sandrox.osservio.core.PersistenceStatus;
import com.sandrox.osservio.core.SystemDiagnostic;
import com.sandrox.osservio.core.SystemDiagnosticBatch;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static com.sandrox.osservio.store.PersistenceFixture.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class TombstoneDatabaseTest {
    private static File file(PersistenceFixture fixture) { return new File(fixture.directory, "incidents.sqlite"); }
    private static SystemDiagnostic evidence(SystemDiagnostic.TombstoneState state) {
        return SystemDiagnostic.androidExit(1000, state == SystemDiagnostic.TombstoneState.NOT_APPLICABLE ? 4 : 5, 6, 123, 100, null, null, state,
            state == SystemDiagnostic.TombstoneState.AVAILABLE ? AndroidTombstone.restore(1, 6, -6, 99, "18446744073709551615",
                Arrays.asList(AndroidTombstone.Frame.restore("18446744073709551615", "18446744073709551615", "0123456789abcdef0123456789abcdef01234567"),
                    AndroidTombstone.Frame.restore("0", "0", null))) : null);
    }
    private static SystemDiagnostic rich() { return evidence(SystemDiagnostic.TombstoneState.AVAILABLE); }
    private static SystemDiagnostic summary() { return evidence(SystemDiagnostic.TombstoneState.UNAVAILABLE); }
    private static SystemDiagnosticBatch batch(SystemDiagnostic... values) {
        return new SystemDiagnosticBatch(SystemDiagnostic.Source.ANDROID_EXIT_INFO, 2000, Arrays.asList(values), 0, 0, false);
    }
    @Test public void allReadStatesRoundTripWithExactOriginalEvidence() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            List<IncidentSnapshot> snapshots = new ArrayList<>();
            for (SystemDiagnostic.TombstoneState state : SystemDiagnostic.TombstoneState.values()) snapshots.add(IncidentSnapshot.captureDiagnostic(evidence(state), 2000));
            try (IncidentDatabase writer = new IncidentDatabase(file(fixture), new IncidentPersistenceOptions(), 2000)) {
                for (IncidentSnapshot snapshot : snapshots) writer.save(snapshot, 2000);
            }
            try (IncidentDatabase reader = new IncidentDatabase(file(fixture), new IncidentPersistenceOptions(), 2001)) {
                for (IncidentSnapshot original : snapshots) {
                    IncidentSnapshot saved = reader.read(original.id); assertNotNull(saved);
                    SystemDiagnostic diagnostic = saved.systemDiagnostic;
                    assertEquals(2000, saved.timestampMillis); assertEquals(1000, diagnostic.intervalStartMillis);
                    assertEquals(original.systemDiagnostic.androidExit.tombstoneState, diagnostic.androidExit.tombstoneState);
                    assertEquals(original.textByteCount, saved.textByteCount);
                    assertEquals(DiagnosticFingerprint.make(original.systemDiagnostic), DiagnosticFingerprint.make(diagnostic));
                    AndroidTombstone trace = diagnostic.androidExit.tombstone;
                    if (trace != null) {
                        assertEquals("18446744073709551615", trace.faultAddress); assertEquals(-6, trace.signalCode); assertEquals(99, trace.threadID);
                        assertEquals("18446744073709551615", trace.frames.get(0).programCounter);
                        assertEquals("18446744073709551615", trace.frames.get(0).relativeProgramCounter);
                        assertEquals("0", trace.frames.get(1).programCounter); assertNull(trace.frames.get(1).buildID);
                        assertNull(saved.environment); assertNull(saved.breadcrumbs);
                    }
                }
            }
        }
    }
    @Test public void sharedCorruptionIsRejectedWithoutRepair() throws Exception {
        for (String line : IncidentDetailFixture.shared("tombstone-corruption.tsv").split("\n")) {
            String[] row = line.split("\t");
            try (PersistenceFixture fixture = new PersistenceFixture()) {
                IncidentSnapshot snapshot = IncidentSnapshot.captureDiagnostic(rich(), 2000);
                try (IncidentDatabase writer = new IncidentDatabase(file(fixture), new IncidentPersistenceOptions(), 2000)) { writer.save(snapshot, 2000); }
                try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, 0)) { raw.execSQL(row[1]); }
                byte[] before = read(file(fixture));
                try (IncidentDatabase reader = new IncidentDatabase(file(fixture), new IncidentPersistenceOptions(), 2001)) {
                    expect(PersistenceStatus.Error.INVALID_RECORD, () -> reader.read(snapshot.id));
                }
                assertArrayEquals(row[0], before, read(file(fixture)));
            }
        }
    }
    @Test public void recoveryEnrichmentAndEvictionDoNotRewriteOrDegradeSavedEvidence() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            try (IncidentDatabase writer = new IncidentDatabase(file(fixture), new IncidentPersistenceOptions(), 2000)) {
                assertEquals(1, writer.recover(batch(summary()), 2000).savedCount);
                String original = writer.summaries().get(0).id;
                assertEquals(1, writer.recover(batch(rich()), 2001).savedCount);
                assertEquals(SystemDiagnostic.TombstoneState.UNAVAILABLE, writer.read(original).systemDiagnostic.androidExit.tombstoneState);
                assertEquals(2, writer.summaries().size());
            }
            try (IncidentDatabase reader = new IncidentDatabase(file(fixture), new IncidentPersistenceOptions(), 2002)) {
                assertEquals(1, reader.recover(batch(rich()), 2002).alreadyProcessedCount);
                assertEquals(1, reader.recover(batch(summary()), 2003).alreadyProcessedCount);
                reader.deleteAll();
                assertEquals(1, reader.recover(batch(rich()), 2004).alreadyProcessedCount);
                assertEquals(1, reader.recover(batch(summary()), 2005).alreadyProcessedCount); assertTrue(reader.summaries().isEmpty());
            }
        }
        try (PersistenceFixture fixture = new PersistenceFixture(); IncidentDatabase store = new IncidentDatabase(file(fixture), new IncidentPersistenceOptions(), 2000)) {
            assertEquals(1, store.recover(batch(rich()), 2000).savedCount);
            assertEquals(1, store.recover(batch(evidence(SystemDiagnostic.TombstoneState.TOO_LARGE)), 2001).alreadyProcessedCount);
            assertEquals(1, store.summaries().size());
            DiagnosticRecoveryReceipt receipt = store.recover(batch(summary(), summary()), 2002);
            assertEquals(1, receipt.savedCount); assertEquals(1, receipt.alreadyProcessedCount);
        }
    }
    @Test public void mixedBatchAliasesKeepMultiplicityWithinBounds() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture(); IncidentDatabase store = new IncidentDatabase(file(fixture), new IncidentPersistenceOptions(), 2000)) {
            SystemDiagnosticBatch mixed = batch(rich(), summary());
            assertEquals(2, store.recover(mixed, 2000).savedCount);
            assertEquals(2, store.recover(mixed, 2001).alreadyProcessedCount);
            assertEquals(2, store.recover(batch(summary(), summary()), 2002).alreadyProcessedCount);
        }
    }
    @Test public void frameWriteFailureRollsBackRetentionAcknowledgementsAndDeletionCascades() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            IncidentPersistenceOptions options = new IncidentPersistenceOptions(10, 604800000, 131072, 16777216, 524288, 1);
            String original;
            try (IncidentDatabase first = new IncidentDatabase(file(fixture), options, 2000)) {
                assertEquals(1, first.recover(batch(summary()), 2000).savedCount); original = first.summaries().get(0).id;
            }
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, 0)) {
                raw.execSQL("CREATE TRIGGER reject_frame BEFORE INSERT ON android_tombstone_frames WHEN NEW.position=1 BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END");
            }
            try (IncidentDatabase failed = new IncidentDatabase(file(fixture), options, 2001)) {
                expect(PersistenceStatus.Error.INVALID_RECORD, () -> failed.recover(batch(rich()), 2001));
                assertEquals(1, failed.summaries().size()); assertEquals(original, failed.summaries().get(0).id);
            }
            try (SQLiteDatabase repair = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, 0)) { repair.execSQL("DROP TRIGGER reject_frame"); }
            try (IncidentDatabase retry = new IncidentDatabase(file(fixture), options, 2002)) {
                assertEquals(1, retry.recover(batch(rich()), 2002).savedCount); assertNull(retry.read(original)); retry.deleteAll();
            }
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                for (String table : new String[] {"android_tombstones", "android_tombstone_frames", "incident_diagnostics"})
                    assertEquals(0, EvidenceSQLite.scalar(raw, "SELECT count(*) FROM " + table));
            }
        }
    }
    private static void legacy(File file) throws Exception {
        try (SQLiteDatabase raw = SQLiteDatabase.openOrCreateDatabase(file, null)) {
            for (String name : new String[] {"incident-v1.sql", "incident-v2.sql", "incident-v3.sql", "incident-v4.sql", "incident-v5.sql"})
                for (String sql : IncidentDetailFixture.shared(name).split(";")) if (!sql.trim().isEmpty()) raw.execSQL(sql);
        }
    }
    @Test public void revisionFiveMigrationPreservesPriorSourcesAndRollsBackOnConflict() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            legacy(file(fixture));
            try (IncidentDatabase store = new IncidentDatabase(file(fixture), new IncidentPersistenceOptions(), 2000)) {
                assertEquals(4, store.summaries().size());
                for (IncidentSummary summary : store.summaries()) assertNotNull(store.read(summary.id));
                assertEquals(SystemDiagnostic.TombstoneState.NOT_REQUESTED, store.read("eeeeeeee-eeee-eeee-eeee-000000000003").systemDiagnostic.androidExit.tombstoneState);
                SystemDiagnostic metric = store.read("eeeeeeee-eeee-eeee-eeee-000000000001").systemDiagnostic;
                assertEquals(1, store.recover(new SystemDiagnosticBatch(SystemDiagnostic.Source.METRIC_KIT, 2000, Collections.singletonList(metric), 0, 0, false), 2000).alreadyProcessedCount);
                store.save(IncidentSnapshot.captureDiagnostic(rich(), 2000), 2000);
            }
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                assertEquals(7, EvidenceSQLite.scalar(raw, "PRAGMA user_version"));
                assertEquals(100, EvidenceSQLite.scalar(raw, "SELECT saved_at FROM incidents WHERE id='eeeeeeee-eeee-eeee-eeee-000000000003'"));
            }
        }
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            legacy(file(fixture));
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, 0)) { raw.execSQL("CREATE TABLE android_tombstone_frames(sentinel TEXT)"); }
            expect(PersistenceStatus.Error.UNAVAILABLE, () -> new IncidentDatabase(file(fixture), new IncidentPersistenceOptions(), 2000));
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                assertEquals(5, EvidenceSQLite.scalar(raw, "PRAGMA user_version"));
                assertEquals(0, EvidenceSQLite.scalar(raw, "SELECT count(*) FROM sqlite_master WHERE name='android_tombstones'"));
                assertEquals(3, EvidenceSQLite.scalar(raw, "SELECT count(*) FROM incident_diagnostics"));
            }
        }
    }
}
