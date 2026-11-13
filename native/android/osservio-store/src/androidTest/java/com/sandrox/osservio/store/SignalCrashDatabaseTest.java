package com.sandrox.osservio.store;

import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.sandrox.osservio.core.AppleSignalCrash;
import com.sandrox.osservio.core.IncidentSnapshot;
import com.sandrox.osservio.core.PersistenceStatus;
import com.sandrox.osservio.core.SystemDiagnostic;
import com.sandrox.osservio.core.SystemDiagnosticBatch;
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
public final class SignalCrashDatabaseTest {
    private static File file(PersistenceFixture fixture) { return new File(fixture.directory, "incidents.sqlite"); }
    private static SystemDiagnostic evidence() {
        Map<SystemDiagnostic.MetadataKey, SystemDiagnostic.Text> metadata = new EnumMap<>(SystemDiagnostic.MetadataKey.class);
        metadata.put(SystemDiagnostic.MetadataKey.APP_VERSION, SystemDiagnostic.Text.capturePublic("😀"));
        metadata.put(SystemDiagnostic.MetadataKey.APP_BUILD, SystemDiagnostic.Text.capturePublic("42"));
        return SystemDiagnostic.appleSignal(AppleSignalCrash.restore("12345678-1234-1234-1234-abcdef123456", 1700000000000L,
            AppleSignalCrash.Signal.ABORT, "#0", "18446744073709551615", 4294967295L, Arrays.asList("0", "18446744073709551615"),
            Arrays.asList(AppleSignalCrash.Image.restore("4096", "4096", "12345678123412341234abcdef123456", "16777228", "18446744073709551615"),
                AppleSignalCrash.Image.restore("18446744073709551615", "0", null, null, null)), metadata, true));
    }
    @Test public void roundTripExactEvidenceAndMissingStackWithoutCurrentLaunchContext() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            IncidentPersistenceOptions options = new IncidentPersistenceOptions();
            IncidentSnapshot snapshot = IncidentSnapshot.captureDiagnostic(evidence(), 1700000001234L);
            IncidentSnapshot absent = IncidentSnapshot.captureDiagnostic(SystemDiagnostic.appleSignal(AppleSignalCrash.restore(
                "00000000-0000-0000-0000-000000000001", 1000, AppleSignalCrash.Signal.ABORT, "#0", "0", null,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyMap(), false)), 42);
            try (IncidentDatabase writer = new IncidentDatabase(file(fixture), options, 1)) { writer.save(snapshot, 1); writer.save(absent, 1); }
            try (IncidentDatabase reader = new IncidentDatabase(file(fixture), options, 2)) {
                IncidentSnapshot saved = reader.read(snapshot.id); assertNotNull(saved);
                AppleSignalCrash signal = saved.systemDiagnostic.appleSignal;
                assertEquals(1700000001234L, saved.timestampMillis); assertEquals(1700000000000L, signal.timestampMillis);
                assertEquals(SystemDiagnostic.Source.APPLE_SIGNAL, saved.systemDiagnostic.source); assertEquals(SystemDiagnostic.Kind.NATIVE_CRASH, saved.systemDiagnostic.kind);
                assertEquals("18446744073709551615", signal.faultAddress); assertEquals(Long.valueOf(4294967295L), signal.threadNumber);
                assertEquals(Arrays.asList("0", "18446744073709551615"), signal.frames); assertEquals(2, signal.images.size());
                assertEquals("18446744073709551615", signal.images.get(0).cpuSubtype); assertEquals("18446744073709551615", signal.images.get(1).baseAddress);
                assertEquals("0", signal.images.get(1).size); assertNull(signal.images.get(1).uuid); assertTrue(signal.imageCacheIncomplete);
                assertEquals("😀", signal.metadata.get(SystemDiagnostic.MetadataKey.APP_VERSION).value);
                assertEquals(83, saved.textByteCount); assertNull(saved.environment); assertNull(saved.breadcrumbs); assertNull(saved.logs);
                assertEquals(DiagnosticFingerprint.make(evidence()), DiagnosticFingerprint.make(saved.systemDiagnostic));
                AppleSignalCrash missing = reader.read(absent.id).systemDiagnostic.appleSignal;
                assertNull(missing.threadNumber); assertTrue(missing.frames.isEmpty()); assertTrue(missing.images.isEmpty());
            }
        }
    }
    @Test public void sharedCorruptionIsRejectedWithoutRepair() throws Exception {
        for (String line : IncidentDetailFixture.shared("signal-crash-corruption.tsv").split("\n")) {
            String[] row = line.split("\t");
            try (PersistenceFixture fixture = new PersistenceFixture()) {
                IncidentPersistenceOptions options = new IncidentPersistenceOptions();
                IncidentSnapshot snapshot = IncidentSnapshot.captureDiagnostic(evidence(), 42);
                try (IncidentDatabase writer = new IncidentDatabase(file(fixture), options, 1)) { writer.save(snapshot, 1); }
                try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, 0)) { raw.execSQL(row[1]); }
                byte[] before = read(file(fixture));
                try (IncidentDatabase reader = new IncidentDatabase(file(fixture), options, 2)) {
                    expect(PersistenceStatus.Error.INVALID_RECORD, () -> reader.read(snapshot.id));
                }
                assertArrayEquals(row[0], before, read(file(fixture)));
            }
        }
    }
    @Test public void childWriteFailureRollsBackRetentionThenDeletionCascades() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            IncidentPersistenceOptions options = new IncidentPersistenceOptions(10, 604800000, 131072, 16777216, 524288, 1);
            IncidentSnapshot original = IncidentSnapshot.captureDiagnostic(evidence(), 42), candidate = IncidentSnapshot.captureDiagnostic(evidence(), 43);
            try (IncidentDatabase first = new IncidentDatabase(file(fixture), options, 1)) { first.save(original, 1); }
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, 0)) {
                raw.execSQL("CREATE TRIGGER reject_image BEFORE INSERT ON signal_crash_images WHEN NEW.position=1 BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END");
            }
            try (IncidentDatabase store = new IncidentDatabase(file(fixture), options, 2)) {
                expect(PersistenceStatus.Error.INVALID_RECORD, () -> store.save(candidate, 2));
                assertEquals(1, store.summaries().size()); assertEquals(original.id, store.summaries().get(0).id);
                assertNotNull(store.read(original.id)); assertNull(store.read(candidate.id)); store.delete(original.id);
            }
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                for (String table : new String[] {"signal_crashes", "signal_crash_frames", "signal_crash_images", "diagnostic_metadata", "incident_diagnostics"}) {
                    assertEquals(0, EvidenceSQLite.scalar(raw, "SELECT count(*) FROM " + table));
                }
            }
        }
    }
    private static void legacy(File file) throws Exception {
        try (SQLiteDatabase raw = SQLiteDatabase.openOrCreateDatabase(file, null)) {
            for (String name : new String[] {"incident-v1.sql", "incident-v2.sql", "incident-v3.sql", "incident-v4.sql"}) {
                for (String sql : IncidentDetailFixture.shared(name).split(";")) if (!sql.trim().isEmpty()) raw.execSQL(sql);
            }
        }
    }
    @Test public void revisionFourMigrationPreservesAcknowledgementsAndRollbackOnConflict() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            legacy(file(fixture));
            try (IncidentDatabase store = new IncidentDatabase(file(fixture), new IncidentPersistenceOptions(), 2000)) {
                assertEquals(2, store.summaries().size());
                SystemDiagnostic old = store.read("eeeeeeee-eeee-eeee-eeee-000000000001").systemDiagnostic;
                SystemDiagnosticBatch batch = new SystemDiagnosticBatch(SystemDiagnostic.Source.METRIC_KIT, 2000, Collections.singletonList(old), 0, 0, false);
                assertEquals(1, store.recover(batch, 2000).alreadyProcessedCount);
                store.save(IncidentSnapshot.captureDiagnostic(evidence(), 42), 2000);
            }
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                assertEquals(9, EvidenceSQLite.scalar(raw, "PRAGMA user_version"));
                assertEquals(100, EvidenceSQLite.scalar(raw, "SELECT saved_at FROM incidents WHERE id='eeeeeeee-eeee-eeee-eeee-000000000001'"));
            }
        }
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            legacy(file(fixture));
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, 0)) { raw.execSQL("CREATE TABLE signal_crash_images(sentinel TEXT)"); }
            expect(PersistenceStatus.Error.UNAVAILABLE, () -> new IncidentDatabase(file(fixture), new IncidentPersistenceOptions(), 2000));
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                assertEquals(4, EvidenceSQLite.scalar(raw, "PRAGMA user_version"));
                assertEquals(0, EvidenceSQLite.scalar(raw, "SELECT count(*) FROM sqlite_master WHERE name IN ('signal_crashes','signal_crash_frames')"));
                assertEquals(1, EvidenceSQLite.scalar(raw, "SELECT count(*) FROM diagnostic_acknowledgements"));
            }
        }
    }
    @Test public void recoverySurvivesReopenAndAcknowledgesDeletedEvidence() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            long time = 1700000001000L;
            IncidentPersistenceOptions options = new IncidentPersistenceOptions();
            SystemDiagnosticBatch batch = new SystemDiagnosticBatch(SystemDiagnostic.Source.APPLE_SIGNAL, time, Collections.singletonList(evidence()), 0, 0, false);
            try (IncidentDatabase writer = new IncidentDatabase(file(fixture), options, time)) { assertEquals(1, writer.recover(batch, time).savedCount); }
            try (IncidentDatabase reader = new IncidentDatabase(file(fixture), options, time + 1)) {
                assertEquals(1, reader.recover(batch, time + 1).alreadyProcessedCount); assertEquals(1, reader.summaries().size());
                reader.deleteAll(); assertEquals(1, reader.recover(batch, time + 2).alreadyProcessedCount); assertTrue(reader.summaries().isEmpty());
            }
        }
    }
}
