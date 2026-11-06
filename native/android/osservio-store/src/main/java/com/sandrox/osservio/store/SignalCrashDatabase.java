package com.sandrox.osservio.store;

import static com.sandrox.osservio.store.EvidenceSQLite.flag;
import static com.sandrox.osservio.store.EvidenceSQLite.integer;
import static com.sandrox.osservio.store.EvidenceSQLite.text;
import static com.sandrox.osservio.store.EvidenceSQLite.textColumn;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.sandrox.osservio.core.AppleSignalCrash;
import com.sandrox.osservio.core.SystemDiagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Worker-owned address evidence. The enclosing incident transaction owns writes and retention. */
final class SignalCrashDatabase {
    private final SQLiteDatabase database;
    SignalCrashDatabase(SQLiteDatabase database) { this.database = database; }
    static final String[] SCHEMA = {
        "CREATE TABLE signal_crashes (incident_id TEXT PRIMARY KEY NOT NULL REFERENCES incident_diagnostics(incident_id) ON DELETE CASCADE, "
            + "report_id TEXT NOT NULL, signal_name TEXT NOT NULL, signal_code TEXT NOT NULL, fault_address TEXT NOT NULL, "
            + "thread_number INTEGER, frame_count INTEGER NOT NULL, image_count INTEGER NOT NULL, image_cache_incomplete INTEGER NOT NULL)",
        "CREATE TABLE signal_crash_frames (incident_id TEXT NOT NULL REFERENCES signal_crashes(incident_id) ON DELETE CASCADE, "
            + "position INTEGER NOT NULL, address TEXT NOT NULL, PRIMARY KEY(incident_id, position))",
        "CREATE TABLE signal_crash_images (incident_id TEXT NOT NULL REFERENCES signal_crashes(incident_id) ON DELETE CASCADE, "
            + "position INTEGER NOT NULL, base_address TEXT NOT NULL, size TEXT NOT NULL, uuid TEXT, cpu_type TEXT, cpu_subtype TEXT, "
            + "PRIMARY KEY(incident_id, position))"
    };
    void save(AppleSignalCrash crash, String id) {
        database.execSQL("INSERT INTO signal_crashes(incident_id,report_id,signal_name,signal_code,fault_address,thread_number,frame_count,image_count,image_cache_incomplete) "
            + "VALUES (?,?,?,?,?,?,?,?,?)", new Object[] {id, crash.reportID, crash.signal.wireName, crash.code, crash.faultAddress,
                crash.threadNumber, crash.frames.size(), crash.images.size(), crash.imageCacheIncomplete ? 1 : 0});
        for (int index = 0; index < crash.frames.size(); index++) {
            database.execSQL("INSERT INTO signal_crash_frames(incident_id,position,address) VALUES (?,?,?)", new Object[] {id, index, crash.frames.get(index)});
        }
        for (int index = 0; index < crash.images.size(); index++) {
            AppleSignalCrash.Image image = crash.images.get(index);
            database.execSQL("INSERT INTO signal_crash_images(incident_id,position,base_address,size,uuid,cpu_type,cpu_subtype) VALUES (?,?,?,?,?,?,?)",
                new Object[] {id, index, image.baseAddress, image.size, image.uuid, image.cpuType, image.cpuSubtype});
        }
    }
    void requireAbsent(String id) {
        for (String table : new String[] {"signal_crashes", "signal_crash_frames", "signal_crash_images"}) {
            try (Cursor row = database.rawQuery("SELECT 1 FROM " + table + " WHERE incident_id=? LIMIT 1", new String[] {id})) {
                if (row.moveToFirst()) throw EvidenceStorageException.invalid();
            }
        }
    }
    AppleSignalCrash read(String id, long timestampMillis, Map<SystemDiagnostic.MetadataKey, SystemDiagnostic.Text> metadata) {
        String projection = textColumn("report_id", 36) + "," + textColumn("signal_name", 16) + "," + textColumn("signal_code", 16)
            + "," + textColumn("fault_address", 20) + ",thread_number,frame_count,image_count,image_cache_incomplete";
        try (Cursor row = database.rawQuery("SELECT " + projection + " FROM signal_crashes WHERE incident_id=? LIMIT 2", new String[] {id})) {
            if (!row.moveToFirst()) throw EvidenceStorageException.invalid();
            String signalName = text(row, 1, 16);
            AppleSignalCrash.Signal signal = null;
            for (AppleSignalCrash.Signal candidate : AppleSignalCrash.Signal.values()) if (candidate.wireName.equals(signalName)) signal = candidate;
            long frameCount = integer(row, 5), imageCount = integer(row, 6);
            if (signal == null || frameCount < 0 || frameCount > 128 || imageCount < 0 || imageCount > 512) throw EvidenceStorageException.invalid();
            AppleSignalCrash result;
            try {
                result = AppleSignalCrash.restore(text(row, 0, 36), timestampMillis, signal, text(row, 2, 16), text(row, 3, 20),
                    row.isNull(4) ? null : integer(row, 4), readFrames(id, (int) frameCount), readImages(id, (int) imageCount), metadata, flag(row, 7));
            } catch (IllegalArgumentException invalid) { throw EvidenceStorageException.invalid(); }
            if (row.moveToNext()) throw EvidenceStorageException.invalid();
            return result;
        }
    }
    private List<String> readFrames(String id, int expected) {
        List<String> frames = new ArrayList<>();
        try (Cursor row = database.rawQuery("SELECT position," + textColumn("address", 20)
            + " FROM signal_crash_frames WHERE incident_id=? ORDER BY position LIMIT 129", new String[] {id})) {
            while (row.moveToNext()) {
                if (frames.size() >= expected || integer(row, 0) != frames.size()) throw EvidenceStorageException.invalid();
                frames.add(text(row, 1, 20));
            }
        }
        if (frames.size() != expected) throw EvidenceStorageException.invalid();
        return frames;
    }
    private List<AppleSignalCrash.Image> readImages(String id, int expected) {
        List<AppleSignalCrash.Image> images = new ArrayList<>();
        String projection = "position," + textColumn("base_address", 20) + "," + textColumn("size", 20) + ","
            + textColumn("uuid", 32) + "," + textColumn("cpu_type", 20) + "," + textColumn("cpu_subtype", 20);
        try (Cursor row = database.rawQuery("SELECT " + projection + " FROM signal_crash_images WHERE incident_id=? ORDER BY position LIMIT 513", new String[] {id})) {
            while (row.moveToNext()) {
                if (images.size() >= expected || integer(row, 0) != images.size()) throw EvidenceStorageException.invalid();
                images.add(AppleSignalCrash.Image.restore(text(row, 1, 20), text(row, 2, 20), row.isNull(3) ? null : text(row, 3, 32),
                    row.isNull(4) ? null : text(row, 4, 20), row.isNull(5) ? null : text(row, 5, 20)));
            }
        }
        if (images.size() != expected) throw EvidenceStorageException.invalid();
        return images;
    }
}
