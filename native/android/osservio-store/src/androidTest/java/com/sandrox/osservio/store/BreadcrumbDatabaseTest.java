package com.sandrox.osservio.store;

import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.sandrox.osservio.core.BreadcrumbEvent;
import com.sandrox.osservio.core.PersistenceStatus;
import com.sandrox.osservio.core.RetainedBreadcrumbs;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import static com.sandrox.osservio.store.PersistenceFixture.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class BreadcrumbDatabaseTest {
    @Test public void reopenPreservesIdentityUnicodeNulAndNoEmptyRun() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            String id = UUID.randomUUID().toString(); List<BreadcrumbEvent> original = events(2, "public\u0000例");
            try (BreadcrumbDatabase writer = fixture.database(id)) {
                assertTrue(writer.runs().isEmpty()); writer.write(original, 1001);
            }
            try (BreadcrumbDatabase reader = fixture.database(UUID.randomUUID().toString())) {
                RetainedBreadcrumbs restored = reader.read(id);
                assertNotNull(restored); assertFalse(restored.run.isCurrent);
                assertEquals(ids(original), ids(restored.events));
                assertEquals(original.get(0).message, restored.events.get(0).message);
                assertEquals(original.get(0).sessionId, restored.events.get(0).sessionId);
                assertEquals(Long.valueOf(Long.MAX_VALUE), restored.events.get(0).sessionElapsedMillis);
                assertEquals(1, reader.runs().size());
            }
        }
    }
    @Test public void countAndTextBudgetsEvictOldestAndCanBeLowered() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            String id = UUID.randomUUID().toString();
            BreadcrumbPersistenceOptions options = new BreadcrumbPersistenceOptions(3, 64, 4, 604800000, 4194304, 64, 131072, 1000);
            try (BreadcrumbDatabase writer = fixture.database(id, options, 1000)) {
                writer.write(events(5, "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"), 1001);
                RetainedBreadcrumbs result = writer.read(id);
                assertEquals(2, result.events.size()); assertEquals(4, result.events.get(0).sequence); assertEquals(3, result.run.evictedEvents);
            }
            BreadcrumbPersistenceOptions smaller = new BreadcrumbPersistenceOptions(1, 64, 4, 604800000, 4194304, 64, 131072, 1000);
            try (BreadcrumbDatabase reader = fixture.database(UUID.randomUUID().toString(), smaller, 1002)) {
                assertEquals(5, reader.read(id).events.get(0).sequence); assertEquals(4, reader.read(id).run.evictedEvents);
            }
        }
    }
    @Test public void runCountAndAgeRetentionDoNotInventExitCauses() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            BreadcrumbPersistenceOptions options = new BreadcrumbPersistenceOptions(200, 65536, 2, 60000, 4194304, 64, 131072, 1000);
            List<String> runIds = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                String id = UUID.randomUUID().toString(); runIds.add(id);
                try (BreadcrumbDatabase writer = fixture.database(id, options, i)) { writer.write(events(1, "fixture"), i); }
            }
            try (BreadcrumbDatabase reader = fixture.database(UUID.randomUUID().toString(), options, 3)) {
                assertEquals(2, reader.runs().size()); assertEquals(runIds.get(2), reader.runs().get(0).id); assertNull(reader.read(runIds.get(0)));
            }
            try (BreadcrumbDatabase expired = fixture.database(UUID.randomUUID().toString(), options, 60003)) { assertTrue(expired.runs().isEmpty()); }
        }
    }
    @Test public void invalidBatchRollsBackWithoutLosingPriorCommit() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            String id = UUID.randomUUID().toString();
            try (BreadcrumbDatabase writer = fixture.database(id)) {
                List<BreadcrumbEvent> initial = events(1, "first"); writer.write(initial, 1000);
                List<BreadcrumbEvent> next = events(1, "next"); next.addAll(initial);
                expect(PersistenceStatus.Error.INVALID_RECORD, () -> writer.write(next, 1001));
                assertEquals(ids(initial), ids(writer.read(id).events));
                expect(PersistenceStatus.Error.INVALID_RECORD, () -> writer.write(events(1, "live", BreadcrumbEvent.Retention.MEMORY_ONLY), 1001));
                assertEquals(ids(initial), ids(writer.read(id).events));
            }
        }
    }
    @Test public void unknownAndNewerSchemasAreNeverReset() throws Exception {
        for (boolean newer : Arrays.asList(false, true)) {
            try (PersistenceFixture fixture = new PersistenceFixture()) {
                if (newer) { try (BreadcrumbDatabase ignored = fixture.database(UUID.randomUUID().toString())) { assertTrue(ignored.runs().isEmpty()); } }
                try (SQLiteDatabase raw = raw(fixture)) {
                    if (newer) raw.execSQL("PRAGMA user_version=2");
                    else { raw.execSQL("CREATE TABLE other_data(value TEXT)"); raw.execSQL("INSERT INTO other_data VALUES ('keep')"); }
                }
                byte[] before = read(fixture.file);
                expect(PersistenceStatus.Error.UNSUPPORTED_SCHEMA, () -> fixture.database(UUID.randomUUID().toString()));
                assertArrayEquals(before, read(fixture.file));
            }
        }
    }
    @Test public void optionalColumnsAreToleratedButInvalidTextAndPrivacyAreRejected() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            String id = UUID.randomUUID().toString();
            try (BreadcrumbDatabase writer = fixture.database(id)) { writer.write(events(1, "public"), 1000); }
            try (SQLiteDatabase raw = raw(fixture)) { raw.execSQL("ALTER TABLE breadcrumbs ADD COLUMN optional_field TEXT"); }
            try (BreadcrumbDatabase reader = fixture.database(UUID.randomUUID().toString())) { assertEquals(1, reader.read(id).events.size()); }
            for (String mutation : Arrays.asList("private=1", "private=0, message=CAST(x'ff' AS TEXT)")) {
                try (SQLiteDatabase raw = raw(fixture)) { raw.execSQL("UPDATE breadcrumbs SET " + mutation); }
                try (BreadcrumbDatabase reader = fixture.database(UUID.randomUUID().toString())) {
                    expect(PersistenceStatus.Error.INVALID_RECORD, () -> reader.read(id));
                }
            }
        }
    }
    @Test public void fullDatabasePreservesLastSuccessfulTransaction() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            String id = UUID.randomUUID().toString();
            BreadcrumbPersistenceOptions options = new BreadcrumbPersistenceOptions(2000, 1048576, 4, 604800000, 1048576, 64, 1048576, 1000);
            char[] chars = new char[4095]; Arrays.fill(chars, 'x'); String large = new String(chars);
            try (BreadcrumbDatabase writer = fixture.database(id, options, 1000)) {
                List<String> before = new ArrayList<>(); boolean full = false;
                for (int i = 0; i < 8; i++) {
                    try { writer.write(events(64, large), 1000); }
                    catch (RuntimeException failure) {
                        assertEquals(failure.toString(), PersistenceStatus.Error.STORAGE_FULL, EvidenceStorageException.category(failure));
                        assertEquals(before, ids(writer.read(id).events)); full = true; break;
                    }
                    before = ids(writer.read(id).events);
                }
                assertTrue(full); assertTrue(fixture.file.length() <= options.databaseByteLimit);
            }
        }
    }
    @Test public void malformedFileIsPreserved() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            byte[] invalid = "synthetic damaged database".getBytes(StandardCharsets.UTF_8); write(fixture.file, invalid);
            expect(PersistenceStatus.Error.CORRUPT, () -> fixture.database(UUID.randomUUID().toString()));
            assertArrayEquals(invalid, read(fixture.file));
        }
    }
    private SQLiteDatabase raw(PersistenceFixture fixture) {
        SQLiteDatabase raw = SQLiteDatabase.openDatabase(fixture.file.getAbsolutePath(), null, SQLiteDatabase.CREATE_IF_NECESSARY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
        raw.disableWriteAheadLogging(); return raw;
    }
}
