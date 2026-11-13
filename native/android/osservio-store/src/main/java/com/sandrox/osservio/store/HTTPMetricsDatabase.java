package com.sandrox.osservio.store;

import static com.sandrox.osservio.store.EvidenceSQLite.flag;
import static com.sandrox.osservio.store.EvidenceSQLite.integer;
import static com.sandrox.osservio.store.EvidenceSQLite.text;
import static com.sandrox.osservio.store.EvidenceSQLite.textColumn;
import static com.sandrox.osservio.store.IncidentNames.name;
import static com.sandrox.osservio.store.IncidentNames.parse;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.sandrox.osservio.core.HTTPMetrics;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Normalized phase evidence on the incident transaction and ownership lease. */
final class HTTPMetricsDatabase {
    static final String[] SCHEMA = {
        "ALTER TABLE incident_http ADD COLUMN metrics_present INTEGER NOT NULL DEFAULT 0",
        "CREATE TABLE incident_http_metrics (incident_id TEXT NOT NULL, event_position INTEGER NOT NULL, protocol TEXT, cache TEXT NOT NULL, reused INTEGER, limited INTEGER NOT NULL, phase_count INTEGER NOT NULL, PRIMARY KEY(incident_id,event_position), FOREIGN KEY(incident_id,event_position) REFERENCES incident_http(incident_id,position) ON DELETE CASCADE)",
        "CREATE TABLE incident_http_phases (incident_id TEXT NOT NULL, event_position INTEGER NOT NULL, position INTEGER NOT NULL, phase TEXT NOT NULL, started INTEGER NOT NULL, ended INTEGER, PRIMARY KEY(incident_id,event_position,position), FOREIGN KEY(incident_id,event_position) REFERENCES incident_http_metrics(incident_id,event_position) ON DELETE CASCADE)"
    };
    private final SQLiteDatabase database;
    HTTPMetricsDatabase(SQLiteDatabase database) { this.database = database; }
    void save(HTTPMetrics metrics, String id, int position) {
        if (metrics == null) return;
        database.execSQL("INSERT INTO incident_http_metrics VALUES (?,?,?,?,?,?,?)", new Object[] {id, position, metrics.protocolName,
            name(metrics.cache), metrics.connectionReused == null ? null : metrics.connectionReused ? 1 : 0, metrics.limited ? 1 : 0, metrics.phases.size()});
        for (int index = 0; index < metrics.phases.size(); index++) {
            HTTPMetrics.Timing phase = metrics.phases.get(index);
            database.execSQL("INSERT INTO incident_http_phases VALUES (?,?,?,?,?,?)", new Object[] {id, position, index, name(phase.phase), phase.startMillis, phase.endMillis});
        }
    }
    Map<Integer, HTTPMetrics> read(String id, int eventCount) {
        Map<Integer, HTTPMetrics> values = new LinkedHashMap<>(); int phaseCount = 0;
        try (Cursor row = database.rawQuery("SELECT event_position," + textColumn("protocol", 32) + "," + textColumn("cache", 32)
            + ",reused,limited,phase_count FROM incident_http_metrics WHERE incident_id=? ORDER BY event_position LIMIT 2001", new String[] {id})) {
            while (row.moveToNext()) {
                long position = integer(row, 0);
                if (position < 0 || position >= eventCount || values.containsKey((int) position)) throw EvidenceStorageException.invalid();
                List<HTTPMetrics.Timing> phases = new ArrayList<>();
                try (Cursor item = database.rawQuery("SELECT position," + textColumn("phase", 32)
                    + ",started,ended FROM incident_http_phases WHERE incident_id=? AND event_position=? ORDER BY position LIMIT 33", new String[] {id, Long.toString(position)})) {
                    while (item.moveToNext()) {
                        if (phases.size() >= 32 || integer(item, 0) != phases.size()) throw EvidenceStorageException.invalid();
                        phases.add(new HTTPMetrics.Timing(parse(HTTPMetrics.Phase.values(), text(item, 1, 32)), integer(item, 2), item.isNull(3) ? null : integer(item, 3)));
                    }
                }
                if (integer(row, 5) != phases.size()) throw EvidenceStorageException.invalid();
                phaseCount += phases.size();
                values.put((int) position, new HTTPMetrics(phases, row.isNull(1) ? null : text(row, 1, 32),
                    parse(HTTPMetrics.Cache.values(), text(row, 2, 32)), row.isNull(3) ? null : flag(row, 3), flag(row, 4)));
            }
        }
        if (EvidenceSQLite.scalar(database, "SELECT count(*) FROM incident_http_phases WHERE incident_id=?", id) != phaseCount) throw EvidenceStorageException.invalid();
        return values;
    }
}
