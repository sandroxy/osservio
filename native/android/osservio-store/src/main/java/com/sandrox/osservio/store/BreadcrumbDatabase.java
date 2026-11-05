package com.sandrox.osservio.store;

import static com.sandrox.osservio.store.EvidenceSQLite.flag;
import static com.sandrox.osservio.store.EvidenceSQLite.integer;
import static com.sandrox.osservio.store.EvidenceSQLite.kindName;
import static com.sandrox.osservio.store.EvidenceSQLite.text;
import static com.sandrox.osservio.store.EvidenceSQLite.textColumn;
import static com.sandrox.osservio.store.EvidenceSQLite.validId;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.sandrox.osservio.core.BreadcrumbEvent;
import com.sandrox.osservio.core.EnvironmentSnapshot;
import com.sandrox.osservio.core.PersistenceStatus;
import com.sandrox.osservio.core.RetainedBreadcrumbs;
import com.sandrox.osservio.core.RetainedRun;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Confined to one storage worker. Platform SQLite owns transaction rollback and recovery. */
final class BreadcrumbDatabase implements AutoCloseable {
    private static final long APPLICATION_ID = 0x4f53564fL;
    private static final long REVISION = 1;
    private final SQLiteDatabase database;
    private final BreadcrumbPersistenceOptions options;
    private final String runId;
    private final long processId;
    private final long startedAt;

