package com.sandrox.osservio.store;

import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.sandrox.osservio.core.DiagnosticRecoveryReceipt;
import com.sandrox.osservio.core.IncidentSnapshot;
import com.sandrox.osservio.core.IncidentSummary;
import com.sandrox.osservio.core.LogMessage;
import com.sandrox.osservio.core.PersistenceStatus;
import com.sandrox.osservio.core.SystemDiagnostic;
import com.sandrox.osservio.core.SystemDiagnosticBatch;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import static com.sandrox.osservio.store.PersistenceFixture.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class DiagnosticRecoveryDatabaseTest {
    private static final long NOW = 1000000;
    private static File file(PersistenceFixture fixture) { return new File(fixture.directory, "incidents.sqlite"); }
    private static SystemDiagnostic exit(long time) { return exit(time, 4); }
    private static SystemDiagnostic exit(long time, int reason) { return SystemDiagnostic.androidExit(time, reason, 0, 123, 100, null, null); }
    private static SystemDiagnosticBatch batch(SystemDiagnostic... diagnostics) {
        SystemDiagnostic.Source source = diagnostics.length == 0 ? SystemDiagnostic.Source.ANDROID_EXIT_INFO : diagnostics[0].source;
        return new SystemDiagnosticBatch(source, 42, Arrays.asList(diagnostics), 0, 0, false);
    }
    private static IncidentPersistenceOptions options(long age, int textBytes, int retained) {
        return new IncidentPersistenceOptions(10, age, textBytes, 16777216, 524288, retained);
    }
    @Test public void repeatedMaterialPreservesBatchMultiplicityAcrossDeletionAndReopen() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            IncidentPersistenceOptions options = new IncidentPersistenceOptions();
            SystemDiagnostic diagnostic = exit(NOW);
            try (IncidentDatabase writer = new IncidentDatabase(file(fixture), options, NOW)) {
                DiagnosticRecoveryReceipt first = writer.recover(batch(diagnostic, diagnostic), NOW);
                assertEquals(2, first.savedCount); assertEquals(0, first.alreadyProcessedCount);
                Set<String> identities = new HashSet<>();
                for (IncidentSummary summary : writer.summaries()) {
                    identities.add(summary.id);
                    IncidentSnapshot saved = writer.read(summary.id); assertNotNull(saved);
                    assertEquals(42, saved.timestampMillis); assertEquals(NOW, saved.systemDiagnostic.intervalEndMillis);
                    assertNull(saved.environment); assertNull(saved.breadcrumbs); assertNull(saved.logs);
                }
                assertEquals(2, identities.size()); writer.deleteAll();
            }
            try (IncidentDatabase reader = new IncidentDatabase(file(fixture), options, NOW + 1)) {
                DiagnosticRecoveryReceipt smaller = reader.recover(batch(diagnostic), NOW + 1);
                assertEquals(1, smaller.alreadyProcessedCount); assertEquals(0, smaller.savedCount); assertTrue(reader.summaries().isEmpty());
                DiagnosticRecoveryReceipt increased = reader.recover(batch(diagnostic, diagnostic, diagnostic), NOW + 2);
                assertEquals(2, increased.alreadyProcessedCount); assertEquals(1, increased.savedCount);
                assertEquals(1, reader.summaries().size());
            }
        }
    }
    @Test public void filteringAndBatchCapacityKeepNewestEligibleEvidenceAndManualMarks() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture(); IncidentDatabase store = new IncidentDatabase(file(fixture), options(60000, 131072, 2), NOW)) {
            IncidentSnapshot manual = IncidentSnapshot.capture(LogMessage.publicText("keep manual"), null, null); store.save(manual, NOW);
            SystemDiagnosticBatch records = batch(exit(NOW - 60001), exit(NOW + 1), exit(NOW, 1), exit(NOW - 60000), exit(NOW - 2), exit(NOW - 1), exit(NOW));
            DiagnosticRecoveryReceipt result = store.recover(records, NOW);
            assertEquals(2, result.savedCount); assertEquals(1, result.ignoredExitCount);
            assertEquals(2, result.outsideWindowCount); assertEquals(2, result.capacityExcludedCount);
            assertEquals(NOW, store.read(store.summaries().get(0).id).systemDiagnostic.intervalEndMillis);
            assertEquals(NOW - 1, store.read(store.summaries().get(1).id).systemDiagnostic.intervalEndMillis);
            assertNotNull(store.read(manual.id));
            DiagnosticRecoveryReceipt repeated = store.recover(records, NOW);
            assertEquals(4, repeated.alreadyProcessedCount); assertEquals(0, repeated.savedCount); assertEquals(0, repeated.capacityExcludedCount);
        }
    }
    @Test public void textCapacityAndExtremeClocksRemainBounded() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture(); IncidentDatabase store = new IncidentDatabase(file(fixture), options(604800000, 64, 10), NOW)) {
            char[] text = new char[65]; Arrays.fill(text, 'x');
            SystemDiagnostic large = SystemDiagnostic.metricKit(NOW, NOW, SystemDiagnostic.Kind.CRASH,
                Collections.singletonMap(SystemDiagnostic.MetadataKey.APP_VERSION, SystemDiagnostic.Text.capturePublic(new String(text))), null, null, null, null);
            SystemDiagnostic small = SystemDiagnostic.metricKit(NOW, NOW, SystemDiagnostic.Kind.HANG, Collections.emptyMap(), null, null, null, 0L);
            DiagnosticRecoveryReceipt result = store.recover(batch(large, small), NOW);
            assertEquals(1, result.savedCount); assertEquals(1, result.capacityExcludedCount);
        }
        for (long time : new long[] {Long.MIN_VALUE, Long.MAX_VALUE}) {
            try (PersistenceFixture fixture = new PersistenceFixture(); IncidentDatabase store = new IncidentDatabase(file(fixture), new IncidentPersistenceOptions(), time)) {
                SystemDiagnostic diagnostic = SystemDiagnostic.metricKit(time, time, SystemDiagnostic.Kind.HANG, Collections.emptyMap(), null, null, null, 0L);
                assertEquals(1, store.recover(batch(diagnostic), time).savedCount);
            }
        }
    }
    @Test public void acknowledgementFailureRollsBackNewReportsAndRetention() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            IncidentPersistenceOptions options = options(604800000, 131072, 1);
            IncidentSnapshot original = IncidentSnapshot.captureDiagnostic(exit(NOW - 1), 12);
            try (IncidentDatabase writer = new IncidentDatabase(file(fixture), options, NOW)) { writer.save(original, NOW); }
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, 0)) {
                raw.execSQL("CREATE TRIGGER reject_ack BEFORE INSERT ON diagnostic_acknowledgements BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END");
            }
            try (IncidentDatabase store = new IncidentDatabase(file(fixture), options, NOW)) {
                expect(PersistenceStatus.Error.INVALID_RECORD, () -> store.recover(batch(exit(NOW)), NOW));
                assertEquals(1, store.summaries().size()); assertEquals(original.id, store.summaries().get(0).id);
            }
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                assertEquals(0, EvidenceSQLite.scalar(raw, "SELECT count(*) FROM diagnostic_acknowledgements"));
            }
        }
    }
    @Test public void acknowledgementsStayBoundedAndExpiredCorruptionIsNotRepaired() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            IncidentPersistenceOptions options = options(60000, 131072, 10);
            try (IncidentDatabase ignored = new IncidentDatabase(file(fixture), options, 1)) { assertTrue(ignored.summaries().isEmpty()); }
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, 0)) {
                for (int index = 1; index <= 256; index++) raw.execSQL("INSERT INTO diagnostic_acknowledgements(fingerprint,occurrences,processed_at) VALUES (?,1,1)",
                    new Object[] {String.format(Locale.ROOT, "%064x", index)});
            }
            try (IncidentDatabase store = new IncidentDatabase(file(fixture), options, 2)) { assertEquals(1, store.recover(batch(exit(2)), 2).savedCount); }
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, 0)) {
                assertEquals(256, EvidenceSQLite.scalar(raw, "SELECT count(*) FROM diagnostic_acknowledgements"));
                assertEquals(2, EvidenceSQLite.scalar(raw, "SELECT min(ordinal) FROM diagnostic_acknowledgements"));
                raw.execSQL("UPDATE diagnostic_acknowledgements SET occurrences=33 WHERE ordinal=257");
            }
            byte[] before = read(file(fixture));
            expect(PersistenceStatus.Error.INVALID_RECORD, () -> new IncidentDatabase(file(fixture), options, NOW));
            assertArrayEquals(before, read(file(fixture)));
        }
    }
    private static void legacy(File file) throws Exception {
        try (SQLiteDatabase raw = SQLiteDatabase.openOrCreateDatabase(file, null)) {
            for (String name : new String[] {"incident-v1.sql", "incident-v2.sql", "incident-v3.sql"}) {
                for (String sql : IncidentDetailFixture.shared(name).split(";")) if (!sql.trim().isEmpty()) raw.execSQL(sql);
            }
        }
    }
    @Test public void sharedAcknowledgementCorruptionIsRejectedEvenAfterExpiry() throws Exception {
        for (String line : IncidentDetailFixture.shared("diagnostic-acknowledgement-corruption.tsv").split("\n")) {
            String[] fields = line.split("\t");
            try (PersistenceFixture fixture = new PersistenceFixture()) {
                IncidentPersistenceOptions options = options(60000, 131072, 10);
                try (IncidentDatabase writer = new IncidentDatabase(file(fixture), options, 1)) { writer.recover(batch(exit(1)), 1); }
                try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, 0)) { raw.execSQL(fields[1]); }
                byte[] before = read(file(fixture));
                expect(PersistenceStatus.Error.INVALID_RECORD, () -> new IncidentDatabase(file(fixture), options, NOW));
                assertArrayEquals(fields[0], before, read(file(fixture)));
            }
        }
    }
    @Test public void revisionThreeMigrationPreservesBothKindsAndRollsBackOnConflict() throws Exception {
        String id = "eeeeeeee-eeee-eeee-eeee-000000000001";
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            legacy(file(fixture));
            try (IncidentDatabase store = new IncidentDatabase(file(fixture), new IncidentPersistenceOptions(), 200)) {
                assertEquals(2, store.summaries().size());
                IncidentSnapshot existing = store.read(id); assertNotNull(existing);
                assertEquals(123, existing.timestampMillis); assertEquals("18446744073709551615", existing.systemDiagnostic.metricKit.exceptionCode);
                assertEquals(1, store.recover(batch(exit(150)), 200).savedCount);
            }
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                assertEquals(5, EvidenceSQLite.scalar(raw, "PRAGMA user_version"));
                assertEquals(100, EvidenceSQLite.scalar(raw, "SELECT saved_at FROM incidents WHERE id=?", id));
            }
        }
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            legacy(file(fixture));
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, 0)) { raw.execSQL("CREATE TABLE diagnostic_acknowledgements(sentinel TEXT)"); }
            expect(PersistenceStatus.Error.UNAVAILABLE, () -> new IncidentDatabase(file(fixture), new IncidentPersistenceOptions(), 200));
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                assertEquals(3, EvidenceSQLite.scalar(raw, "PRAGMA user_version"));
                assertEquals(2, EvidenceSQLite.scalar(raw, "SELECT count(*) FROM incidents"));
            }
        }
    }
}
