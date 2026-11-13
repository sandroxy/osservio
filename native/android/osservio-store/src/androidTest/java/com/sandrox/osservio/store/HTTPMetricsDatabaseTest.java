package com.sandrox.osservio.store;

import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.sandrox.osservio.core.*;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import static com.sandrox.osservio.store.PersistenceFixture.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class HTTPMetricsDatabaseTest {
    @Test public void reopenKeepsRepeatedMissingAndOverlappingPhasesAndRejectsCorruption() throws Exception {
        for (String corrupt : new String[] {"DELETE FROM incident_http_metrics", "DELETE FROM incident_http_phases WHERE position=2", "UPDATE incident_http_phases SET ended=-1 WHERE position=0"}) {
            try (PersistenceFixture fixture = new PersistenceFixture()) {
                MemoryHTTPStore history = new MemoryHTTPStore(); CaptureController capture = new CaptureController(); capture.startDetailed();
                HTTPRecorder recorder = new HTTPRecorder(history, capture, new HTTPPrivacyPolicy());
                HTTPRecorder.Token token = recorder.begin("GET", "https://example.com", Collections.emptyList(), HTTPEvent.Source.HOST).token;
                HTTPMetrics metrics = new HTTPMetrics(Arrays.asList(new HTTPMetrics.Timing(HTTPMetrics.Phase.CONNECT, 0, 7L),
                    new HTTPMetrics.Timing(HTTPMetrics.Phase.TLS, 2, 7L), new HTTPMetrics.Timing(HTTPMetrics.Phase.CONNECT, 10, null)), "h2", HTTPMetrics.Cache.NETWORK, false, false);
                recorder.finish(token, HTTPEvent.State.FAILED, HTTPEvent.Failure.CONNECTION, true, false, metrics);
                IncidentSnapshot snapshot = IncidentSnapshot.capture(null, null, null, null, IncidentHTTP.capture(history.snapshot(), 20, history, false), null);
                File file = new File(fixture.directory, "incidents.sqlite");
                try (IncidentDatabase writer = new IncidentDatabase(file, new IncidentPersistenceOptions(), 0)) { writer.save(snapshot, 0); }
                history.clear();
                try (IncidentDatabase reader = new IncidentDatabase(file, new IncidentPersistenceOptions(), 1)) {
                    IncidentSnapshot saved = reader.read(snapshot.id); HTTPMetrics value = saved.http.events.get(0).metrics;
                    assertEquals(3, value.phases.size()); assertNull(value.phases.get(2).endMillis); assertEquals("h2", value.protocolName);
                    assertEquals(snapshot.textByteCount, saved.textByteCount);
                }
                try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) { raw.execSQL(corrupt); }
                try (IncidentDatabase reader = new IncidentDatabase(file, new IncidentPersistenceOptions(), 2)) {
                    expect(PersistenceStatus.Error.INVALID_RECORD, () -> reader.read(snapshot.id));
                }
            }
        }
    }
    @Test public void revisionEightMigrationPreservesHistoryAndRollsBackOnConflict() throws Exception {
        for (boolean conflict : new boolean[] {false, true}) try (PersistenceFixture fixture = new PersistenceFixture()) {
            File file = new File(fixture.directory, "legacy.sqlite");
            try (SQLiteDatabase raw = SQLiteDatabase.openOrCreateDatabase(file, null)) {
                for (int revision = 1; revision <= 8; revision++)
                    for (String sql : IncidentDetailFixture.shared("incident-v" + revision + ".sql").split(";")) if (!sql.trim().isEmpty()) raw.execSQL(sql);
                if (conflict) raw.execSQL("CREATE TABLE incident_http_metrics (collision INTEGER)");
            }
            if (conflict) expect(PersistenceStatus.Error.UNAVAILABLE, () -> new IncidentDatabase(file, new IncidentPersistenceOptions(), 2000));
            else try (IncidentDatabase store = new IncidentDatabase(file, new IncidentPersistenceOptions(), 2000)) {
                assertEquals(4, store.summaries().size());
                for (IncidentSummary summary : store.summaries()) {
                    IncidentSnapshot value = store.read(summary.id);
                    if (value.http != null) for (HTTPEvent event : value.http.events) assertNull(event.metrics);
                }
            }
            try (SQLiteDatabase check = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                assertEquals(conflict ? 8 : 9, EvidenceSQLite.scalar(check, "PRAGMA user_version"));
            }
        }
    }
}