    BreadcrumbDatabase(File file, BreadcrumbPersistenceOptions options, String runId, long processId, long startedAt) {
        this.options = options; this.runId = runId; this.processId = processId; this.startedAt = startedAt;
        database = EvidenceSQLite.open(file, options.databaseByteLimit, APPLICATION_ID, REVISION, SCHEMA);
        try {
            transaction(() -> {
                if (scalar("SELECT count(*) FROM recording_runs") > 16) throw EvidenceStorageException.invalid();
                pruneRuns(startedAt);
                List<String> ids = new ArrayList<>();
                try (Cursor rows = query("SELECT " + textColumn("id", 36) + " FROM recording_runs")) {
                    while (rows.moveToNext()) ids.add(text(rows, 0, 36));
                }
                for (String id : ids) trimAndAccount(id);
            });
        } catch (RuntimeException failure) { database.close(); throw failure; }
    }
    @Override public void close() { database.close(); }
    void deleteRun(String id) {
        if (!validId(id)) throw EvidenceStorageException.invalid();
        transaction(() -> database.execSQL("DELETE FROM recording_runs WHERE id=?", new Object[] {id}));
    }
    void deleteAllRuns() { transaction(() -> database.execSQL("DELETE FROM recording_runs")); }
    private static final String[] SCHEMA = {
        "CREATE TABLE recording_runs (ordinal INTEGER PRIMARY KEY, id TEXT NOT NULL UNIQUE, "
            + "platform TEXT NOT NULL, process_id INTEGER NOT NULL, started_at INTEGER NOT NULL, last_recorded_at INTEGER NOT NULL, "
            + "last_written_at INTEGER NOT NULL, evicted_events INTEGER NOT NULL DEFAULT 0)",
        "CREATE TABLE breadcrumbs (ordinal INTEGER PRIMARY KEY, id TEXT NOT NULL UNIQUE, "
            + "run_id TEXT NOT NULL REFERENCES recording_runs(id) ON DELETE CASCADE, session_id TEXT NOT NULL, "
            + "sequence INTEGER NOT NULL CHECK(sequence > 0), timestamp INTEGER NOT NULL, elapsed TEXT, kind TEXT NOT NULL, "
            + "label TEXT NOT NULL, message TEXT, private INTEGER NOT NULL CHECK(private IN (0,1)), "
            + "truncated INTEGER NOT NULL CHECK(truncated IN (0,1)), value INTEGER, text_bytes INTEGER NOT NULL CHECK(text_bytes >= 0))",
        "CREATE INDEX breadcrumb_run_order ON breadcrumbs(run_id, ordinal)"
    };
    void write(List<BreadcrumbEvent> events, long now) {
        if (events.isEmpty()) return;
        if (events.size() > options.pendingEventLimit) throw EvidenceStorageException.invalid();
        for (BreadcrumbEvent event : events) {
            if (event.retention != BreadcrumbEvent.Retention.RESTART_RELEVANT || event.textByteCount > options.textBytesPerRun) throw EvidenceStorageException.invalid();
        }
        transaction(() -> {
            database.execSQL("INSERT OR IGNORE INTO recording_runs(id, platform, process_id, started_at, last_recorded_at, last_written_at) VALUES (?, ?, ?, ?, ?, ?)",
                new Object[] {runId, "android", processId, startedAt, events.get(0).timestampMillis, now});
            pruneRuns(now);
            for (BreadcrumbEvent event : events) {
                database.execSQL("INSERT INTO breadcrumbs(id, run_id, session_id, sequence, timestamp, elapsed, kind, label, message, private, truncated, value, text_bytes) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", new Object[] {event.id, runId, event.sessionId, event.sequence, event.timestampMillis,
                        event.sessionElapsedMillis == null ? null : event.sessionElapsedMillis.toString(), kindName(event.kind), event.label, event.message,
                        event.isPrivate ? 1 : 0, event.isTruncated ? 1 : 0, event.value, event.textByteCount});
            }
            trimAndAccount(runId);
            database.execSQL("UPDATE recording_runs SET last_recorded_at=?, last_written_at=? WHERE id=?", new Object[] {events.get(events.size() - 1).timestampMillis, now, runId});
        });
    }
    private void pruneRuns(long now) {
        if (now >= options.maximumAgeMillis) database.execSQL("DELETE FROM recording_runs WHERE id<>? AND last_written_at<?", new Object[] {runId, now - options.maximumAgeMillis});
        long current = scalar("SELECT count(*) FROM recording_runs WHERE id=?", runId);
        database.execSQL("DELETE FROM recording_runs WHERE id IN (SELECT id FROM recording_runs WHERE id<>? ORDER BY ordinal DESC LIMIT -1 OFFSET ?)",
            new Object[] {runId, options.retainedRuns - current});
    }
    private void trimAndAccount(String id) {
        int count = 0;
        long bytes = 0;
        Long cutoff = null;
        long evicted = 0;
        try (Cursor row = query("SELECT ordinal, text_bytes FROM breadcrumbs WHERE run_id=? ORDER BY ordinal DESC LIMIT 2257", id)) {
            while (row.moveToNext()) {
                if (count + evicted >= 2256) throw EvidenceStorageException.invalid();
                long size = integer(row, 1);
                if (size < 0 || size > 4160) throw EvidenceStorageException.invalid();
                if (cutoff != null || count >= options.recordsPerRun || bytes > options.textBytesPerRun - size) {
                    if (cutoff == null) cutoff = integer(row, 0);
                    evicted++;
                } else { count++; bytes += size; }
            }
        }
        if (cutoff != null) {
            database.execSQL("DELETE FROM breadcrumbs WHERE run_id=? AND ordinal<=?", new Object[] {id, cutoff});
            database.execSQL("UPDATE recording_runs SET evicted_events=CASE WHEN evicted_events>9223372036854775807-? THEN 9223372036854775807 "
                + "ELSE evicted_events+? END WHERE id=?", new Object[] {evicted, evicted, id});
        }
    }
    List<RetainedRun> runs() {
        List<RetainedRun> result = new ArrayList<>();
        try (Cursor row = query(runQuery() + " ORDER BY r.ordinal DESC LIMIT ?", Integer.toString(options.retainedRuns))) {
            while (row.moveToNext()) result.add(summary(row));
        }
        return java.util.Collections.unmodifiableList(result);
    }
    RetainedBreadcrumbs read(String id) {
        if (!validId(id)) throw EvidenceStorageException.invalid();
        RetainedRun run;
        try (Cursor row = query(runQuery() + " WHERE r.id=?", id)) {
            if (!row.moveToFirst()) return null;
            run = summary(row);
        }
        List<BreadcrumbEvent> events = new ArrayList<>();
        int bytes = 0;
        String projection = textColumn("id", 36) + "," + textColumn("session_id", 36) + ",sequence,timestamp,"
            + textColumn("elapsed", 20) + "," + textColumn("kind", 32) + "," + textColumn("label", 64) + ","
            + textColumn("message", 4096) + ",private,truncated,value,text_bytes";
        try (Cursor row = query("SELECT " + projection + " FROM breadcrumbs WHERE run_id=? ORDER BY ordinal LIMIT ?", id, Integer.toString(options.recordsPerRun + 1))) {
            while (row.moveToNext()) {
                if (events.size() >= options.recordsPerRun) throw EvidenceStorageException.invalid();
                String elapsedText = row.isNull(4) ? null : text(row, 4, 20);
                Long elapsed = null;
                if (elapsedText != null) {
                    try { elapsed = Long.parseLong(elapsedText); }
                    catch (NumberFormatException failure) { throw EvidenceStorageException.invalid(); }
                    if (elapsed < 0 || !elapsedText.equals(elapsed.toString())) throw EvidenceStorageException.invalid();
                }
                String kindText = text(row, 5, 32);
                BreadcrumbEvent.Kind kind = null;
                for (BreadcrumbEvent.Kind candidate : BreadcrumbEvent.Kind.values()) if (kindName(candidate).equals(kindText)) { kind = candidate; break; }
                BreadcrumbEvent event;
                try {
                    event = BreadcrumbEvent.restore(text(row, 0, 36), text(row, 1, 36), integer(row, 2), integer(row, 3), elapsed, kind,
                        text(row, 6, 64), row.isNull(7) ? null : text(row, 7, 4096), flag(row, 8), flag(row, 9), row.isNull(10) ? null : integer(row, 10),
                        BreadcrumbEvent.Retention.RESTART_RELEVANT);
                } catch (IllegalArgumentException failure) { throw EvidenceStorageException.invalid(); }
                if (event.textByteCount != integer(row, 11) || bytes > options.textBytesPerRun - event.textByteCount) throw EvidenceStorageException.invalid();
                bytes += event.textByteCount; events.add(event);
            }
        }
        if (events.size() != run.eventCount) throw EvidenceStorageException.invalid();
        return new RetainedBreadcrumbs(run, events);
    }
    private static String runQuery() {
        return "SELECT " + textColumn("r.id", 36) + "," + textColumn("r.platform", 16)
            + ",r.process_id,r.started_at,r.last_recorded_at,r.evicted_events,(SELECT count(*) FROM breadcrumbs b WHERE b.run_id=r.id) FROM recording_runs r";
    }
    private RetainedRun summary(Cursor row) {
        String id = text(row, 0, 36);
        String name = text(row, 1, 16);
        if (!validId(id) || (!name.equals("android") && !name.equals("ios"))) throw EvidenceStorageException.invalid();
        long process = integer(row, 2), evicted = integer(row, 5), count = integer(row, 6);
        if (process <= 0 || evicted < 0 || count < 0 || count > options.recordsPerRun) throw EvidenceStorageException.invalid();
        return new RetainedRun(id, name.equals("android") ? EnvironmentSnapshot.Platform.ANDROID : EnvironmentSnapshot.Platform.IOS,
            process, integer(row, 3), integer(row, 4), (int) count, evicted, id.equals(runId));
    }
    private long scalar(String sql, String... args) {
        try (Cursor row = query(sql, args)) { if (!row.moveToFirst()) throw EvidenceStorageException.invalid(); return integer(row, 0); }
    }
    private Cursor query(String sql, String... args) { return database.rawQuery(sql, args); }
    private void transaction(Runnable body) { EvidenceSQLite.transaction(database, body); }
}
