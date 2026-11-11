package com.sandrox.osservio.store;

import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.sandrox.osservio.core.EnvironmentSnapshot;
import com.sandrox.osservio.core.HTTPEvent;
import com.sandrox.osservio.core.IncidentHTTP;
import com.sandrox.osservio.core.IncidentLogs;
import com.sandrox.osservio.core.IncidentPayload;
import com.sandrox.osservio.core.IncidentSnapshot;
import com.sandrox.osservio.core.IncidentSummary;
import com.sandrox.osservio.core.IncidentWebSockets;
import com.sandrox.osservio.core.LogEvent;
import com.sandrox.osservio.core.LogLevel;
import com.sandrox.osservio.core.LogMessage;
import com.sandrox.osservio.core.PersistenceStatus;
import com.sandrox.osservio.core.WebSocketEvent;
import java.io.File;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import static com.sandrox.osservio.store.IncidentDetailFixture.id;
import static com.sandrox.osservio.store.PersistenceFixture.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class IncidentDetailDatabaseTest {
    private static final String[] TABLES = {"incident_windows", "incident_logs", "incident_http", "incident_websocket", "incident_headers", "incident_payloads"};
    private static final String LEGACY_ID = "12345678-1234-1234-1234-123456789abc";
    private static File file(PersistenceFixture fixture) { return new File(fixture.directory, "incidents.sqlite"); }
    @Test public void roundTripPreservesPrivacyPayloadStatesAndIndependentTimes() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            IncidentPersistenceOptions options = new IncidentPersistenceOptions(); IncidentSnapshot snapshot = IncidentDetailFixture.snapshot();
            try (IncidentDatabase writer = new IncidentDatabase(file(fixture), options, 0)) { writer.save(snapshot, 0); }
            try (IncidentDatabase reader = new IncidentDatabase(file(fixture), options, 1)) {
                IncidentSnapshot restored = reader.read(snapshot.id); assertNotNull(restored);
                assertEquals(snapshot.textByteCount, restored.textByteCount); assertEquals("[private]", restored.note.text);
                IncidentLogs logs = restored.logs; IncidentHTTP http = restored.http; IncidentWebSockets sockets = restored.webSockets;
                assertNotNull(logs); assertNotNull(http); assertNotNull(sockets);
                assertEquals(-1, logs.observedAtMillis); assertEquals(-2, http.observedAtMillis); assertEquals(-3, sockets.observedAtMillis);
                assertEquals(Long.MAX_VALUE - 1, logs.events.get(0).sequence); assertEquals(Long.MAX_VALUE, logs.events.get(1).sequence);
                assertEquals(Long.MIN_VALUE, logs.events.get(0).timestampMillis); assertEquals("[private]", logs.events.get(0).message);
                assertTrue(logs.events.get(0).isPrivate); assertFalse(logs.events.get(0).isTruncated);
                assertEquals(IncidentDetailFixture.BODY, logs.events.get(1).message); assertFalse(logs.events.get(1).isPrivate); assertTrue(logs.events.get(1).isTruncated);
                assertEquals(9, logs.sourceEvictedCount); assertEquals(3, logs.omittedEventCount);
                HTTPEvent first = http.events.get(0);
                assertEquals(id(10), first.id); assertEquals(HTTPEvent.Source.OKHTTP, first.source); assertEquals(Long.valueOf(Long.MAX_VALUE), first.durationMillis);
                assertEquals(Long.MIN_VALUE, first.timestampMillis); assertEquals(2, first.revision); assertEquals(Integer.valueOf(201), first.statusCode);
                assertEquals("https://example.com/path?[redacted]", first.url); assertEquals("https://example.com/result", first.responseURL);
                assertEquals("[redacted]", first.requestHeaders.get(0).value); assertEquals("例\u0000", first.requestHeaders.get(1).value);
                assertEquals("text/plain", first.responseHeaders.get(0).value); assertTrue(first.headersLimited);
                assertEquals("", http.payloads.get(first.id + ".request").text); assertEquals(IncidentDetailFixture.BODY, http.payloads.get(first.id + ".response").text);
                IncidentPayload.State[] states = {IncidentPayload.State.NOT_SELECTED, IncidentPayload.State.UNAVAILABLE, IncidentPayload.State.BUSY, IncidentPayload.State.BUDGET_EXCEEDED};
                for (int i = 0; i < states.length; i++) assertEquals(states[i], http.payloads.get(id(11 + i) + ".response").state);
                assertEquals(11, http.sourceEvictedEvents); assertEquals(13, http.sourceEvictedBodies); assertEquals(2, http.omittedEventCount);
                assertEquals(Integer.valueOf(101), sockets.events.get(0).statusCode); assertEquals("[redacted]", sockets.events.get(0).headers.get(0).value);
                assertEquals(WebSocketEvent.Outcome.SUBMITTED, sockets.events.get(1).outcome); assertEquals(sockets.events.get(1).id, sockets.events.get(2).relatedMessageId);
                assertEquals(WebSocketEvent.Outcome.SENT, sockets.events.get(2).outcome); assertNull(sockets.events.get(2).payload);
                assertEquals(Long.valueOf(Long.MAX_VALUE), sockets.events.get(3).payload.byteCount); assertNull(sockets.events.get(4).payload.byteCount);
                assertEquals("", sockets.payloads.get(id(21)).text); assertEquals("例", sockets.payloads.get(id(24)).text);
                assertEquals(17, sockets.sourceEvictedEvents); assertEquals(19, sockets.sourceEvictedPayloads); assertEquals(3, sockets.omittedEventCount);
                IncidentSummary summary = reader.summaries().get(0);
                assertEquals(Integer.valueOf(2), summary.logCount); assertEquals(Integer.valueOf(5), summary.httpCount); assertEquals(Integer.valueOf(5), summary.webSocketCount);
            }
        }
    }
    @Test public void absentAndEmptyGroupsRemainDistinctAndOptionalColumnsAreTolerated() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            IncidentPersistenceOptions options = new IncidentPersistenceOptions(); IncidentSnapshot snapshot = IncidentDetailFixture.snapshot();
            IncidentSnapshot absent = IncidentSnapshot.capture(null, null, null);
            IncidentSnapshot empty = IncidentSnapshot.capture(null, null, null,
                IncidentLogs.restore(Collections.emptyList(), 1, 3, 4),
                IncidentHTTP.restore(Collections.emptyList(), Collections.emptyMap(), 2, 5, 6, 7),
                IncidentWebSockets.restore(Collections.emptyList(), Collections.emptyMap(), 3, 8, 9, 10));
            try (IncidentDatabase writer = new IncidentDatabase(file(fixture), options, 0)) {
                for (IncidentSnapshot value : Arrays.asList(snapshot, absent, empty)) writer.save(value, 0);
            }
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, 0)) {
                for (String table : TABLES) raw.execSQL("ALTER TABLE " + table + " ADD COLUMN future_optional TEXT");
            }
            try (IncidentDatabase reader = new IncidentDatabase(file(fixture), options, 1)) {
                IncidentSnapshot absentValue = reader.read(absent.id), emptyValue = reader.read(empty.id);
                assertNull(absentValue.logs); assertNull(absentValue.http); assertNull(absentValue.webSockets);
                assertTrue(emptyValue.logs.events.isEmpty()); assertTrue(emptyValue.http.events.isEmpty()); assertTrue(emptyValue.webSockets.events.isEmpty());
                assertEquals(7, emptyValue.http.sourceEvictedBodies);
                List<IncidentSummary> summaries = reader.summaries(); assertEquals(Integer.valueOf(0), summaries.get(0).httpCount); assertNull(summaries.get(1).httpCount);
                assertEquals(snapshot.textByteCount, reader.read(snapshot.id).textByteCount); reader.save(IncidentDetailFixture.snapshot(), 1);
            }
        }
    }
    @Test public void sharedCorruptionCasesFailWithoutRepairingEvidence() throws Exception {
        for (String line : IncidentDetailFixture.shared("incident-detail-corruption.tsv").split("\n")) {
            String[] row = line.split("\t"); assertEquals(2, row.length);
            try (PersistenceFixture fixture = new PersistenceFixture()) {
                IncidentSnapshot snapshot = IncidentDetailFixture.snapshot(); IncidentPersistenceOptions options = new IncidentPersistenceOptions();
                try (IncidentDatabase writer = new IncidentDatabase(file(fixture), options, 0)) { writer.save(snapshot, 0); }
                try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, 0)) { raw.execSQL(row[1]); }
                byte[] before = read(file(fixture));
                try (IncidentDatabase reader = new IncidentDatabase(file(fixture), options, 1)) {
                    expect(PersistenceStatus.Error.INVALID_RECORD, () -> reader.read(snapshot.id));
                }
                assertArrayEquals(row[0], before, read(file(fixture)));
            }
        }
    }
    private static void legacy(File file) throws Exception {
        try (SQLiteDatabase raw = SQLiteDatabase.openOrCreateDatabase(file, null)) {
            for (String sql : IncidentDetailFixture.shared("incident-v1.sql").split(";")) if (!sql.trim().isEmpty()) raw.execSQL(sql);
        }
    }
    @Test public void migrationPreservesLegacyEvidenceAndAllowsNewAttachments() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            legacy(file(fixture));
            try (IncidentDatabase store = new IncidentDatabase(file(fixture), new IncidentPersistenceOptions(), 101)) {
                IncidentSnapshot restored = store.read(LEGACY_ID); assertNotNull(restored);
                assertEquals(LEGACY_ID, restored.id); assertEquals(-17, restored.timestampMillis); assertEquals("legacy", restored.note.text);
                assertEquals(-23, restored.environment.timestampMillis); assertEquals(EnvironmentSnapshot.Platform.ANDROID, restored.environment.platform);
                assertEquals("com.example.fixture", restored.environment.values.get(EnvironmentSnapshot.Key.APP_IDENTIFIER).fact.text);
                assertEquals(Long.MAX_VALUE, restored.breadcrumbs.events.get(0).sequence); assertEquals(Long.MIN_VALUE, restored.breadcrumbs.events.get(0).timestampMillis);
                assertEquals("[private]", restored.breadcrumbs.events.get(0).message); assertEquals(7, restored.breadcrumbs.sourceEvictedCount);
                assertNull(restored.logs); assertNull(restored.http); assertNull(restored.webSockets); assertNull(store.summaries().get(0).logCount);
                IncidentSnapshot added = IncidentDetailFixture.snapshot(); store.save(added, 102);
                assertEquals(added.textByteCount, store.read(added.id).textByteCount);
            }
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                assertEquals(7, EvidenceSQLite.scalar(raw, "PRAGMA user_version"));
                assertEquals(100, EvidenceSQLite.scalar(raw, "SELECT saved_at FROM incidents WHERE id=?", LEGACY_ID));
            }
        }
    }
    @Test public void failedMigrationRollsBackAddedTablesAndRevision() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            legacy(file(fixture));
            try (SQLiteDatabase conflict = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, 0)) { conflict.execSQL("CREATE TABLE incident_http (unrelated TEXT)"); }
            expect(PersistenceStatus.Error.UNAVAILABLE, () -> new IncidentDatabase(file(fixture), new IncidentPersistenceOptions(), 101));
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                assertEquals(1, EvidenceSQLite.scalar(raw, "PRAGMA user_version"));
                assertEquals(0, EvidenceSQLite.scalar(raw, "SELECT count(*) FROM sqlite_master WHERE name IN ('incident_windows', 'incident_logs')"));
                assertEquals(1, EvidenceSQLite.scalar(raw, "SELECT count(*) FROM incidents WHERE id=? AND note='legacy' AND text_bytes=40", LEGACY_ID));
            }
        }
    }
    @Test public void capacityRejectionAndRetentionDeleteCompleteAttachments() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            IncidentSnapshot first = IncidentDetailFixture.snapshot(), second = IncidentDetailFixture.snapshot();
            IncidentPersistenceOptions options = new IncidentPersistenceOptions(1, 604800000, first.textByteCount, 16777216, 524288);
            try (IncidentDatabase store = new IncidentDatabase(file(fixture), options, 0)) {
                store.save(first, 0);
                char[] large = new char[4096]; Arrays.fill(large, 'x');
                IncidentSnapshot oversized = IncidentSnapshot.capture(LogMessage.publicText(new String(large)), null, null, null, first.http, null);
                expect(PersistenceStatus.Error.INVALID_RECORD, () -> store.save(oversized, 1));
                assertEquals(first.textByteCount, store.read(first.id).textByteCount);
                store.save(second, 2); assertNull(store.read(first.id)); assertEquals(second.textByteCount, store.read(second.id).textByteCount);
                store.delete(second.id);
            }
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file(fixture).getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                for (String table : TABLES) assertEquals(0, EvidenceSQLite.scalar(raw, "SELECT count(*) FROM " + table));
            }
        }
    }
    @Test public void diskFullWhileSavingAttachmentsRollsBackPruningAndPartialRows() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            IncidentSnapshot first = IncidentDetailFixture.snapshot(), second = IncidentDetailFixture.snapshot();
            IncidentPersistenceOptions options = new IncidentPersistenceOptions(2, 604800000, 2097152, 1048576, 524288);
            try (IncidentDatabase store = new IncidentDatabase(file(fixture), options, 0)) {
                store.save(first, 0); store.save(second, 1);
                char[] characters = new char[65536]; Arrays.fill(characters, 'x'); String text = new String(characters);
                List<LogEvent> events = new ArrayList<>();
                for (int i = 0; i < 12; i++) events.add(LogEvent.restore(id(1), i + 1, 0, LogLevel.INFO, "fixture", text, false, false));
                IncidentLogs logs = IncidentLogs.restore(events, 0, 12, 0);
                List<HTTPEvent> http = new ArrayList<>(); Map<String, IncidentPayload> payloads = new LinkedHashMap<>();
                for (int i = 0; i < 4; i++) {
                    String eventId = id(i + 30), blobId = eventId + ".response";
                    http.add(HTTPEvent.restore(eventId, id(1), i + 1, 2, 0, "GET", HTTPEvent.Source.HOST, "https://example.com", null,
                        Collections.emptyList(), Collections.emptyList(), false, 200, HTTPEvent.State.COMPLETED, null, null,
                        HTTPEvent.BodyReference.restore(HTTPEvent.BodyState.OMITTED, null, 0), HTTPEvent.BodyReference.restore(HTTPEvent.BodyState.AVAILABLE, blobId, 65536)));
                    payloads.put(blobId, IncidentPayload.restore(blobId, IncidentPayload.State.AVAILABLE, text));
                }
                IncidentSnapshot large = IncidentSnapshot.capture(null, null, null, logs, IncidentHTTP.restore(http, payloads, 0, 4, 0, 0), null);
                expect(PersistenceStatus.Error.STORAGE_FULL, () -> store.save(large, 2));
                List<IncidentSummary> summaries = store.summaries(); assertEquals(2, summaries.size());
                assertEquals(second.id, summaries.get(0).id); assertEquals(first.id, summaries.get(1).id); assertNull(store.read(large.id));
                assertEquals(first.textByteCount, store.read(first.id).textByteCount);
                IncidentSnapshot next = IncidentDetailFixture.snapshot(); store.save(next, 3); assertEquals(next.textByteCount, store.read(next.id).textByteCount);
            }
        }
    }
}
