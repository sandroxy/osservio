package com.sandrox.osservio.store;

import android.content.Context;
import com.sandrox.osservio.core.BreadcrumbArchive;
import com.sandrox.osservio.core.BreadcrumbEvent;
import com.sandrox.osservio.core.BreadcrumbHistory;
import com.sandrox.osservio.core.BreadcrumbSink;
import com.sandrox.osservio.core.BreadcrumbSnapshot;
import com.sandrox.osservio.core.LogClock;
import com.sandrox.osservio.core.PersistenceStatus;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Live history plus bounded restart evidence. File operations and initialization run on one private worker. */
public final class SQLiteBreadcrumbStore implements BreadcrumbSink, BreadcrumbHistory, BreadcrumbArchive {
    public final String currentRunId = UUID.randomUUID().toString();
    private final MemoryBreadcrumbStore memory;
    private final BreadcrumbPersistenceOptions options;
    private final LogClock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "Osservio-breadcrumb-storage"); thread.setDaemon(true); return thread;
    });
    private PersistenceStatus.State state = PersistenceStatus.State.OPENING;
    private boolean accepting = true;
    private final List<BreadcrumbEvent> pending = new ArrayList<>();
    private int outstandingEvents;
    private int outstandingBytes;
    private int queryCount;
    private ScheduledFuture<?> scheduled;
    private long committed;
    private long discarded;
    private long failedBatches;
    private PersistenceStatus.Error lastError;
    // These resources are confined to the worker; callers only read immutable snapshots under the lock.
    private BreadcrumbDatabase database;
    private EvidenceDirectory directory;
    private interface DirectoryProvider { File directory(); }

    public SQLiteBreadcrumbStore(Context context) { this(context, new BreadcrumbPersistenceOptions()); }
    public SQLiteBreadcrumbStore(Context context, BreadcrumbPersistenceOptions options) { this(privateDirectory(context), options, LogClock.SYSTEM); }
    SQLiteBreadcrumbStore(File directory, BreadcrumbPersistenceOptions options, LogClock clock) { this(() -> directory, options, clock); }
    private static DirectoryProvider privateDirectory(Context context) {
        Context application = Objects.requireNonNull(context.getApplicationContext(), "applicationContext");
        return () -> new File(application.getNoBackupFilesDir(), "osservio/breadcrumbs");
    }
    private SQLiteBreadcrumbStore(DirectoryProvider path, BreadcrumbPersistenceOptions options, LogClock clock) {
        this.options = Objects.requireNonNull(options, "options"); this.clock = Objects.requireNonNull(clock, "clock");
        memory = new MemoryBreadcrumbStore(options.recordsPerRun, options.textBytesPerRun);
        worker.execute(() -> {
            try {
                directory = new EvidenceDirectory(path.directory());
                database = new BreadcrumbDatabase(directory.databaseFile, options, currentRunId, android.os.Process.myPid(), clock.wallTimeMillis());
                lock.lock(); try { if (accepting) state = PersistenceStatus.State.READY; } finally { lock.unlock(); }
            } catch (Exception failure) { fail(EvidenceStorageException.category(failure)); }
        });
    }
    @Override public WriteResult append(BreadcrumbEvent event) {
        if (!lock.tryLock()) return WriteResult.BUSY;
        try {
            if (!accepting) throw new EvidenceStorageException(PersistenceStatus.Error.CLOSED);
            WriteResult result = memory.append(event);
            if (result != WriteResult.STORED || event.retention != BreadcrumbEvent.Retention.RESTART_RELEVANT) return result;
            if (state == PersistenceStatus.State.FAILED || outstandingEvents >= options.pendingEventLimit || event.textByteCount > options.pendingTextByteLimit - outstandingBytes) {
                discarded = add(discarded, 1); return WriteResult.STORED;
            }
            pending.add(event); outstandingEvents++; outstandingBytes += event.textByteCount;
            if (scheduled == null) {
                scheduled = worker.schedule(() -> {
                    lock.lock(); try { scheduled = null; } finally { lock.unlock(); }
                    drain();
                }, options.flushIntervalMillis, TimeUnit.MILLISECONDS);
            }
            return WriteResult.STORED;
        } finally { lock.unlock(); }
    }
    @Override public BreadcrumbSnapshot snapshot() { return memory.snapshot(); }
    /** Clears live history only. Persisted runs have independent retention. */
    @Override public void clear() { memory.clear(); }
    @Override public PersistenceStatus persistenceStatus() {
        if (!lock.tryLock()) return null;
        try { return new PersistenceStatus(state, outstandingEvents, outstandingBytes, committed, discarded, failedBatches, lastError); }
        finally { lock.unlock(); }
    }
    @Override public void readRuns(RunsCompletion completion) { perform(completion::complete, false, BreadcrumbDatabase::runs); }
    @Override public void readRun(String id, RunCompletion completion) {
        Objects.requireNonNull(completion, "completion");
        if (!BreadcrumbDatabase.validId(id)) { completion.complete(null, PersistenceStatus.Error.INVALID_RECORD); return; }
        perform(completion::complete, false, db -> db.read(id));
    }
    @Override public void flush(Completion completion) {
        Objects.requireNonNull(completion, "completion");
        perform((ignored, error) -> completion.complete(error), true, db -> null);
    }
    @Override public void deleteRun(String id, Completion completion) {
        Objects.requireNonNull(completion, "completion");
        if (!BreadcrumbDatabase.validId(id)) { completion.complete(PersistenceStatus.Error.INVALID_RECORD); return; }
        perform((ignored, error) -> completion.complete(error), true, db -> { db.deleteRun(id); return null; });
    }
    @Override public void deleteAllRuns(Completion completion) {
        Objects.requireNonNull(completion, "completion");
        perform((ignored, error) -> completion.complete(error), true, db -> { db.deleteAllRuns(); return null; });
    }
    /** Nonblocking close. Completion follows queued work, final flush, and release of the directory lease. */
    public void close(Completion completion) {
        Objects.requireNonNull(completion, "completion");
        if (!lock.tryLock()) { completion.complete(PersistenceStatus.Error.BUSY); return; }
        if (state == PersistenceStatus.State.CLOSED) { lock.unlock(); completion.complete(null); return; }
        if (!accepting) { lock.unlock(); completion.complete(PersistenceStatus.Error.BUSY); return; }
        accepting = false; state = PersistenceStatus.State.CLOSING;
        if (scheduled != null) { scheduled.cancel(false); scheduled = null; }
        worker.execute(() -> {
            drain();
            releaseResources();
            lock.lock(); PersistenceStatus.Error error;
            try { state = PersistenceStatus.State.CLOSED; error = lastError; } finally { lock.unlock(); }
            worker.shutdown();
            completion.complete(error);
        });
        lock.unlock();
    }
    private interface Operation<T> { T run(BreadcrumbDatabase db); }
    private interface Delivery<T> { void complete(T result, PersistenceStatus.Error error); }
    private <T> void perform(Delivery<T> completion, boolean flush, Operation<T> operation) {
        if (!lock.tryLock()) { completion.complete(null, PersistenceStatus.Error.BUSY); return; }
        if (!accepting || queryCount >= 4) {
            PersistenceStatus.Error error = accepting ? PersistenceStatus.Error.BUSY : PersistenceStatus.Error.CLOSED;
            lock.unlock(); completion.complete(null, error); return;
        }
        queryCount++;
        worker.execute(() -> {
            if (flush) drain();
            T result = null;
            PersistenceStatus.Error error = null;
            if (database != null) {
                try { result = operation.run(database); }
                catch (RuntimeException failure) {
                    error = EvidenceStorageException.category(failure);
                    if (error == PersistenceStatus.Error.CORRUPT || error == PersistenceStatus.Error.INVALID_RECORD || error == PersistenceStatus.Error.UNSUPPORTED_SCHEMA) fail(error);
                }
            } else {
                lock.lock(); try { error = lastError == null ? PersistenceStatus.Error.UNAVAILABLE : lastError; } finally { lock.unlock(); }
            }
            lock.lock(); try { queryCount--; } finally { lock.unlock(); }
            // Host code runs after its permit is returned. Never retry a host callback that throws.
            completion.complete(result, error);
        });
        lock.unlock();
    }
    private void drain() {
        if (database == null) return;
        List<BreadcrumbEvent> batch;
        lock.lock(); try { batch = new ArrayList<>(pending); pending.clear(); } finally { lock.unlock(); }
        if (batch.isEmpty()) return;
        try {
            database.write(batch, clock.wallTimeMillis());
            int bytes = 0;
            for (BreadcrumbEvent event : batch) bytes += event.textByteCount;
            lock.lock();
            try { outstandingEvents -= batch.size(); outstandingBytes -= bytes; committed = add(committed, batch.size()); }
            finally { lock.unlock(); }
        } catch (RuntimeException failure) {
            lock.lock(); try { failedBatches = add(failedBatches, 1); } finally { lock.unlock(); }
            fail(EvidenceStorageException.category(failure));
        }
    }
    private void fail(PersistenceStatus.Error error) {
        lock.lock();
        try {
            state = accepting ? PersistenceStatus.State.FAILED : PersistenceStatus.State.CLOSING; lastError = error;
            discarded = add(discarded, outstandingEvents); pending.clear(); outstandingEvents = 0; outstandingBytes = 0;
        } finally { lock.unlock(); }
        releaseResources();
    }
    private void releaseResources() {
        try { if (database != null) database.close(); }
        catch (RuntimeException failure) { remember(EvidenceStorageException.category(failure)); }
        finally { database = null; }
        try { if (directory != null) directory.close(); }
        catch (IOException failure) { remember(PersistenceStatus.Error.UNAVAILABLE); }
        finally { directory = null; }
    }
    private void remember(PersistenceStatus.Error error) { lock.lock(); try { if (lastError == null) lastError = error; } finally { lock.unlock(); } }
    private static long add(long value, long increment) { return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment; }
}
