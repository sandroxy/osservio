package com.sandrox.osservio.store;

import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.sandrox.osservio.core.*;
import java.io.File;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import static com.sandrox.osservio.store.PersistenceFixture.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class NetworkErrorDatabaseTest {
    @Test public void frozenDetailsSurviveReopenAndCorruptionIsRejected() throws Exception {
        String[] corruptions = {"UPDATE incident_http SET error_limited=NULL", "UPDATE incident_http SET error_processed=2",
            "UPDATE incident_http SET error_code='" + String.join("", Collections.nCopies(65, "x")) + "'",
            "UPDATE incident_http SET error_message=CAST(X'FF' AS TEXT)",
            "UPDATE incident_websocket SET close_reason_processed=NULL WHERE close_reason IS NOT NULL",
            "UPDATE incident_websocket SET close_reason='" + String.join("", Collections.nCopies(513, "x")) + "' WHERE close_reason IS NOT NULL"};
        for (String sql : corruptions) try (PersistenceFixture fixture = new PersistenceFixture()) {
            CaptureController capture = new CaptureController(); capture.startDetailed();
            MemoryHTTPStore http = new MemoryHTTPStore(); MemoryWebSocketStore sockets = new MemoryWebSocketStore();
            HTTPRecorder requests = new HTTPRecorder(http, capture, new HTTPPrivacyPolicy());
            HTTPRecorder.Token token = requests.begin("GET", "https://example.com", Collections.emptyList()).token;
            requests.finish(token, HTTPEvent.State.FAILED, HTTPEvent.Failure.CONNECTION, true, false, null,
                () -> new NetworkError.Input("fixture", "-1009", "Failure", "连接失败😀"));
            WebSocketRecorder connections = new WebSocketRecorder(sockets, capture, new WebSocketPrivacyPolicy());
            WebSocketRecorder.Token connection = connections.begin("wss://example.com", Collections.emptyList(), WebSocketEvent.Source.HOST).token;
            connections.lifecycle(connection, WebSocketEvent.Kind.FAILED, Collections.emptyList(), null, 1000, WebSocketEvent.Failure.TRANSPORT,
                () -> new NetworkError.Input(null, null, null, "failed after close"), () -> "normal close");
            IncidentSnapshot snapshot = IncidentSnapshot.capture(null, null, null, null,
                IncidentHTTP.capture(http.snapshot(), 20, http, false), IncidentWebSockets.capture(sockets.snapshot(), 21, sockets, false));
            File file = new File(fixture.directory, "incidents.sqlite");
            try (IncidentDatabase writer = new IncidentDatabase(file, new IncidentPersistenceOptions(), 0)) { writer.save(snapshot, 0); }
            http.clear(); sockets.clear();
            try (IncidentDatabase reader = new IncidentDatabase(file, new IncidentPersistenceOptions(), 1)) {
                IncidentSnapshot saved = reader.read(snapshot.id);
                assertEquals("连接失败😀", saved.http.events.get(0).error.message); assertEquals("-1009", saved.http.events.get(0).error.code);
                WebSocketEvent event = saved.webSockets.events.get(saved.webSockets.events.size() - 1);
                assertEquals("failed after close", event.error.message); assertEquals("normal close", event.closeReason.text);
                assertEquals(Integer.valueOf(1000), event.closeCode); assertEquals(snapshot.textByteCount, saved.textByteCount);
            }
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) { raw.execSQL(sql); }
            try (IncidentDatabase damaged = new IncidentDatabase(file, new IncidentPersistenceOptions(), 2)) {
                expect(PersistenceStatus.Error.INVALID_RECORD, () -> damaged.read(snapshot.id));
            }
        }
    }

    @Test public void revisionTenPreservesMissingFactsAndRollsBackOnConflict() throws Exception {
        for (boolean conflict : new boolean[] {false, true}) try (PersistenceFixture fixture = new PersistenceFixture()) {
            File file = new File(fixture.directory, "legacy.sqlite");
            try (SQLiteDatabase raw = SQLiteDatabase.openOrCreateDatabase(file, null)) {
                for (int revision = 1; revision <= 10; revision++)
                    for (String sql : IncidentDetailFixture.shared("incident-v" + revision + ".sql").split(";")) if (!sql.trim().isEmpty()) raw.execSQL(sql);
                if (conflict) raw.execSQL("ALTER TABLE incident_websocket ADD COLUMN close_reason TEXT");
            }
            if (conflict) expect(PersistenceStatus.Error.UNAVAILABLE, () -> new IncidentDatabase(file, new IncidentPersistenceOptions(), 2000));
            else try (IncidentDatabase store = new IncidentDatabase(file, new IncidentPersistenceOptions(), 2000)) {
                assertEquals(4, store.summaries().size());
                for (IncidentSummary summary : store.summaries()) {
                    IncidentSnapshot value = store.read(summary.id);
                    if (value.http != null) for (HTTPEvent event : value.http.events) assertNull(event.error);
                    if (value.webSockets != null) for (WebSocketEvent event : value.webSockets.events) { assertNull(event.error); assertNull(event.closeReason); }
                }
            }
            try (SQLiteDatabase check = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                assertEquals(conflict ? 10 : 12, EvidenceSQLite.scalar(check, "PRAGMA user_version"));
                if (conflict) assertThrows(android.database.sqlite.SQLiteException.class, () -> check.rawQuery("SELECT error_domain FROM incident_http", null).close());
            }
        }
    }
}
