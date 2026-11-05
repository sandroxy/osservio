package com.sandrox.osservio.core;

/** Per-instance diagnostics. An in-memory append is not a durable commit. */
public final class PersistenceStatus {
    public enum State { OPENING, READY, FAILED, CLOSING, CLOSED }
    public enum Error { BUSY, CLOSED, UNAVAILABLE, UNSUPPORTED_SCHEMA, CORRUPT, STORAGE_FULL, INVALID_RECORD }
    public final State state;
    public final int queuedEvents;
    public final int queuedTextBytes;
    public final long committedEvents;
    public final long discardedEvents;
    public final long failedBatches;
    public final Error lastError;
    public PersistenceStatus(State state, int queuedEvents, int queuedTextBytes, long committedEvents,
                             long discardedEvents, long failedBatches, Error lastError) {
        this.state = state; this.queuedEvents = queuedEvents; this.queuedTextBytes = queuedTextBytes;
        this.committedEvents = committedEvents; this.discardedEvents = discardedEvents;
        this.failedBatches = failedBatches; this.lastError = lastError;
    }
}
