package com.sandrox.osservio.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.sandrox.osservio.core.BreadcrumbEvent;
import com.sandrox.osservio.core.BreadcrumbSnapshot;
import com.sandrox.osservio.core.EnvironmentSnapshot;
import com.sandrox.osservio.core.IncidentSnapshot;
import com.sandrox.osservio.core.LogClock;
import com.sandrox.osservio.core.LogMessage;
import com.sandrox.osservio.core.PersistenceStatus;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import static com.sandrox.osservio.store.PersistenceFixture.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class IncidentDatabaseTest {
    private static File file(PersistenceFixture fixture) { return new File(fixture.directory, "incidents.sqlite"); }
    private static IncidentSnapshot context() { return context(2, "public\u0000例"); }
    private static IncidentSnapshot context(int count, String message) {
        Map<EnvironmentSnapshot.Key, EnvironmentSnapshot.Fact> facts = new EnumMap<>(EnvironmentSnapshot.Key.class);
        for (EnvironmentSnapshot.Key key : EnvironmentSnapshot.Key.values()) facts.put(key, EnvironmentSnapshot.Fact.UNAVAILABLE);
        facts.put(EnvironmentSnapshot.Key.OS_VERSION, EnvironmentSnapshot.Fact.text(repeat('x', 300)));
        facts.put(EnvironmentSnapshot.Key.PHYSICAL_MEMORY_BYTES, EnvironmentSnapshot.Fact.number(Long.MAX_VALUE));
        facts.put(EnvironmentSnapshot.Key.LOW_POWER_MODE, EnvironmentSnapshot.Fact.flag(false));
        facts.put(EnvironmentSnapshot.Key.PROCESS_PROPORTIONAL_BYTES, EnvironmentSnapshot.Fact.UNSUPPORTED);
        EnvironmentSnapshot environment = EnvironmentSnapshot.capture(EnvironmentSnapshot.Platform.ANDROID, facts, -123);
        List<BreadcrumbEvent> events = new ArrayList<>(); int bytes = 0;
        String session = UUID.randomUUID().toString();
        for (int i = 0; i < count; i++) {
            BreadcrumbEvent event = BreadcrumbEvent.restore(UUID.randomUUID().toString(), session, i + 1, i - 100, Long.MAX_VALUE,
                BreadcrumbEvent.Kind.HOST_MARKER, "fixture", message, false, false, Long.MIN_VALUE,
                i % 2 == 0 ? BreadcrumbEvent.Retention.MEMORY_ONLY : BreadcrumbEvent.Retention.RESTART_RELEVANT);
            events.add(event); bytes += event.textByteCount;
        }
        return IncidentSnapshot.capture(LogMessage.privateText("not written"), environment, new BreadcrumbSnapshot(events, bytes, 7, 1), 2000, 1048576, LogClock.SYSTEM);
    }
    private static String repeat(char value, int count) { char[] chars = new char[count]; Arrays.fill(chars, value); return new String(chars); }
    @Test public void roundTripKeepsIdentityPrecisionPrivacyAndMissingAttachments() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            IncidentPersistenceOptions options = new IncidentPersistenceOptions();
            IncidentSnapshot snapshot = context(), missing = IncidentSnapshot.capture(null, null, null);
            IncidentSnapshot empty = IncidentSnapshot.capture(null, null, new BreadcrumbSnapshot(Collections.emptyList(), 0, 0, 1));
            try (IncidentDatabase writer = new IncidentDatabase(file(fixture), options, 1000)) {
                for (IncidentSnapshot value : Arrays.asList(snapshot, missing, empty)) writer.save(value, 1001);
            }
            try (IncidentDatabase reader = new IncidentDatabase(file(fixture), options, 1002)) {
                IncidentSnapshot restored = reader.read(snapshot.id.toUpperCase(Locale.ROOT));
                assertNotNull(restored); assertEquals(snapshot.id, restored.id); assertEquals(snapshot.timestampMillis, restored.timestampMillis);
                assertEquals("[private]", restored.note.text); assertEquals(-123, restored.environment.timestampMillis);
                assertTrue(restored.environment.values.get(EnvironmentSnapshot.Key.OS_VERSION).isTruncated);
                assertEquals(Long.valueOf(Long.MAX_VALUE), restored.environment.values.get(EnvironmentSnapshot.Key.PHYSICAL_MEMORY_BYTES).fact.number);
                assertEquals(ids(snapshot.breadcrumbs.events), ids(restored.breadcrumbs.events));
                assertEquals("public\u0000例", restored.breadcrumbs.events.get(0).message);
                assertEquals(Long.valueOf(Long.MAX_VALUE), restored.breadcrumbs.events.get(0).sessionElapsedMillis);
                assertEquals(BreadcrumbEvent.Retention.MEMORY_ONLY, restored.breadcrumbs.events.get(0).retention);
                assertEquals(BreadcrumbEvent.Retention.RESTART_RELEVANT, restored.breadcrumbs.events.get(1).retention);
                assertEquals(7, restored.breadcrumbs.sourceEvictedCount);
                assertNull(reader.read(missing.id).breadcrumbs); assertTrue(reader.read(empty.id).breadcrumbs.events.isEmpty());
                assertEquals(3, reader.summaries().size()); assertEquals(empty.id, reader.summaries().get(0).id);
            }
        }
    }
    @Test public void countAgeDeletionAndDuplicateIdentityPreserveWholeReports() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            IncidentPersistenceOptions options = new IncidentPersistenceOptions(2, 60000, 131072, 16777216, 524288);
            IncidentSnapshot first = context(), second = context(), third = context();
            try (IncidentDatabase store = new IncidentDatabase(file(fixture), options, 0)) {
                store.save(first, 0); store.save(second, 1); store.save(third, 2);
                assertNull(store.read(first.id));
                expect(PersistenceStatus.Error.INVALID_RECORD, () -> store.save(second, 100));
                assertEquals(2, store.summaries().size()); assertEquals(third.id, store.summaries().get(0).id);
                store.delete(second.id.toUpperCase(Locale.ROOT)); assertNull(store.read(second.id));
            }
            try (IncidentDatabase expired = new IncidentDatabase(file(fixture), options, 60003)) {
                assertTrue(expired.summaries().isEmpty()); expired.save(first, 60003); expired.deleteAll();
            }
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                for (String table : Arrays.asList("environment_facts", "incident_breadcrumbs")) {
                    try (Cursor row = raw.rawQuery("SELECT count(*) FROM " + table, null)) { assertTrue(row.moveToFirst()); assertEquals(0, row.getInt(0)); }
                }
            }
        }
    }
    @Test public void optionalColumnsAllowExistingReadsAndNewWrites() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            IncidentSnapshot snapshot = context(); IncidentPersistenceOptions options = new IncidentPersistenceOptions();
            try (IncidentDatabase store = new IncidentDatabase(file(fixture), options, 0)) { store.save(snapshot, 0); }
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, 0)) {
                for (String table : Arrays.asList("incidents", "environment_facts", "incident_breadcrumbs")) raw.execSQL("ALTER TABLE " + table + " ADD COLUMN future_optional TEXT");
            }
            try (IncidentDatabase store = new IncidentDatabase(file(fixture), options, 1)) {
                assertEquals(snapshot.textByteCount, store.read(snapshot.id).textByteCount); store.save(context(), 1); assertEquals(2, store.summaries().size());
            }
        }
    }
    @Test public void invalidEvidenceIsRejectedWithoutSilentRepair() throws Exception {
        for (String sql : Arrays.asList(
            "UPDATE incidents SET note='raw', private=1", "UPDATE incidents SET breadcrumb_count=3", "UPDATE incidents SET text_bytes=text_bytes+1",
            "UPDATE environment_facts SET truncated=1 WHERE key='physicalMemoryBytes'",
            "UPDATE environment_facts SET text_value=CAST(x'ff' AS TEXT) WHERE key='osVersion'",
            "DELETE FROM environment_facts WHERE key='lowPowerMode'", "UPDATE incident_breadcrumbs SET elapsed='01'", "UPDATE incident_breadcrumbs SET private=1")) {
            try (PersistenceFixture fixture = new PersistenceFixture()) {
                IncidentSnapshot snapshot = context(); IncidentPersistenceOptions options = new IncidentPersistenceOptions();
                try (IncidentDatabase store = new IncidentDatabase(file(fixture), options, 0)) { store.save(snapshot, 0); }
                try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, 0)) { raw.execSQL(sql); }
                try (IncidentDatabase store = new IncidentDatabase(file(fixture), options, 1)) { expect(PersistenceStatus.Error.INVALID_RECORD, () -> store.read(snapshot.id)); }
            }
        }
    }
    @Test public void unsupportedAndCorruptFilesAreNotReset() throws Exception {
        for (int revision : new int[] {0, 4}) {
            try (PersistenceFixture fixture = new PersistenceFixture()) {
                IncidentPersistenceOptions options = new IncidentPersistenceOptions();
                if (revision == 4) { try (IncidentDatabase ignored = new IncidentDatabase(file(fixture), options, 0)) { assertTrue(ignored.summaries().isEmpty()); } }
                try (SQLiteDatabase raw = SQLiteDatabase.openOrCreateDatabase(file(fixture), null)) {
                    raw.execSQL(revision == 0 ? "CREATE TABLE other_data(value TEXT)" : "PRAGMA user_version=5");
                }
                byte[] before = read(file(fixture));
                expect(PersistenceStatus.Error.UNSUPPORTED_SCHEMA, () -> new IncidentDatabase(file(fixture), options, 1));
                assertArrayEquals(before, read(file(fixture)));
            }
        }
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            byte[] bytes = "synthetic corrupt incident file".getBytes(StandardCharsets.UTF_8); write(file(fixture), bytes);
            expect(PersistenceStatus.Error.CORRUPT, () -> new IncidentDatabase(file(fixture), new IncidentPersistenceOptions(), 0));
            assertArrayEquals(bytes, read(file(fixture)));
        }
    }
    @Test public void fullDatabaseRollsBackPruningAndRemainsDeletable() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            IncidentPersistenceOptions options = new IncidentPersistenceOptions(2, 604800000, 2097152, 1048576, 524288);
            try (IncidentDatabase store = new IncidentDatabase(file(fixture), options, 0)) {
                IncidentSnapshot first = context(), second = context(64, repeat('x', 4095)), large = context(250, repeat('x', 4095));
                store.save(first, 0); store.save(second, 1);
                expect(PersistenceStatus.Error.STORAGE_FULL, () -> store.save(large, 2));
                assertEquals(2, store.summaries().size()); assertEquals(second.id, store.summaries().get(0).id);
                assertEquals(first.textByteCount, store.read(first.id).textByteCount);
                store.delete(second.id); store.save(context(), 3); assertEquals(2, store.summaries().size());
            }
        }
    }
}
