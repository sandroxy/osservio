package com.sandrox.osservio.store;

import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabaseCorruptException;
import android.database.sqlite.SQLiteDatabaseLockedException;
import android.database.sqlite.SQLiteFullException;
import android.system.ErrnoException;
import android.system.OsConstants;
import com.sandrox.osservio.core.PersistenceStatus;

/** Stable categories cross SDK boundaries; database messages and paths do not. */
final class EvidenceStorageException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final PersistenceStatus.Error category;
    EvidenceStorageException(PersistenceStatus.Error category) { super(category.name()); this.category = category; }
    static PersistenceStatus.Error category(Exception failure) {
        if (failure instanceof EvidenceStorageException) return ((EvidenceStorageException) failure).category;
        if (failure instanceof SQLiteDatabaseLockedException) return PersistenceStatus.Error.BUSY;
        if (failure instanceof SQLiteFullException) return PersistenceStatus.Error.STORAGE_FULL;
        if (failure instanceof ErrnoException && ((ErrnoException) failure).errno == OsConstants.ENOSPC) return PersistenceStatus.Error.STORAGE_FULL;
        if (failure instanceof SQLiteDatabaseCorruptException) return PersistenceStatus.Error.CORRUPT;
        if (failure instanceof SQLiteConstraintException) return PersistenceStatus.Error.INVALID_RECORD;
        return PersistenceStatus.Error.UNAVAILABLE;
    }
    static EvidenceStorageException invalid() { return new EvidenceStorageException(PersistenceStatus.Error.INVALID_RECORD); }
}
