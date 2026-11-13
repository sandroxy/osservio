package com.sandrox.osservio.core;

import java.util.Objects;

/** Compact context is admitted in baseline and detailed capture; off admits neither. */
public final class BreadcrumbRecorder {
    public enum Result { RECORDED, INACTIVE, BUSY, INVALID_LABEL, INVALID_MESSAGE, INVALID_EVENT, RATE_LIMITED, TOO_LARGE, SINK_FAILURE, SEQUENCE_EXHAUSTED }
    public static final class Status {
        public final long rateLimitedCount;
        private Status(long rateLimitedCount) { this.rateLimitedCount = rateLimitedCount; }
    }
    public final CaptureController captureController;
    private final BreadcrumbSink sink;
    private final int messageByteLimit;
    private final int eventsPerSecond;
    private String sessionId = "";
    private long windowStart;
    private int windowCount;
    private long rateLimited;

    public BreadcrumbRecorder(BreadcrumbSink sink, CaptureController captureController) { this(sink, captureController, 1024, 20); }
    public BreadcrumbRecorder(BreadcrumbSink sink, CaptureController captureController, int messageByteLimit, int eventsPerSecond) {
        if (messageByteLimit < 9 || messageByteLimit > 4096 || eventsPerSecond < 1 || eventsPerSecond > 1000) {
            throw new IllegalArgumentException("Invalid breadcrumb capture limit");
        }
        this.sink = Objects.requireNonNull(sink, "sink"); this.captureController = Objects.requireNonNull(captureController, "captureController");
        this.messageByteLimit = messageByteLimit; this.eventsPerSecond = eventsPerSecond;
    }
    public Result record(BreadcrumbEvent.Kind kind, String label) { return record(kind, label, null, null, BreadcrumbEvent.Retention.MEMORY_ONLY); }
    public Result record(BreadcrumbEvent.Kind kind, String label, LogMessage message, Long value, BreadcrumbEvent.Retention retention) {
        CaptureController.Access<Result> access = captureController.record(false, context -> ingest(context, kind, label, message, value, retention));
        switch (access.admission) {
            case VALUE: return captureController.statistics.observe(access.value);
            case INACTIVE: return captureController.statistics.observe(Result.INACTIVE);
            case BUSY: return captureController.statistics.observe(Result.BUSY);
            case SEQUENCE_EXHAUSTED: return captureController.statistics.observe(Result.SEQUENCE_EXHAUSTED);
            default: throw new IllegalStateException("Unknown capture admission");
        }
    }
    public Status status() { return captureController.inspect(ignored -> new Status(rateLimited)); }
    private Result ingest(CaptureController.Context context, BreadcrumbEvent.Kind kind, String label, LogMessage message,
                          Long value, BreadcrumbEvent.Retention retention) {
        if (kind == null || retention == null) return Result.INVALID_EVENT;
        if (!validLabel(label)) return Result.INVALID_LABEL;
        if (!sessionId.equals(context.sessionId)) { sessionId = context.sessionId; windowStart = context.sessionStartMillis; windowCount = 0; }
        if (context.monotonicMillis >= windowStart && context.monotonicMillis - windowStart >= 1000) {
            windowStart = context.monotonicMillis; windowCount = 0;
        }
        if (windowCount >= eventsPerSecond) { if (rateLimited < Long.MAX_VALUE) rateLimited++; return Result.RATE_LIMITED; }
        windowCount++;
        BoundedText bounded = null;
        if (message != null) {
            if (message.text == null) return Result.INVALID_MESSAGE;
            bounded = BoundedText.prefix(message.text, messageByteLimit);
            if (bounded == null) return Result.INVALID_MESSAGE;
        }
        BreadcrumbEvent event = new BreadcrumbEvent(context, captureController.clock.wallTimeMillis(), kind, label,
            bounded == null ? null : bounded.text, message != null && message.isPrivate,
            bounded != null && bounded.truncated, value, retention);
        try {
            BreadcrumbSink.WriteResult result = sink.append(event);
            if (result == BreadcrumbSink.WriteResult.STORED) return Result.RECORDED;
            if (result == BreadcrumbSink.WriteResult.BUSY) return Result.BUSY;
            if (result == BreadcrumbSink.WriteResult.TOO_LARGE) return Result.TOO_LARGE;
            return Result.SINK_FAILURE;
        } catch (RuntimeException failure) { return Result.SINK_FAILURE; }
    }
    static boolean validLabel(String label) {
        if (label == null || label.isEmpty() || label.length() > 64) return false;
        for (int i = 0; i < label.length(); i++) {
            char value = label.charAt(i);
            if (!((value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z') || (value >= '0' && value <= '9')
                || value == '-' || value == '.' || value == '_')) return false;
        }
        return true;
    }
}
