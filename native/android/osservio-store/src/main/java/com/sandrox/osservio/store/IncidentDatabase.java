package com.sandrox.osservio.store;

import static com.sandrox.osservio.store.EvidenceSQLite.flag;
import static com.sandrox.osservio.store.EvidenceSQLite.integer;
import static com.sandrox.osservio.store.EvidenceSQLite.text;
import static com.sandrox.osservio.store.EvidenceSQLite.textColumn;
import static com.sandrox.osservio.store.EvidenceSQLite.validId;
import static com.sandrox.osservio.store.IncidentNames.name;
import static com.sandrox.osservio.store.IncidentNames.parse;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.sandrox.osservio.core.BreadcrumbEvent;
import com.sandrox.osservio.core.EnvironmentSnapshot;
import com.sandrox.osservio.core.IncidentSnapshot;
import com.sandrox.osservio.core.IncidentSummary;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Worker-confined normalized storage. Live histories have no ownership or retention dependency on it. */
final class IncidentDatabase implements AutoCloseable {
    private final SQLiteDatabase database;
    private final IncidentDetailDatabase details;
    private final IncidentPersistenceOptions options;
    IncidentDatabase(File file, IncidentPersistenceOptions options, long now) {
        this.options = options;
        String[] schema = Arrays.copyOf(SCHEMA, SCHEMA.length + IncidentDetailDatabase.SCHEMA.length);
        System.arraycopy(IncidentDetailDatabase.SCHEMA, 0, schema, SCHEMA.length, IncidentDetailDatabase.SCHEMA.length);
        database = EvidenceSQLite.open(file, options.databaseByteLimit, 0x4f535649L, 2, schema, Collections.singletonMap(1L, IncidentDetailDatabase.SCHEMA));
        details = new IncidentDetailDatabase(database);
        try {
            transaction(() -> {
                if (EvidenceSQLite.scalar(database, "SELECT count(*) FROM incidents") > 16) throw EvidenceStorageException.invalid();
                prune(now, 0);
            });
        } catch (RuntimeException failure) { database.close(); throw failure; }
    }
    @Override public void close() { database.close(); }
    private static final String[] SCHEMA = {
        "CREATE TABLE incidents (ordinal INTEGER PRIMARY KEY, id TEXT NOT NULL UNIQUE COLLATE NOCASE, "
            + "timestamp INTEGER NOT NULL, saved_at INTEGER NOT NULL, note TEXT, private INTEGER NOT NULL, truncated INTEGER NOT NULL, "
            + "environment_platform TEXT, environment_time INTEGER, breadcrumb_count INTEGER, source_count INTEGER, source_evicted INTEGER, text_bytes INTEGER NOT NULL)",
        "CREATE TABLE environment_facts (incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE, "
            + "key TEXT NOT NULL, type TEXT NOT NULL, text_value TEXT, number_value INTEGER, truncated INTEGER NOT NULL, PRIMARY KEY(incident_id, key))",
        "CREATE TABLE incident_breadcrumbs (incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE, "
            + "position INTEGER NOT NULL, id TEXT NOT NULL COLLATE NOCASE, session_id TEXT NOT NULL, sequence INTEGER NOT NULL, "
            + "timestamp INTEGER NOT NULL, elapsed TEXT, kind TEXT NOT NULL, label TEXT NOT NULL, message TEXT, private INTEGER NOT NULL, "
            + "truncated INTEGER NOT NULL, value INTEGER, retention TEXT NOT NULL, PRIMARY KEY(incident_id, position), UNIQUE(incident_id, id))"
    };
    void save(IncidentSnapshot snapshot, long now) {
        if (snapshot.textByteCount > options.textBytesPerIncident) throw EvidenceStorageException.invalid();
        transaction(() -> {
            // Check before retention cleanup; a duplicate identity must never replace its original evidence.
            if (EvidenceSQLite.scalar(database, "SELECT count(*) FROM incidents WHERE id=?", snapshot.id) != 0) throw EvidenceStorageException.invalid();
            prune(now, 1);
            IncidentSnapshot.Note note = snapshot.note;
            EnvironmentSnapshot environment = snapshot.environment;
            IncidentSnapshot.Breadcrumbs breadcrumbs = snapshot.breadcrumbs;
            database.execSQL("INSERT INTO incidents(id, timestamp, saved_at, note, private, truncated, environment_platform, environment_time, "
                + "breadcrumb_count, source_count, source_evicted, text_bytes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", new Object[] {
                    snapshot.id, snapshot.timestampMillis, now, note == null ? null : note.text, note != null && note.isPrivate ? 1 : 0,
                    note != null && note.isTruncated ? 1 : 0, environment == null ? null : name(environment.platform), environment == null ? null : environment.timestampMillis,
                    breadcrumbs == null ? null : breadcrumbs.events.size(), breadcrumbs == null ? null : breadcrumbs.sourceEventCount,
                    breadcrumbs == null ? null : breadcrumbs.sourceEvictedCount, snapshot.textByteCount});
            if (environment != null) for (Map.Entry<EnvironmentSnapshot.Key, EnvironmentSnapshot.Value> entry : environment.values.entrySet()) {
                EnvironmentSnapshot.Value value = entry.getValue();
                EnvironmentSnapshot.Fact fact = value.fact;
                Long number = fact.number;
                if (fact.type == EnvironmentSnapshot.Fact.Type.FLAG) number = fact.flag ? 1L : 0L;
                database.execSQL("INSERT INTO environment_facts(incident_id, key, type, text_value, number_value, truncated) VALUES (?, ?, ?, ?, ?, ?)",
                    new Object[] {snapshot.id, name(entry.getKey()), name(fact.type), fact.text, number, value.isTruncated ? 1 : 0});
            }
            if (breadcrumbs != null) for (int position = 0; position < breadcrumbs.events.size(); position++) {
                BreadcrumbEvent event = breadcrumbs.events.get(position);
                database.execSQL("INSERT INTO incident_breadcrumbs(incident_id, position, id, session_id, sequence, timestamp, elapsed, kind, label, "
                    + "message, private, truncated, value, retention) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", new Object[] {
                        snapshot.id, position, event.id, event.sessionId, event.sequence, event.timestampMillis,
                        event.sessionElapsedMillis == null ? null : event.sessionElapsedMillis.toString(), name(event.kind), event.label,
                        event.message, event.isPrivate ? 1 : 0, event.isTruncated ? 1 : 0, event.value, name(event.retention)});
            }
            details.save(snapshot);
        });
    }
    private void prune(long now, int reserve) {
        if (now >= options.maximumAgeMillis) database.execSQL("DELETE FROM incidents WHERE saved_at<?", new Object[] {now - options.maximumAgeMillis});
        database.execSQL("DELETE FROM incidents WHERE id IN (SELECT id FROM incidents ORDER BY ordinal DESC LIMIT -1 OFFSET ?)",
            new Object[] {options.retainedIncidents - reserve});
    }
    List<IncidentSummary> summaries() {
        List<IncidentSummary> result = new ArrayList<>();
        try (Cursor row = query(headerQuery() + " ORDER BY ordinal DESC LIMIT 17")) {
            while (row.moveToNext()) {
                if (result.size() >= options.retainedIncidents) throw EvidenceStorageException.invalid();
                result.add(header(row).summary);
            }
        }
        return Collections.unmodifiableList(result);
    }
    IncidentSnapshot read(String id) {
        if (!validId(id)) throw EvidenceStorageException.invalid();
        Header info;
        try (Cursor row = query(headerQuery() + " WHERE id=?", id)) {
            if (!row.moveToFirst()) return null;
            info = header(row);
        }
        EnvironmentSnapshot environment = readEnvironment(info);
        IncidentSnapshot.Breadcrumbs breadcrumbs = readBreadcrumbs(info);
        IncidentDetailDatabase.Detail attachments = details.read(info.summary.id, info.windows);
        IncidentSnapshot snapshot = IncidentSnapshot.restore(info.summary.id, info.summary.timestampMillis, info.summary.note, environment, breadcrumbs,
            attachments.logs, attachments.http, attachments.webSockets);
        if (snapshot.textByteCount != info.bytes) throw EvidenceStorageException.invalid();
        return snapshot;
    }
    void delete(String id) {
        if (!validId(id)) throw EvidenceStorageException.invalid();
        transaction(() -> database.execSQL("DELETE FROM incidents WHERE id=?", new Object[] {id}));
    }
    void deleteAll() { transaction(() -> database.execSQL("DELETE FROM incidents")); }
    private static String headerQuery() {
        return "SELECT " + textColumn("id", 36) + ",timestamp," + textColumn("note", 4096) + ",private,truncated,"
            + textColumn("environment_platform", 16) + ",environment_time,breadcrumb_count,source_count,source_evicted,text_bytes FROM incidents";
    }
    private static final class Header {
        final IncidentSummary summary;
        final EnvironmentSnapshot.Platform platform;
        final Long environmentTime;
        final Integer sourceCount;
        final Long sourceEvicted;
        final int bytes;
        final Map<IncidentDetailDatabase.Kind, IncidentDetailDatabase.Window> windows;
        Header(IncidentSummary summary, EnvironmentSnapshot.Platform platform, Long environmentTime, Integer sourceCount, Long sourceEvicted, int bytes,
               Map<IncidentDetailDatabase.Kind, IncidentDetailDatabase.Window> windows) {
            this.summary = summary; this.platform = platform; this.environmentTime = environmentTime;
            this.sourceCount = sourceCount; this.sourceEvicted = sourceEvicted; this.bytes = bytes; this.windows = windows;
        }
    }
    private Header header(Cursor row) {
        String id = text(row, 0, 36);
        boolean isPrivate = flag(row, 3), truncated = flag(row, 4);
        if (!validId(id) || (row.isNull(2) && (isPrivate || truncated))) throw EvidenceStorageException.invalid();
        IncidentSnapshot.Note note;
        try { note = row.isNull(2) ? null : IncidentSnapshot.Note.restore(text(row, 2, 4096), isPrivate, truncated); }
        catch (IllegalArgumentException failure) { throw EvidenceStorageException.invalid(); }
        EnvironmentSnapshot.Platform platform = null;
        if (row.isNull(5)) { if (!row.isNull(6)) throw EvidenceStorageException.invalid(); }
        else {
            platform = parse(EnvironmentSnapshot.Platform.values(), text(row, 5, 16));
            if (row.isNull(6)) throw EvidenceStorageException.invalid();
        }
        Long count = row.isNull(7) ? null : integer(row, 7), source = row.isNull(8) ? null : integer(row, 8), evicted = row.isNull(9) ? null : integer(row, 9);
        if (count == null) { if (source != null || evicted != null) throw EvidenceStorageException.invalid(); }
        else if (count < 0 || count > 2000 || source == null || source < count || source > Integer.MAX_VALUE || evicted == null || evicted < 0) throw EvidenceStorageException.invalid();
        long bytes = integer(row, 10);
        if (bytes < 0 || bytes > 2097152) throw EvidenceStorageException.invalid();
        Map<IncidentDetailDatabase.Kind, IncidentDetailDatabase.Window> windows = details.windows(id);
        int attachmentBytes = 0;
        for (IncidentDetailDatabase.Window window : windows.values()) attachmentBytes += window.bytes + window.payloadBytes;
        if (attachmentBytes > bytes) throw EvidenceStorageException.invalid();
        return new Header(new IncidentSummary(id, integer(row, 1), note, platform != null, count == null ? null : count.intValue(),
            count(windows, IncidentDetailDatabase.Kind.LOGS), count(windows, IncidentDetailDatabase.Kind.HTTP), count(windows, IncidentDetailDatabase.Kind.WEBSOCKET)),
            platform, platform == null ? null : integer(row, 6), source == null ? null : source.intValue(), evicted, (int) bytes, windows);
    }
    private static Integer count(Map<IncidentDetailDatabase.Kind, IncidentDetailDatabase.Window> windows, IncidentDetailDatabase.Kind kind) {
        IncidentDetailDatabase.Window window = windows.get(kind); return window == null ? null : window.count;
    }
    private EnvironmentSnapshot readEnvironment(Header info) {
        Map<EnvironmentSnapshot.Key, EnvironmentSnapshot.Fact> facts = new EnumMap<>(EnvironmentSnapshot.Key.class);
        Set<EnvironmentSnapshot.Key> truncated = EnumSet.noneOf(EnvironmentSnapshot.Key.class);
        String projection = textColumn("key", 32) + "," + textColumn("type", 16) + "," + textColumn("text_value", 256) + ",number_value,truncated";
        try (Cursor row = query("SELECT " + projection + " FROM environment_facts WHERE incident_id=? LIMIT 18", info.summary.id)) {
            while (row.moveToNext()) {
                if (info.platform == null || facts.size() >= EnvironmentSnapshot.Key.values().length) throw EvidenceStorageException.invalid();
                EnvironmentSnapshot.Key key = parse(EnvironmentSnapshot.Key.values(), text(row, 0, 32));
                if (facts.containsKey(key)) throw EvidenceStorageException.invalid();
                String type = text(row, 1, 16);
                EnvironmentSnapshot.Fact fact;
                switch (type) {
                    case "text":
                        if (!row.isNull(3)) throw EvidenceStorageException.invalid();
                        fact = EnvironmentSnapshot.Fact.text(text(row, 2, 256)); break;
                    case "number":
                        if (!row.isNull(2)) throw EvidenceStorageException.invalid();
                        fact = EnvironmentSnapshot.Fact.number(integer(row, 3)); break;
                    case "flag":
                        if (!row.isNull(2)) throw EvidenceStorageException.invalid();
                        fact = EnvironmentSnapshot.Fact.flag(flag(row, 3)); break;
                    case "unavailable": case "unsupported":
                        if (!row.isNull(2) || !row.isNull(3)) throw EvidenceStorageException.invalid();
                        fact = type.equals("unavailable") ? EnvironmentSnapshot.Fact.UNAVAILABLE : EnvironmentSnapshot.Fact.UNSUPPORTED; break;
                    default: throw EvidenceStorageException.invalid();
                }
                facts.put(key, fact);
                if (flag(row, 4)) truncated.add(key);
            }
        }
        if (info.platform == null) return null;
        try { return EnvironmentSnapshot.restore(info.platform, facts, truncated, info.environmentTime); }
        catch (IllegalArgumentException failure) { throw EvidenceStorageException.invalid(); }
    }
    private IncidentSnapshot.Breadcrumbs readBreadcrumbs(Header info) {
        List<BreadcrumbEvent> events = new ArrayList<>();
        int bytes = 0;
        String projection = "position," + textColumn("id", 36) + "," + textColumn("session_id", 36) + ",sequence,timestamp,"
            + textColumn("elapsed", 20) + "," + textColumn("kind", 32) + "," + textColumn("label", 64) + "," + textColumn("message", 4096)
            + ",private,truncated,value," + textColumn("retention", 32);
        try (Cursor row = query("SELECT " + projection + " FROM incident_breadcrumbs WHERE incident_id=? ORDER BY position LIMIT 2001", info.summary.id)) {
            while (row.moveToNext()) {
                if (info.summary.breadcrumbCount == null || events.size() >= info.summary.breadcrumbCount || integer(row, 0) != events.size()) throw EvidenceStorageException.invalid();
                String elapsedText = row.isNull(5) ? null : text(row, 5, 20);
                Long elapsed = null;
                if (elapsedText != null) {
                    try { elapsed = Long.parseLong(elapsedText); }
                    catch (NumberFormatException failure) { throw EvidenceStorageException.invalid(); }
                    if (elapsed < 0 || !elapsedText.equals(elapsed.toString())) throw EvidenceStorageException.invalid();
                }
                BreadcrumbEvent event;
                try {
                    event = BreadcrumbEvent.restore(text(row, 1, 36), text(row, 2, 36), integer(row, 3), integer(row, 4), elapsed,
                        parse(BreadcrumbEvent.Kind.values(), text(row, 6, 32)), text(row, 7, 64), row.isNull(8) ? null : text(row, 8, 4096),
                        flag(row, 9), flag(row, 10), row.isNull(11) ? null : integer(row, 11), parse(BreadcrumbEvent.Retention.values(), text(row, 12, 32)));
                } catch (IllegalArgumentException failure) { throw EvidenceStorageException.invalid(); }
                if (event.textByteCount > 1048576 - bytes) throw EvidenceStorageException.invalid();
                bytes += event.textByteCount; events.add(event);
            }
        }
        if (info.summary.breadcrumbCount == null) return null;
        if (events.size() != info.summary.breadcrumbCount) throw EvidenceStorageException.invalid();
        try { return IncidentSnapshot.Breadcrumbs.restore(events, info.sourceCount, info.sourceEvicted); }
        catch (IllegalArgumentException failure) { throw EvidenceStorageException.invalid(); }
    }
    private Cursor query(String sql, String... args) { return database.rawQuery(sql, args); }
    private void transaction(Runnable operation) { EvidenceSQLite.transaction(database, operation); }
}
