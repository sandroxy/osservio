package com.sandrox.osservio.export;

import com.sandrox.osservio.core.IncidentExporter;

/** Cancellation and completion share one linearization point; no callback executes under this monitor. */
final class ExportTask implements IncidentExporter.Task {
    private boolean cancelled;
    private boolean finished;
    @Override public synchronized void cancel() { if (!finished) cancelled = true; }
    synchronized void check() throws ExportFailure { if (cancelled) throw new ExportFailure(IncidentExporter.Error.CANCELLED); }
    synchronized boolean finish() { finished = true; return cancelled; }
}
