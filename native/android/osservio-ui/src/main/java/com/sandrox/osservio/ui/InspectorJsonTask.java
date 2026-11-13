package com.sandrox.osservio.ui;

import android.os.Handler;
import android.os.Looper;

/** One running parse and one pending parse. The worker retires when idle. */
final class InspectorJsonTask implements Runnable {
    interface Completion { void complete(InspectorJsonDocument document, InspectorJsonDocument.Failure failure); }
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private final String source;
    private final Completion completion;
    private volatile boolean cancelled;
    private InspectorJsonTask(String source, Completion completion) { this.source = source; this.completion = completion; }
    static InspectorJsonTask submit(String source, Completion completion) {
        InspectorJsonTask task = new InspectorJsonTask(source, completion);
        return InspectorReadWorker.submit(task) ? task : null;
    }
    void cancel() { cancelled = true; InspectorReadWorker.cancel(this); MAIN.removeCallbacksAndMessages(this); }
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
