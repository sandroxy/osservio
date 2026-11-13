package com.sandrox.osservio.core;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLongArray;

/** Fixed-domain operation counters. Counts do not imply unique events or durable writes. */
public final class CaptureStatistics {
    public enum Source { LOGS, HTTP, WEB_SOCKET, BREADCRUMBS }
    public enum Outcome {
        ACCEPTED, INACTIVE, BUSY, RATE_LIMITED, ACTIVE_LIMIT, OVERSIZED, PROTECTED_CAPACITY,
        INVALID, MISSING, STALE, SINK_FAILURE, SEQUENCE_EXHAUSTED
    }
    private static final int OUTCOMES = Outcome.values().length;
    private final AtomicLongArray counts = new AtomicLongArray(Source.values().length * OUTCOMES);
    private final AtomicBoolean incomplete = new AtomicBoolean();
    private final long startedAtMillis;
    CaptureStatistics(long startedAtMillis) { this.startedAtMillis = startedAtMillis; }
    public static final class Snapshot {
        public final long startedAtMillis;
        /** Counters are independent; true also signals saturation or bookkeeping contention. */
        public final boolean isIncomplete;
        private final long[] counts;
        private Snapshot(long startedAtMillis, boolean incomplete, long[] counts) {
            this.startedAtMillis = startedAtMillis; isIncomplete = incomplete; this.counts = counts;
        }
        public long count(Source source, Outcome outcome) { return counts[source.ordinal() * OUTCOMES + outcome.ordinal()]; }
    }
    public Snapshot snapshot() {
        long[] values = new long[counts.length()];
        for (int i = 0; i < values.length; i++) values[i] = counts.get(i);
        return new Snapshot(startedAtMillis, incomplete.get(), values);
    }
    void record(Source source, Outcome outcome) {
        int index = source.ordinal() * OUTCOMES + outcome.ordinal();
        for (int attempt = 0; attempt < 4; attempt++) {
            long current = counts.get(index);
            if (current == Long.MAX_VALUE) { incomplete.set(true); return; }
            if (counts.compareAndSet(index, current, current + 1)) return;
        }
        incomplete.set(true);
    }

    LogRecorder.Result observe(LogRecorder.Result result) {
        Outcome outcome;
        switch (result) {
            case RECORDED: outcome = Outcome.ACCEPTED; break;
            case INACTIVE: outcome = Outcome.INACTIVE; break;
            case BUSY: outcome = Outcome.BUSY; break;
            case RATE_LIMITED: outcome = Outcome.RATE_LIMITED; break;
            case TOO_LARGE: outcome = Outcome.OVERSIZED; break;
            case SINK_FAILURE: outcome = Outcome.SINK_FAILURE; break;
            case SEQUENCE_EXHAUSTED: outcome = Outcome.SEQUENCE_EXHAUSTED; break;
            case INVALID_CATEGORY: outcome = Outcome.INVALID; break;
            case INVALID_MESSAGE: outcome = Outcome.INVALID; break;
            case PROTECTED_CAPACITY: outcome = Outcome.PROTECTED_CAPACITY; break;
            case INVALID_LEVEL: outcome = Outcome.INVALID; break;
            default: throw new IllegalStateException("Unknown recorder result");
        }
        record(Source.LOGS, outcome);
        return result;
    }
    BreadcrumbRecorder.Result observe(BreadcrumbRecorder.Result result) {
        Outcome outcome;
        switch (result) {
            case RECORDED: outcome = Outcome.ACCEPTED; break;
            case INACTIVE: outcome = Outcome.INACTIVE; break;
            case BUSY: outcome = Outcome.BUSY; break;
            case RATE_LIMITED: outcome = Outcome.RATE_LIMITED; break;
            case TOO_LARGE: outcome = Outcome.OVERSIZED; break;
            case SINK_FAILURE: outcome = Outcome.SINK_FAILURE; break;
            case SEQUENCE_EXHAUSTED: outcome = Outcome.SEQUENCE_EXHAUSTED; break;
            case INVALID_LABEL: outcome = Outcome.INVALID; break;
            case INVALID_MESSAGE: outcome = Outcome.INVALID; break;
            case INVALID_EVENT: outcome = Outcome.INVALID; break;
            default: throw new IllegalStateException("Unknown recorder result");
        }
        record(Source.BREADCRUMBS, outcome);
        return result;
    }
    HTTPRecorder.Result observe(HTTPRecorder.Result result) {
        Outcome outcome;
        switch (result) {
            case RECORDED: outcome = Outcome.ACCEPTED; break;
            case INACTIVE: outcome = Outcome.INACTIVE; break;
            case BUSY: outcome = Outcome.BUSY; break;
            case RATE_LIMITED: outcome = Outcome.RATE_LIMITED; break;
            case TOO_LARGE: outcome = Outcome.OVERSIZED; break;
            case SINK_FAILURE: outcome = Outcome.SINK_FAILURE; break;
            case SEQUENCE_EXHAUSTED: outcome = Outcome.SEQUENCE_EXHAUSTED; break;
            case STALE: outcome = Outcome.STALE; break;
            case ACTIVE_LIMIT: outcome = Outcome.ACTIVE_LIMIT; break;
            case INVALID: outcome = Outcome.INVALID; break;
            case MISSING: outcome = Outcome.MISSING; break;
            default: throw new IllegalStateException("Unknown recorder result");
        }
        record(Source.HTTP, outcome);
        return result;
    }
    WebSocketRecorder.Result observe(WebSocketRecorder.Result result) {
        Outcome outcome;
        switch (result) {
            case RECORDED: outcome = Outcome.ACCEPTED; break;
            case INACTIVE: outcome = Outcome.INACTIVE; break;
            case BUSY: outcome = Outcome.BUSY; break;
            case RATE_LIMITED: outcome = Outcome.RATE_LIMITED; break;
            case TOO_LARGE: outcome = Outcome.OVERSIZED; break;
            case SINK_FAILURE: outcome = Outcome.SINK_FAILURE; break;
            case SEQUENCE_EXHAUSTED: outcome = Outcome.SEQUENCE_EXHAUSTED; break;
            case STALE: outcome = Outcome.STALE; break;
            case INVALID: outcome = Outcome.INVALID; break;
            case CONNECTION_LIMIT: outcome = Outcome.ACTIVE_LIMIT; break;
            default: throw new IllegalStateException("Unknown recorder result");
        }
        record(Source.WEB_SOCKET, outcome);
        return result;
    }
}
