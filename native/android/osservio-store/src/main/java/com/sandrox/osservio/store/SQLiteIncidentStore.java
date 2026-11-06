package com.sandrox.osservio.store;

import android.content.Context;
import com.sandrox.osservio.core.IncidentArchive;
import com.sandrox.osservio.core.DiagnosticRecoveryArchive;
import com.sandrox.osservio.core.SystemDiagnostic;
import com.sandrox.osservio.core.SystemDiagnosticBatch;
import com.sandrox.osservio.core.IncidentSnapshot;
import com.sandrox.osservio.core.LogClock;
import com.sandrox.osservio.core.PersistenceStatus;
import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/** Explicit asynchronous snapshot storage. It does not own live histories or capture policy. */
public final class SQLiteIncidentStore implements IncidentArchive, DiagnosticRecoveryArchive {
    private final IncidentPersistenceOptions options;
    private final LogClock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Osservio-incident-storage"); thread.setDaemon(true); return thread;
    });
    private PersistenceStatus.State state = PersistenceStatus.State.OPENING;
    private boolean accepting = true;
    private int operations;
    private int writes;
    private int pendingBytes;
    private long committed;
    private long discarded;
    private long failures;
    private PersistenceStatus.Error lastError;
    // Resources are worker-confined; caller threads access only counters under the admission lock.
    private IncidentDatabase database;
    private EvidenceDirectory directory;
    private interface DirectoryProvider { File directory(); }

    public SQLiteIncidentStore(Context context) { this(context, new IncidentPersistenceOptions()); }
    public SQLiteIncidentStore(Context context, IncidentPersistenceOptions options) { this(privateDirectory(context), options, LogClock.SYSTEM); }
    SQLiteIncidentStore(File directory, IncidentPersistenceOptions options, LogClock clock) { this(() -> directory, options, clock); }
    private static DirectoryProvider privateDirectory(Context context) {
        Context application = Objects.requireNonNull(context.getApplicationContext(), "applicationContext");
        return () -> new File(application.getNoBackupFilesDir(), "osservio/incidents");
    }
    private SQLiteIncidentStore(DirectoryProvider path, IncidentPersistenceOptions options, LogClock clock) {
        this.options = Objects.requireNonNull(options, "options"); this.clock = Objects.requireNonNull(clock, "clock");
        worker.execute(() -> {
            try {
                directory = new EvidenceDirectory(path.directory(), EvidenceDirectory.Contents.INCIDENTS);
                database = new IncidentDatabase(directory.databaseFile, options, clock.wallTimeMillis());
                lock.lock(); try { if (accepting) state = PersistenceStatus.State.READY; } finally { lock.unlock(); }
            } catch (Exception failure) { fail(EvidenceStorageException.category(failure)); }
        });
    }
    @Override public PersistenceStatus persistenceStatus() {
        if (!lock.tryLock()) return null;
        try { return new PersistenceStatus(state, writes, pendingBytes, committed, discarded, failures, lastError); }
        finally { lock.unlock(); }
    }
    @Override public void save(IncidentSnapshot snapshot, IncidentArchive.Completion completion) {
        Objects.requireNonNull(snapshot, "snapshot"); Objects.requireNonNull(completion, "completion");
        if (snapshot.textByteCount > options.textBytesPerIncident) { completion.complete(PersistenceStatus.Error.INVALID_RECORD); return; }
        perform((ignored, error) -> completion.complete(error), snapshot.textByteCount, false, db -> { db.save(snapshot, clock.wallTimeMillis()); return null; });
    }
    @Override public void readIncidents(SummariesCompletion completion) {
        Objects.requireNonNull(completion, "completion");
        perform(completion::complete, null, true, IncidentDatabase::summaries);
    }
    @Override public void recoverDiagnostics(SystemDiagnosticBatch batch, DiagnosticRecoveryArchive.RecoveryCompletion completion) {
        Objects.requireNonNull(batch, "batch"); Objects.requireNonNull(completion, "completion");
        int bytes = 0;
        for (SystemDiagnostic diagnostic : batch.diagnostics) bytes += diagnostic.textByteCount();
        perform(completion::complete, bytes, batch.diagnostics.size(), true,
            receipt -> receipt.savedCount, db -> db.recover(batch, clock.wallTimeMillis()));
    }
    @Override public void readIncident(String id, SnapshotCompletion completion) {
        Objects.requireNonNull(completion, "completion");
        if (!EvidenceSQLite.validId(id)) { completion.complete(null, PersistenceStatus.Error.INVALID_RECORD); return; }
        perform(completion::complete, null, true, db -> db.read(id));
    }
    @Override public void deleteIncident(String id, IncidentArchive.Completion completion) {
        Objects.requireNonNull(completion, "completion");
        if (!EvidenceSQLite.validId(id)) { completion.complete(PersistenceStatus.Error.INVALID_RECORD); return; }
        perform((ignored, error) -> completion.complete(error), null, false, db -> { db.delete(id); return null; });
    }
    @Override public void deleteAllIncidents(IncidentArchive.Completion completion) {
        Objects.requireNonNull(completion, "completion");
        perform((ignored, error) -> completion.complete(error), null, false, db -> { db.deleteAll(); return null; });
    }
    /** Finishes admitted work before releasing ownership. No caller-thread disk operations. */
    public void close(IncidentArchive.Completion completion) {
        Objects.requireNonNull(completion, "completion");
        if (!lock.tryLock()) { completion.complete(PersistenceStatus.Error.BUSY); return; }
        if (state == PersistenceStatus.State.CLOSED) { lock.unlock(); completion.complete(null); return; }
        if (!accepting) { lock.unlock(); completion.complete(PersistenceStatus.Error.BUSY); return; }
        accepting = false; state = PersistenceStatus.State.CLOSING;
        worker.execute(() -> {
            PersistenceStatus.Error error;
            lock.lock(); try { error = database == null ? lastError : null; } finally { lock.unlock(); }
            PersistenceStatus.Error cleanup = releaseResources();
            if (cleanup != null) error = cleanup;
            lock.lock(); try { state = PersistenceStatus.State.CLOSED; } finally { lock.unlock(); }
            worker.shutdown();
            completion.complete(error);
        });
        lock.unlock();
    }
    private interface Operation<T> { T run(IncidentDatabase db); }
    private interface Delivery<T> { void complete(T result, PersistenceStatus.Error error); }
    private interface CommittedCount<T> { int count(T result); }
    private <T> void perform(Delivery<T> completion, Integer writeBytes, boolean reading, Operation<T> operation) {
        perform(completion, writeBytes, 1, reading, ignored -> 1, operation);
    }
    private <T> void perform(Delivery<T> completion, Integer writeBytes, int writeCount, boolean reading,
                            CommittedCount<T> committedCount, Operation<T> operation) {
        if (!lock.tryLock()) { completion.complete(null, PersistenceStatus.Error.BUSY); return; }
        if (!accepting || operations >= 4 || (writeBytes == null ? 0 : writeBytes) > options.pendingTextByteLimit - pendingBytes) {
            PersistenceStatus.Error error = accepting ? PersistenceStatus.Error.BUSY : PersistenceStatus.Error.CLOSED;
            if (writeBytes != null) discarded = increment(discarded, writeCount);
            lock.unlock(); completion.complete(null, error); return;
        }
        operations++;
        if (writeBytes != null) { writes += writeCount; pendingBytes += writeBytes; }
        // Admission and enqueue share a lock so close cannot overtake an accepted operation.
        worker.execute(() -> {
            T result = null;
            PersistenceStatus.Error error = null;
            if (database != null) {
                try { result = operation.run(database); }
                catch (RuntimeException failure) {
                    error = EvidenceStorageException.category(failure);
                    if (error == PersistenceStatus.Error.CORRUPT || error == PersistenceStatus.Error.UNSUPPORTED_SCHEMA
                        || (reading && error == PersistenceStatus.Error.INVALID_RECORD)) fail(error);
                }
            } else {
                lock.lock(); try { error = lastError == null ? PersistenceStatus.Error.UNAVAILABLE : lastError; } finally { lock.unlock(); }
            }
            lock.lock();
            try {
                operations--;
                if (writeBytes != null) {
                    writes -= writeCount; pendingBytes -= writeBytes;
                    if (error == null) {
                        int count = committedCount.count(result);
                        committed = increment(committed, count); discarded = increment(discarded, writeCount - count); lastError = null;
                    } else { discarded = increment(discarded, writeCount); failures = increment(failures); }
                }
                if (error != null) lastError = error;
            } finally { lock.unlock(); }
            // Host callbacks run once, outside locks and after returning admission permits.
            completion.complete(result, error);
        });
        lock.unlock();
    }
    private void fail(PersistenceStatus.Error error) {
        lock.lock(); try { state = accepting ? PersistenceStatus.State.FAILED : PersistenceStatus.State.CLOSING; lastError = error; } finally { lock.unlock(); }
        releaseResources();
    }
    private PersistenceStatus.Error releaseResources() {
        PersistenceStatus.Error error = null;
        try { if (database != null) database.close(); }
        catch (RuntimeException failure) { error = EvidenceStorageException.category(failure); }
        finally { database = null; }
        try { if (directory != null) directory.close(); }
        catch (IOException failure) { if (error == null) error = PersistenceStatus.Error.UNAVAILABLE; }
        finally { directory = null; }
        return error;
    }
    private static long increment(long value) { return value == Long.MAX_VALUE ? value : value + 1; }
    private static long increment(long value, int amount) { return value > Long.MAX_VALUE - amount ? Long.MAX_VALUE : value + amount; }
}
