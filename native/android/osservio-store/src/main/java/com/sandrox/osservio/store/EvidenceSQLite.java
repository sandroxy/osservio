package com.sandrox.osservio.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import com.sandrox.osservio.core.BreadcrumbEvent;
import com.sandrox.osservio.core.PersistenceStatus;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Shared platform SQLite policy and strict bounded column decoding. Used only on storage workers. */
final class EvidenceSQLite {
    private EvidenceSQLite() { }
    static SQLiteDatabase open(File file, int byteLimit, long applicationId, long revision, String[] schema) {
        checkFile(file, byteLimit);
        checkFile(new File(file.getPath() + "-journal"), 2L * byteLimit + 65536L);
        int flags = SQLiteDatabase.CREATE_IF_NECESSARY | SQLiteDatabase.NO_LOCALIZED_COLLATORS;
        // The default platform corruption handler deletes files; diagnostic storage must preserve them.
        SQLiteDatabase database = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, flags, corrupted -> { });
        try {
            long owner = scalar(database, "PRAGMA application_id"), version = scalar(database, "PRAGMA user_version");
            boolean empty = scalar(database, "SELECT count(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'") == 0;
            boolean create = owner == 0 && version == 0 && empty;
            if (!create && (owner != applicationId || version != revision)) throw new EvidenceStorageException(PersistenceStatus.Error.UNSUPPORTED_SCHEMA);
            database.disableWriteAheadLogging();
            try (Cursor row = database.rawQuery("PRAGMA journal_mode=DELETE", null)) {
                if (!row.moveToFirst() || !"delete".equals(row.getString(0))) throw new EvidenceStorageException(PersistenceStatus.Error.UNAVAILABLE);
            }
            database.execSQL("PRAGMA synchronous=FULL");
            database.setForeignKeyConstraintsEnabled(true);
            database.execSQL("PRAGMA temp_store=MEMORY");
            database.execSQL("PRAGMA cache_size=-256");
            if (scalar(database, "PRAGMA busy_timeout=0") != 0) throw new EvidenceStorageException(PersistenceStatus.Error.UNAVAILABLE);
            long pageSize = scalar(database, "PRAGMA page_size");
            if (pageSize < 512 || pageSize > 65536) throw new EvidenceStorageException(PersistenceStatus.Error.CORRUPT);
            long pages = byteLimit / pageSize;
            if (scalar(database, "PRAGMA max_page_count=" + pages) > pages) throw new EvidenceStorageException(PersistenceStatus.Error.STORAGE_FULL);
            if (create) transaction(database, () -> {
                for (String sql : schema) database.execSQL(sql);
                database.execSQL("PRAGMA application_id=" + applicationId);
                database.execSQL("PRAGMA user_version=" + revision);
            });
            return database;
        } catch (RuntimeException failure) { database.close(); throw failure; }
    }
    static long scalar(SQLiteDatabase database, String sql, String... args) {
        try (Cursor row = database.rawQuery(sql, args)) {
            if (!row.moveToFirst()) throw EvidenceStorageException.invalid();
            return integer(row, 0);
        }
    }
    static void transaction(SQLiteDatabase database, Runnable body) {
        database.beginTransaction();
        Throwable original = null;
        try { body.run(); database.setTransactionSuccessful(); }
        catch (RuntimeException | Error failure) { original = failure; throw failure; }
        finally {
            try { database.endTransaction(); }
            catch (RuntimeException cleanup) {
                // SQLITE_FULL may already have rolled back; preserve the original failure category.
                if (original != null) original.addSuppressed(cleanup);
                else throw cleanup;
            }
        }
    }
    static long integer(Cursor row, int column) {
        if (row.getType(column) != Cursor.FIELD_TYPE_INTEGER) throw EvidenceStorageException.invalid();
        return row.getLong(column);
    }
    static boolean flag(Cursor row, int column) {
        long value = integer(row, column);
        if (value != 0 && value != 1) throw EvidenceStorageException.invalid();
        return value == 1;
    }
    // Retrieve bounded original UTF-8 bytes, not a cursor's replacement-decoded string. Invalid types become an invalid UTF-8 sentinel.
    static String textColumn(String name, int limit) {
        return "CASE WHEN " + name + " IS NULL THEN NULL WHEN typeof(" + name + ")='text' THEN substr(CAST(" + name + " AS BLOB),1," + (limit + 1) + ") ELSE x'ff' END";
    }
    static String text(Cursor row, int column, int limit) {
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
    static String kindName(BreadcrumbEvent.Kind kind) {
        String[] words = kind.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder name = new StringBuilder(words[0]);
        for (int i = 1; i < words.length; i++) name.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));
        return name.toString();
    }
    static void checkFile(File file, long limit) {
        try {
            StructStat info = Os.lstat(file.getAbsolutePath());
            if (!OsConstants.S_ISREG(info.st_mode) || info.st_size > limit) throw new EvidenceStorageException(PersistenceStatus.Error.STORAGE_FULL);
        } catch (ErrnoException failure) {
            if (failure.errno != OsConstants.ENOENT) throw new EvidenceStorageException(PersistenceStatus.Error.UNAVAILABLE);
        }
    }
}
