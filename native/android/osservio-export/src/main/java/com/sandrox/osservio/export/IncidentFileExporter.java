package com.sandrox.osservio.export;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import androidx.core.content.FileProvider;
import com.sandrox.osservio.core.IncidentExporter;
import com.sandrox.osservio.core.IncidentSnapshot;
import com.sandrox.osservio.core.LogClock;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/** Bounded asynchronous ZIP preparation. Retain at host scope; all I/O occurs on the private worker. */
public final class IncidentFileExporter implements IncidentExporter {
    public interface CloseCompletion { void complete(Error error); }
    private interface DirectoryProvider { File directory(); }
    private final DirectoryProvider directory;
    private final Context application;
    private final IncidentExportOptions options;
    private final LogClock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Osservio-export"); thread.setDaemon(true); return thread;
    });
    private boolean accepting = true;
    private boolean closed;
    private ExportTask active;
    private ExportCache cache; // Worker-confined.

    public IncidentFileExporter(Context context) { this(context, new IncidentExportOptions()); }
    public IncidentFileExporter(Context context, IncidentExportOptions options) { this(context, privateDirectory(context), options, LogClock.SYSTEM); }
    IncidentFileExporter(Context context, File directory, IncidentExportOptions options, LogClock clock) { this(context, () -> directory, options, clock); }
    private IncidentFileExporter(Context context, DirectoryProvider directory, IncidentExportOptions options, LogClock clock) {
        this.application = Objects.requireNonNull(context.getApplicationContext(), "applicationContext");
        this.directory = directory; this.options = Objects.requireNonNull(options, "options"); this.clock = Objects.requireNonNull(clock, "clock");
    }
    private static DirectoryProvider privateDirectory(Context context) {
        Context application = Objects.requireNonNull(context.getApplicationContext(), "applicationContext");
        return () -> {
            File parent = application.getCacheDir();
            if (parent == null || !parent.isAbsolute()) throw new IllegalStateException("Private export cache is unavailable");
            return new File(parent, "osservio/exports");
        };
    }
    @Override public Task prepare(IncidentSnapshot snapshot, Completion completion) {
        Objects.requireNonNull(snapshot, "snapshot"); Objects.requireNonNull(completion, "completion");
        if (snapshot.textByteCount > IncidentDocuments.MAXIMUM_TEXT_BYTES) { completion.complete(null, Error.TOO_LARGE); return null; }
        if (!lock.tryLock()) { completion.complete(null, Error.BUSY); return null; }
        if (!accepting || active != null) {
            Error error = accepting ? Error.BUSY : Error.CLOSED; lock.unlock(); completion.complete(null, error); return null;
        }
        ExportTask task = new ExportTask(); active = task;
        worker.execute(() -> {
            PreparedFile result = null; Error error = null; File partial = null; File completed = null;
            try {
                task.check();
                if (cache == null) cache = new ExportCache(directory.directory(), options);
                cache.clean(clock.wallTimeMillis()); task.check();
                IncidentDocuments documents = IncidentDocuments.encode(snapshot); task.check();
                partial = cache.newPartial(); long size = IncidentZIP.write(documents, partial, task); task.check();
                completed = cache.commit(partial, size, clock.wallTimeMillis());
                URI address;
                try { address = URI.create(FileProvider.getUriForFile(application, application.getPackageName() + ".osservio.exports", completed).toString()); }
                catch (IllegalArgumentException unavailableProvider) { throw new ExportFailure(Error.UNAVAILABLE); }
                result = new PreparedFile(completed, address, size);
            } catch (Exception failure) { error = ExportFailure.category(failure); }
            finally {
                if (partial != null) discard(partial);
                if (result == null && completed != null) discard(completed);
            }
            if (task.finish()) { if (result != null) discard(result.file); result = null; error = Error.CANCELLED; }
            lock.lock(); try { active = null; } finally { lock.unlock(); }
            completion.complete(result, error);
        });
        lock.unlock(); return task;
    }
    /** Cancels admitted work, then releases directory ownership. Completed cache files are retained. */
    public void close(CloseCompletion completion) {
        Objects.requireNonNull(completion, "completion");
        if (!lock.tryLock()) { completion.complete(Error.BUSY); return; }
        if (closed) { lock.unlock(); completion.complete(null); return; }
        if (!accepting) { lock.unlock(); completion.complete(Error.BUSY); return; }
        accepting = false; if (active != null) active.cancel();
        worker.execute(() -> {
            Error error = null;
            try { if (cache != null) cache.close(); } catch (IOException failure) { error = ExportFailure.category(failure); }
            cache = null;
            lock.lock(); try { closed = true; } finally { lock.unlock(); }
            completion.complete(error);
        });
        worker.shutdown(); lock.unlock();
    }
    private static void discard(File file) {
        // A failed removal leaves at most one recognized file; the next preparation retries cleanup first.
        // It must not conceal the original cancellation or I/O error.
        try { Os.remove(file.getAbsolutePath()); } catch (ErrnoException ignored) { /* Retried by cache cleanup. */ }
    }
}
