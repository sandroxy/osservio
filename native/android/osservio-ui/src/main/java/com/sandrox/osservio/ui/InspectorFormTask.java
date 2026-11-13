package com.sandrox.osservio.ui;

import android.os.Handler;
import android.os.Looper;

/** One running parse and one pending parse. The worker retires when idle. */
final class InspectorFormTask implements Runnable {
    interface Completion { void complete(InspectorFormDocument document, InspectorFormDocument.Failure failure); }
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private final String source;
    private final Completion completion;
    private volatile boolean cancelled;
    private InspectorFormTask(String source, Completion completion) { this.source = source; this.completion = completion; }
    static InspectorFormTask submit(String source, Completion completion) {
        InspectorFormTask task = new InspectorFormTask(source, completion);
        return InspectorReadWorker.submit(task) ? task : null;
    }
    void cancel() { cancelled = true; InspectorReadWorker.cancel(this); MAIN.removeCallbacksAndMessages(this); }
    @Override public void run() {
        if (cancelled) return;
        InspectorFormDocument document = null; InspectorFormDocument.Failure failure = null;
        try { document = InspectorFormDocument.parse(source, () -> cancelled); }
        catch (InspectorFormDocument.ParseFailure invalid) { failure = invalid.reason; }
        if (cancelled) return;
        InspectorFormDocument result = document; InspectorFormDocument.Failure reason = failure;
        MAIN.postAtTime(() -> { if (!cancelled) completion.complete(result, reason); }, this, android.os.SystemClock.uptimeMillis());
    }
}
