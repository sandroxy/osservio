package com.sandrox.osservio.store;

import static com.sandrox.osservio.store.EvidenceSQLite.flag;
import static com.sandrox.osservio.store.EvidenceSQLite.integer;
import static com.sandrox.osservio.store.EvidenceSQLite.text;
import static com.sandrox.osservio.store.EvidenceSQLite.textColumn;
import static com.sandrox.osservio.store.IncidentNames.name;
import static com.sandrox.osservio.store.IncidentNames.parse;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.sandrox.osservio.core.IncidentSnapshot;
import com.sandrox.osservio.core.SystemDiagnostic;
import java.util.EnumMap;
import java.util.Map;

/** Worker-owned normalized reviewed system facts. Never stores raw platform reports. */
final class IncidentDiagnosticDatabase {
    private final SQLiteDatabase database;
    private final SignalCrashDatabase signals;
    private final TombstoneDatabase tombstones;
    IncidentDiagnosticDatabase(SQLiteDatabase database) {
        this.database = database; signals = new SignalCrashDatabase(database); tombstones = new TombstoneDatabase(database);
    }
    static final String[] SCHEMA = {
        "ALTER TABLE incidents ADD COLUMN diagnostic_kind TEXT",
        "CREATE TABLE incident_diagnostics (incident_id TEXT PRIMARY KEY NOT NULL REFERENCES incidents(id) ON DELETE CASCADE, "
            + "source TEXT NOT NULL, interval_start INTEGER NOT NULL, interval_end INTEGER NOT NULL, reason INTEGER, status INTEGER, "
            + "process_id INTEGER, importance INTEGER, pss INTEGER, rss INTEGER, signal INTEGER, exception_type INTEGER, exception_code TEXT, hang_duration INTEGER)",
        "CREATE TABLE diagnostic_metadata (incident_id TEXT NOT NULL REFERENCES incident_diagnostics(incident_id) ON DELETE CASCADE, "
            + "key TEXT NOT NULL, value TEXT NOT NULL, truncated INTEGER NOT NULL, PRIMARY KEY(incident_id, key))"
    };
    void save(IncidentSnapshot snapshot) {
        SystemDiagnostic diagnostic = snapshot.systemDiagnostic;
        if (diagnostic == null) return;
        SystemDiagnostic.AndroidExit exit = diagnostic.androidExit;
        SystemDiagnostic.MetricKit metric = diagnostic.metricKit;
        database.execSQL("INSERT INTO incident_diagnostics(incident_id, source, interval_start, interval_end, reason, status, process_id, "
            + "importance, pss, rss, signal, exception_type, exception_code, hang_duration, tombstone_state) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", new Object[] {
                snapshot.id, name(diagnostic.source), diagnostic.intervalStartMillis, diagnostic.intervalEndMillis,
                exit == null ? null : exit.reasonCode, exit == null ? null : exit.status, exit == null ? null : exit.processID,
                exit == null ? null : exit.importance, exit == null ? null : exit.pssKiB, exit == null ? null : exit.rssKiB,
                metric == null ? null : metric.signal, metric == null ? null : metric.exceptionType,
                metric == null ? null : metric.exceptionCode, metric == null ? null : metric.hangDurationMillis,
                exit == null ? null : name(exit.tombstoneState)});
        Map<SystemDiagnostic.MetadataKey, SystemDiagnostic.Text> metadata = metric != null ? metric.metadata :
            diagnostic.appleSignal != null ? diagnostic.appleSignal.metadata : java.util.Collections.emptyMap();
        for (Map.Entry<SystemDiagnostic.MetadataKey, SystemDiagnostic.Text> entry : metadata.entrySet()) {
            database.execSQL("INSERT INTO diagnostic_metadata(incident_id, key, value, truncated) VALUES (?, ?, ?, ?)",
                new Object[] {snapshot.id, name(entry.getKey()), entry.getValue().value, entry.getValue().isTruncated ? 1 : 0});
        }
        if (diagnostic.appleSignal != null) signals.save(diagnostic.appleSignal, snapshot.id);
        if (exit != null && exit.tombstone != null) tombstones.save(exit.tombstone, snapshot.id);
    }
    SystemDiagnostic read(String id, SystemDiagnostic.Kind expectedKind) {
        Map<SystemDiagnostic.MetadataKey, SystemDiagnostic.Text> metadata = readMetadata(id);
        String projection = textColumn("source", 32) + ",interval_start,interval_end,reason,status,process_id,importance,pss,rss,signal,exception_type,"
            + textColumn("exception_code", 20) + ",hang_duration," + textColumn("tombstone_state", 32);
        try (Cursor row = database.rawQuery("SELECT " + projection + " FROM incident_diagnostics WHERE incident_id=? LIMIT 2", new String[] {id})) {
            if (!row.moveToFirst()) {
                if (expectedKind != null || !metadata.isEmpty()) throw EvidenceStorageException.invalid();
                signals.requireAbsent(id);
                tombstones.requireAbsent(id);
                return null;
            }
            if (expectedKind == null) throw EvidenceStorageException.invalid();
            SystemDiagnostic.Source source = parse(SystemDiagnostic.Source.values(), text(row, 0, 32));
            if (source != SystemDiagnostic.Source.APPLE_SIGNAL) signals.requireAbsent(id);
            if (source != SystemDiagnostic.Source.ANDROID_EXIT_INFO) {
                tombstones.requireAbsent(id);
                if (!row.isNull(13)) throw EvidenceStorageException.invalid();
            }
            long start = integer(row, 1), end = integer(row, 2);
            SystemDiagnostic result;
            try {
                if (source == SystemDiagnostic.Source.ANDROID_EXIT_INFO) {
                    if (start != end || !metadata.isEmpty()) throw EvidenceStorageException.invalid();
                    for (int i = 9; i <= 12; i++) if (!row.isNull(i)) throw EvidenceStorageException.invalid();
                    int reason = int32(row, 3);
                    SystemDiagnostic.TombstoneState state = row.isNull(13) ?
                        (reason == 5 ? SystemDiagnostic.TombstoneState.NOT_REQUESTED : SystemDiagnostic.TombstoneState.NOT_APPLICABLE) :
                        parse(SystemDiagnostic.TombstoneState.values(), text(row, 13, 32));
                    if (state != SystemDiagnostic.TombstoneState.AVAILABLE) tombstones.requireAbsent(id);
                    result = SystemDiagnostic.androidExit(start, reason, int32(row, 4), int32(row, 5), int32(row, 6),
                        optionalInteger(row, 7), optionalInteger(row, 8), state,
                        state == SystemDiagnostic.TombstoneState.AVAILABLE ? tombstones.read(id) : null);
                } else if (source == SystemDiagnostic.Source.METRIC_KIT) {
                    for (int i = 3; i <= 8; i++) if (!row.isNull(i)) throw EvidenceStorageException.invalid();
                    result = SystemDiagnostic.metricKit(start, end, expectedKind, metadata, row.isNull(9) ? null : int32(row, 9),
                        optionalInteger(row, 10), row.isNull(11) ? null : text(row, 11, 20), optionalInteger(row, 12));
                } else if (source == SystemDiagnostic.Source.APPLE_SIGNAL) {
                    if (start != end) throw EvidenceStorageException.invalid();
                    for (int i = 3; i <= 12; i++) if (!row.isNull(i)) throw EvidenceStorageException.invalid();
                    result = SystemDiagnostic.appleSignal(signals.read(id, start, metadata));
                } else {
                    throw EvidenceStorageException.invalid();
                }
            } catch (IllegalArgumentException invalid) { throw EvidenceStorageException.invalid(); }
            if (result.kind != expectedKind || row.moveToNext()) throw EvidenceStorageException.invalid();
            return result;
        }
    }
    private Map<SystemDiagnostic.MetadataKey, SystemDiagnostic.Text> readMetadata(String id) {
        Map<SystemDiagnostic.MetadataKey, SystemDiagnostic.Text> result = new EnumMap<>(SystemDiagnostic.MetadataKey.class);
        String projection = textColumn("key", 32) + "," + textColumn("value", 256) + ",truncated";
        try (Cursor row = database.rawQuery("SELECT " + projection + " FROM diagnostic_metadata WHERE incident_id=? LIMIT 5", new String[] {id})) {
            while (row.moveToNext()) {
                if (result.size() >= 4) throw EvidenceStorageException.invalid();
                SystemDiagnostic.MetadataKey key = parse(SystemDiagnostic.MetadataKey.values(), text(row, 0, 32));
                if (result.containsKey(key)) throw EvidenceStorageException.invalid();
                try { result.put(key, SystemDiagnostic.Text.restore(text(row, 1, 256), flag(row, 2))); }
                catch (IllegalArgumentException invalid) { throw EvidenceStorageException.invalid(); }
            }
        }
        return result;
    }
    private static Long optionalInteger(Cursor row, int index) { return row.isNull(index) ? null : integer(row, index); }
    private static int int32(Cursor row, int index) {
        long value = integer(row, index);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw EvidenceStorageException.invalid();
        return (int) value;
    }
}
