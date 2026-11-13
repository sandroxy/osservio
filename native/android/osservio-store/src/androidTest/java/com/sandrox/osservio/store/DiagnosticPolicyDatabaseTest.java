package com.sandrox.osservio.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.sandrox.osservio.core.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import static com.sandrox.osservio.store.PersistenceFixture.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class DiagnosticPolicyDatabaseTest {
    @Test public void processedBodiesAndCompleteMetadataSurviveReopen() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            CaptureController capture = new CaptureController(false, true, LogClock.SYSTEM);
            DiagnosticTextPolicy policy = input -> input.field == DiagnosticTextPolicy.Field.REQUEST_BODY ||
                input.field == DiagnosticTextPolicy.Field.WEB_SOCKET_MESSAGE ? "{\"reviewed\":true}" : input.text;
            MemoryHTTPStore http = new MemoryHTTPStore(); MemoryWebSocketStore sockets = new MemoryWebSocketStore();
            HTTPRecorder recorder = new HTTPRecorder(http, capture,
                new HTTPPrivacyPolicy(true, null, HTTPPrivacyPolicy.BodyPolicy.PUBLIC_UTF8, 16384, true, 4096, policy));
            String tokenValue = "Bearer " + String.join("", Collections.nCopies(1200, "a"));
            HTTPRecorder.Token token = recorder.begin("PROPFIND", "https://user:fixture@example.com?q=value#part",
                Arrays.asList(new HTTPHeader("Authorization", tokenValue), new HTTPHeader("Content-Type", "application/json"))).token;
            recorder.observeBody(token, HTTPRecorder.Direction.REQUEST, 2, count -> "{}".getBytes(StandardCharsets.UTF_8));
            recorder.finish(token, HTTPEvent.State.FAILED, HTTPEvent.Failure.CONNECTION, true, false);
            WebSocketRecorder socket = new WebSocketRecorder(sockets, capture,
                new WebSocketPrivacyPolicy(true, null, true, 4096, true, 4096, policy));
            WebSocketRecorder.Token connection = socket.begin("wss://example.com?token=fixture", Collections.emptyList(), WebSocketEvent.Source.HOST).token;
            socket.text(connection, WebSocketEvent.Direction.INCOMING, WebSocketEvent.Outcome.RECEIVED, "{}");
            IncidentSnapshot snapshot = IncidentSnapshot.capture(null, null, null, null,
                IncidentHTTP.capture(http.snapshot(), 1, http, true, 50, 65536, 65536),
                IncidentWebSockets.capture(sockets.snapshot(), 1, sockets, true, 200, 65536, 65536));
            File file = new File(fixture.directory, "incidents.sqlite");
            try (IncidentDatabase writer = new IncidentDatabase(file, new IncidentPersistenceOptions(), 1)) { writer.save(snapshot, 1); }
            try (IncidentDatabase reader = new IncidentDatabase(file, new IncidentPersistenceOptions(), 2)) {
                IncidentSnapshot saved = reader.read(snapshot.id);
                HTTPEvent event = saved.http.events.get(0);
                assertTrue(event.requestBody.isProcessed); assertEquals(2, event.requestBody.observedBytes);
                assertEquals(tokenValue, event.requestHeaders.get(0).value); assertEquals("PROPFIND", event.method);
                assertEquals(snapshot.http.events.get(0).url, event.url);
                assertEquals("{\"reviewed\":true}", saved.http.payloads.get(event.requestBody.blobId).text);
                WebSocketEvent.PayloadReference message = saved.webSockets.events.get(1).payload;
                assertTrue(message.isProcessed); assertEquals(Long.valueOf(2), message.byteCount);
                assertEquals(snapshot.textByteCount, saved.textByteCount);
            }
        }
    }

    @Test public void revisionSixMigrationPreservesEvidenceAndRollsBackPartialDDL() throws Exception {
        for (boolean conflict : new boolean[] {false, true}) try (PersistenceFixture fixture = new PersistenceFixture()) {
            File file = new File(fixture.directory, "legacy.sqlite");
            try (SQLiteDatabase raw = SQLiteDatabase.openOrCreateDatabase(file, null)) {
                for (int revision = 1; revision <= 6; revision++)
                    for (String sql : IncidentDetailFixture.shared("incident-v" + revision + ".sql").split(";")) if (!sql.trim().isEmpty()) raw.execSQL(sql);
                if (conflict) raw.execSQL("ALTER TABLE incident_http ADD COLUMN response_processed INTEGER NOT NULL DEFAULT 0");
            }
            if (conflict) expect(PersistenceStatus.Error.UNAVAILABLE, () -> new IncidentDatabase(file, new IncidentPersistenceOptions(), 2000));
            else try (IncidentDatabase store = new IncidentDatabase(file, new IncidentPersistenceOptions(), 2000)) {
                assertEquals(4, store.summaries().size());
                for (IncidentSummary summary : store.summaries()) assertNotNull(store.read(summary.id));
                assertEquals("[private]", store.read("12345678-1234-1234-1234-123456789abc").logs.events.get(0).message);
            }
            try (SQLiteDatabase check = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                assertEquals(conflict ? 6 : 9, EvidenceSQLite.scalar(check, "PRAGMA user_version"));
                boolean requestColumn = false;
                try (Cursor row = check.rawQuery("PRAGMA table_info(incident_http)", null)) {
                    while (row.moveToNext()) if (row.getString(1).equals("request_processed")) requestColumn = true;
                }
                assertEquals(!conflict, requestColumn);
            }
        }
    }
}
