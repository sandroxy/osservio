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
    interface ProgressiveWork { InspectorRecordSearch.Result run(InspectorJsonDocument.Cancellation cancellation, InspectorRecordSearch.Progress progress); }
    interface Completion { void complete(InspectorRecordSearch.Result result); }
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1), work -> { Thread thread = new Thread(work, "Osservio-Search"); thread.setDaemon(true); return thread; });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private final ProgressiveWork work;
    private final Completion completion;
    private volatile boolean cancelled;
    private InspectorRecordSearch.Result pendingResult;
    private boolean deliveryScheduled;
    private InspectorRecordSearchTask(ProgressiveWork work, Completion completion) { this.work = work; this.completion = completion; }
    static InspectorRecordSearchTask submit(Work work, Completion completion) {
        return submitProgressive((cancelled, progress) -> work.run(cancelled), completion);
    }
    static InspectorRecordSearchTask submitProgressive(ProgressiveWork work, Completion completion) {
        InspectorRecordSearchTask task = new InspectorRecordSearchTask(work, completion);
        try { WORKER.execute(task); return task; }
        catch (RejectedExecutionException busy) { return null; }
    }
    synchronized void cancel() { cancelled = true; pendingResult = null; WORKER.remove(this); MAIN.removeCallbacksAndMessages(this); }
    @Override public void run() {
        boolean tracing = InspectorPerformance.begin(InspectorPerformance.Point.RECORD_SEARCH);
        try {
            if (cancelled) return;
            InspectorRecordSearch.Result result = work.run(() -> cancelled, this::publish);
            if (cancelled || result == null) return;
            publish(result);
        } finally { InspectorPerformance.end(tracing); }
    }
    private synchronized void publish(InspectorRecordSearch.Result result) {
        if (cancelled) return;
        pendingResult = result;
        if (deliveryScheduled) return;
        deliveryScheduled = true;
        MAIN.postAtTime(this::deliver, this, android.os.SystemClock.uptimeMillis());
    }
    private void deliver() {
        InspectorRecordSearch.Result result;
        synchronized (this) { result = pendingResult; pendingResult = null; deliveryScheduled = false; }
        if (!cancelled && result != null) completion.complete(result);
    }
}
