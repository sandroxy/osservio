package com.sandrox.osservio.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.sandrox.osservio.core.*;
import java.io.File;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.sandrox.osservio.store.PersistenceFixture.*;

@RunWith(AndroidJUnit4.class)
public final class IncidentFocusTest {
    @Test public void exactIdentityRequiredAndPrivateEvidenceUnchanged() throws Exception {
        IncidentSnapshot snapshot = IncidentDetailFixture.snapshot();
        IncidentFocus[] focuses = {IncidentFocus.log(snapshot.logs.events.get(0)), IncidentFocus.http(snapshot.http.events.get(1)), IncidentFocus.webSocket(snapshot.webSockets.events.get(2))};
        for (IncidentFocus focus : focuses) {
            IncidentSnapshot selected = snapshot.withFocus(focus);
            assertEquals(snapshot.id, selected.id); assertEquals(snapshot.textByteCount, selected.textByteCount);
            assertEquals("[private]", selected.logs.events.get(0).message);
            assertThrows(IllegalArgumentException.class, () -> IncidentSnapshot.capture(null, null, null).withFocus(focus));
            assertThrows(IllegalArgumentException.class, () -> snapshot.withFocus(IncidentFocus.restore(focus.kind, focus.sessionId, 789, focus.eventId)));
            assertThrows(IllegalArgumentException.class, () -> snapshot.withFocus(IncidentFocus.restore(focus.kind, UUID.randomUUID().toString(), focus.sequence, focus.eventId)));
        }
    }
    @Test public void additiveMigrationRoundTripAndCascadePreserveLegacyRecord() throws Exception {
        try (PersistenceFixture fixture = new PersistenceFixture()) {
            File file = new File(fixture.directory, "incidents.sqlite"); IncidentPersistenceOptions options = new IncidentPersistenceOptions();
            IncidentSnapshot legacy = IncidentDetailFixture.snapshot();
            try (IncidentDatabase store = new IncidentDatabase(file, options, 0)) { store.save(legacy, 0); }
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
                raw.execSQL("DROP TABLE incident_focus"); raw.execSQL("PRAGMA user_version=11");
            }
            IncidentSnapshot base = IncidentDetailFixture.snapshot();
            IncidentSnapshot selected = base.withFocus(IncidentFocus.http(base.http.events.get(1)));
            try (IncidentDatabase writer = new IncidentDatabase(file, options, 1)) {
                assertNull(writer.read(legacy.id).focus); writer.save(selected, 1);
            }
            try (IncidentDatabase reader = new IncidentDatabase(file, options, 2)) {
                IncidentSnapshot restored = reader.read(selected.id);
                assertEquals(IncidentFocus.Kind.HTTP, restored.focus.kind); assertTrue(restored.focus.matches(restored.http.events.get(1)));
                assertEquals(IncidentPayload.State.NOT_SELECTED, restored.http.payloads.get(restored.http.events.get(1).responseBody.blobId).state);
                assertEquals(legacy.textByteCount, reader.read(legacy.id).textByteCount); reader.delete(selected.id);
            }
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
                 Cursor row = raw.rawQuery("SELECT count(*) FROM incident_focus", null)) {
                assertTrue(row.moveToFirst()); assertEquals(0, row.getInt(0)); assertEquals(12, raw.getVersion());
            }
        }
    }
    @Test public void corruptReferenceFailsWithoutReplacingEvidence() throws Exception {
        for (String update : new String[] {"kind='unknown'", "sequence=999", "event_id=NULL", "session_id='broken'"}) {
            try (PersistenceFixture fixture = new PersistenceFixture()) {
                File file = new File(fixture.directory, "incidents.sqlite"); IncidentPersistenceOptions options = new IncidentPersistenceOptions();
                IncidentSnapshot base = IncidentDetailFixture.snapshot(), selected = base.withFocus(IncidentFocus.http(base.http.events.get(0)));
                try (IncidentDatabase writer = new IncidentDatabase(file, options, 0)) { writer.save(selected, 0); }
                try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) { raw.execSQL("UPDATE incident_focus SET " + update); }
                try (IncidentDatabase reader = new IncidentDatabase(file, options, 1)) {
                    expect(PersistenceStatus.Error.INVALID_RECORD, () -> reader.read(selected.id)); assertEquals(1, reader.summaries().size());
                }
            }
        }
    }
}
