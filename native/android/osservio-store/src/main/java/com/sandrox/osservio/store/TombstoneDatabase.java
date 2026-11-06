package com.sandrox.osservio.store;

import static com.sandrox.osservio.store.EvidenceSQLite.integer;
import static com.sandrox.osservio.store.EvidenceSQLite.text;
import static com.sandrox.osservio.store.EvidenceSQLite.textColumn;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.sandrox.osservio.core.AndroidTombstone;
import java.util.ArrayList;
import java.util.List;

/** Worker-owned reviewed Android address evidence, within the enclosing incident transaction. */
final class TombstoneDatabase {
    private final SQLiteDatabase database;
    TombstoneDatabase(SQLiteDatabase database) { this.database = database; }
    static final String[] SCHEMA = {
        "ALTER TABLE incident_diagnostics ADD COLUMN tombstone_state TEXT",
        "CREATE TABLE android_tombstones (incident_id TEXT PRIMARY KEY NOT NULL REFERENCES incident_diagnostics(incident_id) ON DELETE CASCADE, "
            + "architecture INTEGER NOT NULL, signal_number INTEGER NOT NULL, signal_code INTEGER NOT NULL, thread_id INTEGER NOT NULL, "
            + "fault_address TEXT, frame_count INTEGER NOT NULL)",
        "CREATE TABLE android_tombstone_frames (incident_id TEXT NOT NULL REFERENCES android_tombstones(incident_id) ON DELETE CASCADE, "
            + "position INTEGER NOT NULL, pc TEXT NOT NULL, relative_pc TEXT NOT NULL, build_id TEXT, PRIMARY KEY(incident_id, position))"
    };
    void save(AndroidTombstone value, String id) {
        database.execSQL("INSERT INTO android_tombstones(incident_id,architecture,signal_number,signal_code,thread_id,fault_address,frame_count) VALUES (?,?,?,?,?,?,?)",
            new Object[] {id, value.architectureCode, value.signalNumber, value.signalCode, value.threadID, value.faultAddress, value.frames.size()});
        for (int index = 0; index < value.frames.size(); index++) {
            AndroidTombstone.Frame frame = value.frames.get(index);
            database.execSQL("INSERT INTO android_tombstone_frames(incident_id,position,pc,relative_pc,build_id) VALUES (?,?,?,?,?)",
                new Object[] {id, index, frame.programCounter, frame.relativeProgramCounter, frame.buildID});
        }
    }
    void requireAbsent(String id) {
        for (String table : new String[] {"android_tombstones", "android_tombstone_frames"}) {
            try (Cursor row = database.rawQuery("SELECT 1 FROM " + table + " WHERE incident_id=? LIMIT 1", new String[] {id})) {
                if (row.moveToFirst()) throw EvidenceStorageException.invalid();
            }
        }
    }
    AndroidTombstone read(String id) {
        String projection = "architecture,signal_number,signal_code,thread_id," + textColumn("fault_address", 20) + ",frame_count";
        try (Cursor row = database.rawQuery("SELECT " + projection + " FROM android_tombstones WHERE incident_id=? LIMIT 2", new String[] {id})) {
            if (!row.moveToFirst()) throw EvidenceStorageException.invalid();
            long count = integer(row, 5);
            if (count < 0 || count > 128) throw EvidenceStorageException.invalid();
            AndroidTombstone value;
            try {
                value = AndroidTombstone.restore(int32(row, 0), int32(row, 1), int32(row, 2), int32(row, 3),
                    row.isNull(4) ? null : text(row, 4, 20), readFrames(id, (int) count));
            } catch (IllegalArgumentException invalid) { throw EvidenceStorageException.invalid(); }
            if (row.moveToNext()) throw EvidenceStorageException.invalid();
            return value;
        }
    }
    private List<AndroidTombstone.Frame> readFrames(String id, int count) {
        List<AndroidTombstone.Frame> frames = new ArrayList<>();
        String projection = "position," + textColumn("pc", 20) + "," + textColumn("relative_pc", 20) + "," + textColumn("build_id", 128);
        try (Cursor row = database.rawQuery("SELECT " + projection + " FROM android_tombstone_frames WHERE incident_id=? ORDER BY position LIMIT 129", new String[] {id})) {
            while (row.moveToNext()) {
                if (frames.size() >= count || integer(row, 0) != frames.size()) throw EvidenceStorageException.invalid();
                frames.add(AndroidTombstone.Frame.restore(text(row, 1, 20), text(row, 2, 20), row.isNull(3) ? null : text(row, 3, 128)));
            }
        }
        if (frames.size() != count) throw EvidenceStorageException.invalid();
        return frames;
    }
    private static int int32(Cursor row, int index) {
        long value = integer(row, index);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw EvidenceStorageException.invalid();
        return (int) value;
    }
}
