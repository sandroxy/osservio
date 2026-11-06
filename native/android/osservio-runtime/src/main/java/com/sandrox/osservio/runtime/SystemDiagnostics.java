package com.sandrox.osservio.runtime;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import com.sandrox.osservio.core.SystemDiagnostic;
import com.sandrox.osservio.core.SystemDiagnosticBatch;
import com.sandrox.osservio.core.SystemDiagnosticProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded system history reads with selected API 31+ native tombstone evidence. Installs no handlers. */
public final class SystemDiagnostics implements SystemDiagnosticProvider, AutoCloseable {
    interface Source { SystemDiagnosticBatch read(); }
    private final Source source;
    private final ExecutorService worker;
    private final AtomicBoolean pending = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    public SystemDiagnostics(Context context) {
        Context app = Objects.requireNonNull(context.getApplicationContext(), "applicationContext");
        source = Build.VERSION.SDK_INT >= 30 ? new ExitSource(app) : null;
        worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "Osservio-system-diagnostics"); thread.setDaemon(true); return thread;
        });
    }
    SystemDiagnostics(Source source, ExecutorService worker) { this.source = source; this.worker = worker; }
    @Override public void read(Completion completion) {
        Objects.requireNonNull(completion, "completion");
        if (closed.get()) { completion.complete(null, Error.CLOSED); return; }
        if (source == null) { completion.complete(null, Error.UNSUPPORTED); return; }
        if (!pending.compareAndSet(false, true)) { completion.complete(null, Error.BUSY); return; }
        try {
            worker.execute(() -> {
                SystemDiagnosticBatch batch = null; Error error = null;
                try { batch = source.read(); if (batch == null) error = Error.UNAVAILABLE; }
                catch (RuntimeException failure) { error = Error.UNAVAILABLE; }
                finally { pending.set(false); }
                // A consumer callback is outside the diagnostic catch and is never retried.
                completion.complete(batch, error);
            });
        } catch (RejectedExecutionException failure) {
            pending.set(false); completion.complete(null, closed.get() ? Error.CLOSED : Error.UNAVAILABLE);
        }
    }
    /** Rejects new reads; an already admitted read completes normally. Does not alter system history. */
    @Override public void close() { closed.set(true); worker.shutdown(); }

    private static final class ExitSource implements Source {
        private final Context context;
        ExitSource(Context context) { this.context = context; }
        @Override public SystemDiagnosticBatch read() {
            if (Build.VERSION.SDK_INT < 30) throw new IllegalStateException("Process exit history is unsupported");
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            if (manager == null) throw new IllegalStateException("Activity manager unavailable");
            List<ApplicationExitInfo> records = manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 32);
            List<SystemDiagnostic> chosen = new ArrayList<>();
            int rejected = 0, excluded = 0;
            boolean traceLimitReached = false;
            TombstoneReader tombstones = new TombstoneReader();
            String mainProcess = context.getApplicationInfo().processName;
            int packageUid = context.getApplicationInfo().uid;
            for (int i = 0; i < Math.min(32, records.size()); i++) {
                ApplicationExitInfo record = records.get(i);
                if (record == null) { rejected++; continue; }
                // OS history may include isolated/external services; do not attribute them to the main host process.
                if (mainProcess == null || !mainProcess.equals(record.getProcessName()) || record.getPackageUid() != packageUid) {
                    excluded++; continue;
                }
                try {
                    long pss = record.getPss(), rss = record.getRss();
                    SystemDiagnostic facts = SystemDiagnostic.androidExit(record.getTimestamp(), record.getReason(), record.getStatus(),
                        record.getPid(), record.getImportance(), pss == 0 ? null : pss, rss == 0 ? null : rss);
                    if (facts.kind == SystemDiagnostic.Kind.NATIVE_CRASH) {
                        SystemDiagnostic.AndroidExit exit = facts.androidExit;
                        TombstoneReader.Result trace = Build.VERSION.SDK_INT >= 31 ? tombstones.read(record::getTraceInputStream, exit.processID) :
                            new TombstoneReader.Result(SystemDiagnostic.TombstoneState.UNSUPPORTED, null);
                        traceLimitReached |= trace.state == SystemDiagnostic.TombstoneState.LIMIT_REACHED;
                        facts = SystemDiagnostic.androidExit(facts.intervalStartMillis, exit.reasonCode, exit.status, exit.processID,
                            exit.importance, exit.pssKiB, exit.rssKiB, trace.state, trace.evidence);
                    }
                    chosen.add(facts);
                } catch (IllegalArgumentException invalid) { rejected++; }
            }
            return new SystemDiagnosticBatch(SystemDiagnostic.Source.ANDROID_EXIT_INFO, System.currentTimeMillis(), chosen,
                rejected, excluded, records.size() >= 32 || traceLimitReached);
        }
    }
}
