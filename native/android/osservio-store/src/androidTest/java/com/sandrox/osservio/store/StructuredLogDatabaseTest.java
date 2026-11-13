package com.sandrox.osservio.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.sandrox.osservio.core.*;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;
import static com.sandrox.osservio.store.PersistenceFixture.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class StructuredLogDatabaseTest {
    @Test public void detailsSurviveReopenAndInconsistentReferenceIsRejected() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            MemoryLogStore history = new MemoryLogStore(); LogRecorder logger = new LogRecorder(history); logger.start();
            logger.record(LogLevel.ERROR, "fixture", LogMessage.publicText("Request failed"), LogDetails.publicValues(new LogValue[0], new LogField[0],
                new LogError("Timeout", null, "transport", null, "Origin.run\nCaller.run"), null));
            IncidentLogs logs = IncidentLogs.capture(history.snapshot(), 3, 200, 65536, history, true, 65536);
            IncidentSnapshot snapshot = IncidentSnapshot.capture(null, null, null, logs, null, null);
            File file = new File(fixture.directory, "incidents.sqlite");
            try (IncidentDatabase writer = new IncidentDatabase(file, new IncidentPersistenceOptions(), 0)) { writer.save(snapshot, 0); }
            history.clear();
            try (IncidentDatabase reader = new IncidentDatabase(file, new IncidentPersistenceOptions(), 1)) {
                IncidentSnapshot saved = reader.read(snapshot.id); String id = logs.events.get(0).details.blobId;
                assertEquals(id, saved.logs.events.get(0).details.blobId);
                assertEquals(logs.payloads.get(id).text, saved.logs.payloads.get(id).text);
                assertEquals(logs.retainedPayloadBytes, saved.logs.retainedPayloadBytes); assertEquals(snapshot.textByteCount, saved.textByteCount);
            }
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
                raw.execSQL("UPDATE incident_logs SET details_bytes=details_bytes+1");
            }
            try (IncidentDatabase reader = new IncidentDatabase(file, new IncidentPersistenceOptions(), 2)) {
                expect(PersistenceStatus.Error.INVALID_RECORD, () -> reader.read(snapshot.id));
            }
        }
    }
    @Test public void revisionSevenMigrationPreservesHistoryAndRollsBackOnConflict() throws Exception {
        for (boolean conflict : new boolean[] {false, true}) try (PersistenceFixture fixture = new PersistenceFixture()) {
            File file = new File(fixture.directory, "legacy.sqlite");
            try (SQLiteDatabase raw = SQLiteDatabase.openOrCreateDatabase(file, null)) {
                for (int revision = 1; revision <= 7; revision++)
                    for (String sql : IncidentDetailFixture.shared("incident-v" + revision + ".sql").split(";")) if (!sql.trim().isEmpty()) raw.execSQL(sql);
                if (conflict) raw.execSQL("ALTER TABLE incident_logs ADD COLUMN details_blob TEXT");
            }
            if (conflict) expect(PersistenceStatus.Error.UNAVAILABLE, () -> new IncidentDatabase(file, new IncidentPersistenceOptions(), 2000));
            else try (IncidentDatabase store = new IncidentDatabase(file, new IncidentPersistenceOptions(), 2000)) {
                assertEquals(4, store.summaries().size()); for (IncidentSummary summary : store.summaries()) assertNotNull(store.read(summary.id));
                IncidentLogs old = store.read("12345678-1234-1234-1234-123456789abc").logs;
                assertEquals("[private]", old.events.get(0).message); assertNull(old.events.get(0).details); assertEquals(0, old.sourceEvictedDetails);
            }
            try (SQLiteDatabase check = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                assertEquals(conflict ? 7 : 8, EvidenceSQLite.scalar(check, "PRAGMA user_version"));
                boolean column = false;
                try (Cursor row = check.rawQuery("PRAGMA table_info(incident_logs)", null)) { while (row.moveToNext()) if (row.getString(1).equals("details_state")) column = true; }
                assertEquals(!conflict, column);
            }
        }
    }
}
