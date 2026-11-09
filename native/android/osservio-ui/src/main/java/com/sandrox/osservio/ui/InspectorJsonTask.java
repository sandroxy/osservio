package com.sandrox.osservio.ui;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One running parse and one pending parse. The worker retires when idle. */
final class InspectorJsonTask implements Runnable {
    interface Completion { void complete(InspectorJsonDocument document, InspectorJsonDocument.Failure failure); }
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1), work -> { Thread thread = new Thread(work, "Osservio-JSON"); thread.setDaemon(true); return thread; });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private final String source;
    private final Completion completion;
    private volatile boolean cancelled;
    private InspectorJsonTask(String source, Completion completion) { this.source = source; this.completion = completion; }
    static InspectorJsonTask submit(String source, Completion completion) {
        InspectorJsonTask task = new InspectorJsonTask(source, completion);
        try { WORKER.execute(task); return task; }
        catch (RejectedExecutionException busy) { return null; }
    }
    void cancel() { cancelled = true; WORKER.remove(this); MAIN.removeCallbacksAndMessages(this); }
    @Override public void run() {
        if (cancelled) return;
        InspectorJsonDocument document = null; InspectorJsonDocument.Failure failure = null;
        try { document = InspectorJsonDocument.parse(source, () -> cancelled); }
        catch (InspectorJsonDocument.ParseFailure invalid) { failure = invalid.reason; }
        if (cancelled) return;
        InspectorJsonDocument result = document; InspectorJsonDocument.Failure reason = failure;
        MAIN.postAtTime(() -> { if (!cancelled) completion.complete(result, reason); }, this, android.os.SystemClock.uptimeMillis());
    }
}
