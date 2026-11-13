package com.sandrox.osservio.ui;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** JSON and form readers share one running parse and one pending parse. */
final class InspectorReadWorker {
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1), work -> { Thread thread = new Thread(work, "Osservio-Reader"); thread.setDaemon(true); return thread; });
    private InspectorReadWorker() { }
    static boolean submit(Runnable task) {
        try { WORKER.execute(task); return true; } catch (RejectedExecutionException busy) { return false; }
    }
    static void cancel(Runnable task) { WORKER.remove(task); }
}
