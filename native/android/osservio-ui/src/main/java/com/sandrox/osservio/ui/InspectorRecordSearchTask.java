package com.sandrox.osservio.ui;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One running search and one queued search across viewers; stale callbacks are discarded. */
final class InspectorRecordSearchTask implements Runnable {
    interface Work { InspectorRecordSearch.Result run(InspectorJsonDocument.Cancellation cancellation); }
    interface Completion { void complete(InspectorRecordSearch.Result result); }
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1), work -> { Thread thread = new Thread(work, "Osservio-Search"); thread.setDaemon(true); return thread; });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private final Work work;
    private final Completion completion;
    private volatile boolean cancelled;
    private InspectorRecordSearchTask(Work work, Completion completion) { this.work = work; this.completion = completion; }
    static InspectorRecordSearchTask submit(Work work, Completion completion) {
        InspectorRecordSearchTask task = new InspectorRecordSearchTask(work, completion);
        try { WORKER.execute(task); return task; }
        catch (RejectedExecutionException busy) { return null; }
    }
    void cancel() { cancelled = true; WORKER.remove(this); MAIN.removeCallbacksAndMessages(this); }
    @Override public void run() {
        if (cancelled) return;
        InspectorRecordSearch.Result result = work.run(() -> cancelled);
        if (cancelled || result == null) return;
        MAIN.postAtTime(() -> { if (!cancelled) completion.complete(result); }, this, android.os.SystemClock.uptimeMillis());
    }
}
