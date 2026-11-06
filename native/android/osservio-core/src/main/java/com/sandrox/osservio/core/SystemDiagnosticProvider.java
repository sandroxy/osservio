package com.sandrox.osservio.core;

/** Explicit asynchronous history reads. Completion may run on any thread, including immediate rejection. */
public interface SystemDiagnosticProvider {
    enum Error { BUSY, UNSUPPORTED, CLOSED, UNAVAILABLE }
    interface Completion { void complete(SystemDiagnosticBatch batch, Error error); }
    void read(Completion completion);
}
