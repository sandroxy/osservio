package com.sandrox.osservio.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import com.sandrox.osservio.core.BreadcrumbEvent;
import com.sandrox.osservio.core.EnvironmentSnapshot;
import com.sandrox.osservio.core.PersistenceStatus;
import com.sandrox.osservio.core.RetainedBreadcrumbs;
import com.sandrox.osservio.core.RetainedRun;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
        checkFile(file, options.databaseByteLimit);
        // Recovery also needs journal page records and sector-aligned headers, not just database bytes.
        checkFile(new File(file.getPath() + "-journal"), 2L * options.databaseByteLimit + 65536L);
        int flags = SQLiteDatabase.CREATE_IF_NECESSARY | SQLiteDatabase.NO_LOCALIZED_COLLATORS;
        // The platform default error handler deletes corrupted files. Preserve them and report the failure instead.
        database = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, flags, corrupted -> { });
        try {
            configure();
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
    private void configure() {
        long owner = scalar("PRAGMA application_id");
        long version = scalar("PRAGMA user_version");
        boolean empty = scalar("SELECT count(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'") == 0;
        if (!(owner == 0 && version == 0 && empty) && (owner != APPLICATION_ID || version != REVISION)) {
            throw new EvidenceStorageException(PersistenceStatus.Error.UNSUPPORTED_SCHEMA);
        }
        database.disableWriteAheadLogging();
        try (Cursor row = query("PRAGMA journal_mode=DELETE")) {
            if (!row.moveToFirst() || !"delete".equals(row.getString(0))) throw new EvidenceStorageException(PersistenceStatus.Error.UNAVAILABLE);
        }
        database.execSQL("PRAGMA synchronous=FULL");
        database.setForeignKeyConstraintsEnabled(true);
        database.execSQL("PRAGMA temp_store=MEMORY");
        database.execSQL("PRAGMA cache_size=-256");
        if (scalar("PRAGMA busy_timeout=0") != 0) throw new EvidenceStorageException(PersistenceStatus.Error.UNAVAILABLE);
        long pageSize = scalar("PRAGMA page_size");
        if (pageSize < 512 || pageSize > 65536) throw new EvidenceStorageException(PersistenceStatus.Error.CORRUPT);
        long pages = options.databaseByteLimit / pageSize;
        if (scalar("PRAGMA max_page_count=" + pages) > pages) throw new EvidenceStorageException(PersistenceStatus.Error.STORAGE_FULL);
        if (owner == 0 && version == 0 && empty) {
            transaction(() -> {
                database.execSQL("CREATE TABLE recording_runs (ordinal INTEGER PRIMARY KEY, id TEXT NOT NULL UNIQUE, "
                    + "platform TEXT NOT NULL, process_id INTEGER NOT NULL, started_at INTEGER NOT NULL, last_recorded_at INTEGER NOT NULL, "
                    + "last_written_at INTEGER NOT NULL, evicted_events INTEGER NOT NULL DEFAULT 0)");
                database.execSQL("CREATE TABLE breadcrumbs (ordinal INTEGER PRIMARY KEY, id TEXT NOT NULL UNIQUE, "
                    + "run_id TEXT NOT NULL REFERENCES recording_runs(id) ON DELETE CASCADE, session_id TEXT NOT NULL, "
                    + "sequence INTEGER NOT NULL CHECK(sequence > 0), timestamp INTEGER NOT NULL, elapsed TEXT, kind TEXT NOT NULL, "
                    + "label TEXT NOT NULL, message TEXT, private INTEGER NOT NULL CHECK(private IN (0,1)), "
                    + "truncated INTEGER NOT NULL CHECK(truncated IN (0,1)), value INTEGER, text_bytes INTEGER NOT NULL CHECK(text_bytes >= 0))");
                database.execSQL("CREATE INDEX breadcrumb_run_order ON breadcrumbs(run_id, ordinal)");
                database.execSQL("PRAGMA application_id=" + APPLICATION_ID);
                database.execSQL("PRAGMA user_version=" + REVISION);
            });
        }
    }
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
    private void transaction(Runnable body) {
        database.beginTransaction();
        Throwable original = null;
        try { body.run(); database.setTransactionSuccessful(); }
        catch (RuntimeException | Error failure) { original = failure; throw failure; }
        finally {
            try { database.endTransaction(); }
            catch (RuntimeException cleanup) {
                // SQLite may already have rolled back after SQLITE_FULL. Preserve the operation's failure.
                if (original != null) original.addSuppressed(cleanup);
                else throw cleanup;
            }
        }
    }
    private static long integer(Cursor row, int column) {
        if (row.getType(column) != Cursor.FIELD_TYPE_INTEGER) throw EvidenceStorageException.invalid();
        return row.getLong(column);
    }
    private static boolean flag(Cursor row, int column) {
        long value = integer(row, column);
        if (value != 0 && value != 1) throw EvidenceStorageException.invalid();
        return value == 1;
    }
    // Retrieve bounded original UTF-8 bytes, not a cursor's replacement-decoded string. Invalid types become an invalid UTF-8 sentinel.
    private static String textColumn(String name, int limit) {
        return "CASE WHEN " + name + " IS NULL THEN NULL WHEN typeof(" + name + ")='text' THEN substr(CAST(" + name + " AS BLOB),1," + (limit + 1) + ") ELSE x'ff' END";
    }
    private static String text(Cursor row, int column, int limit) {
        if (row.getType(column) != Cursor.FIELD_TYPE_BLOB) throw EvidenceStorageException.invalid();
        byte[] bytes = row.getBlob(column);
        if (bytes.length > limit) throw EvidenceStorageException.invalid();
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException failure) { throw EvidenceStorageException.invalid(); }
    }
    static boolean validId(String id) {
        if (id == null || id.length() != 36) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (i == 8 || i == 13 || i == 18 || i == 23) { if (c != '-') return false; }
            else if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
        }
        return true;
    }
    private static String kindName(BreadcrumbEvent.Kind kind) {
        String[] words = kind.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder name = new StringBuilder(words[0]);
        for (int i = 1; i < words.length; i++) name.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));
        return name.toString();
    }
    private static void checkFile(File file, long limit) {
        try {
            StructStat info = Os.lstat(file.getAbsolutePath());
            if (!OsConstants.S_ISREG(info.st_mode) || info.st_size > limit) throw new EvidenceStorageException(PersistenceStatus.Error.STORAGE_FULL);
        } catch (ErrnoException failure) {
            if (failure.errno != OsConstants.ENOENT) throw new EvidenceStorageException(PersistenceStatus.Error.UNAVAILABLE);
        }
    }
}
