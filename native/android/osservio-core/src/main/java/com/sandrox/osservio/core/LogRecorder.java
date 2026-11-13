package com.sandrox.osservio.core;

import java.util.Objects;

/** A finite capture session. Capture uses try-lock admission and has no async queue. */
public final class LogRecorder {
    public enum Result {
        RECORDED, INACTIVE, RATE_LIMITED, BUSY, INVALID_CATEGORY, INVALID_MESSAGE,
        INVALID_LEVEL, TOO_LARGE, PROTECTED_CAPACITY, SINK_FAILURE, SEQUENCE_EXHAUSTED
    }

    public static final class Status {
        public final boolean isRecording;
        public final long remainingMillis;
        public final long rateLimitedCount;

        private Status(boolean recording, long remaining, long limited) {
            isRecording = recording;
            remainingMillis = remaining;
            rateLimitedCount = limited;
        }
    }

    public final CaptureController captureController;
    private final LogSink sink;
    private final int messageByteLimit;
    private final int eventsPerSecond;
    private final int errorReservePerSecond;
    private String sessionId = "";
    private long windowStart;
    private int windowCount;
    private int reservedErrors;
    private long rateLimited;

    public LogRecorder(LogSink sink) { this(sink, 4096, 200, LogClock.SYSTEM); }

    public LogRecorder(LogSink sink, int messageByteLimit, int eventsPerSecond, LogClock clock) {
        this(sink, new CaptureController(false, clock), messageByteLimit, eventsPerSecond);
    }

    public LogRecorder(LogSink sink, CaptureController captureController) {
        this(sink, captureController, 4096, 200);
    }

    public LogRecorder(LogSink sink, CaptureController captureController, int messageByteLimit, int eventsPerSecond) {
        this(sink, captureController, messageByteLimit, eventsPerSecond, Math.min(20, Math.max(1, eventsPerSecond / 10)));
    }

    public LogRecorder(LogSink sink, CaptureController captureController, int messageByteLimit, int eventsPerSecond, int errorReservePerSecond) {
        if (messageByteLimit < 9 || messageByteLimit > 65536 || eventsPerSecond < 1 || eventsPerSecond > 10000
            || errorReservePerSecond < 0 || errorReservePerSecond > 100) {
            throw new IllegalArgumentException("Invalid log capture limit");
        }
        this.sink = Objects.requireNonNull(sink, "sink");
        this.captureController = Objects.requireNonNull(captureController, "captureController");
        this.messageByteLimit = messageByteLimit;
        this.eventsPerSecond = eventsPerSecond;
        this.errorReservePerSecond = errorReservePerSecond;
    }

    public void start() { start(900_000L); }

    /** Starts detailed capture for all recorders sharing this controller, without clearing history. */
    public void start(long durationMillis) {
        captureController.startDetailed(durationMillis);
    }

    /** Ends shared detailed capture and restores the configured baseline or off state. */
    public boolean stop() {
        return captureController.stopDetailed();
    }

    /** Null means capture is currently using the state; retry later. */
    public Status status() {
        return captureController.inspect(state ->
            new Status(state.mode == CaptureController.Mode.DETAILED, state.remainingMillis, rateLimited));
    }

    /** Category is a static ASCII label, not a place for user or request data. */
    public Result record(LogLevel level, String category, LogMessage message) {
        CaptureController.Access<Result> result = captureController.record(true,
            context -> ingest(level, category, message, context));
        switch (result.admission) {
            case VALUE: return result.value;
            case INACTIVE: return Result.INACTIVE;
            case BUSY: return Result.BUSY;
            case SEQUENCE_EXHAUSTED: return Result.SEQUENCE_EXHAUSTED;
            default: return Result.SINK_FAILURE;
        }
    }

    private Result ingest(LogLevel level, String category, LogMessage message, CaptureController.Context context) {
        long now = context.monotonicMillis;
        if (!sessionId.equals(context.sessionId)) {
            sessionId = context.sessionId;
            windowStart = context.sessionStartMillis;
            windowCount = 0;
            reservedErrors = 0;
        }
        if (level == null) return Result.INVALID_LEVEL;
        if (!validCategory(category)) return Result.INVALID_CATEGORY;
        if (message == null || message.text == null) return Result.INVALID_MESSAGE;
        if (now >= windowStart && now - windowStart >= 1000L) {
            windowStart = now;
            windowCount = 0;
            reservedErrors = 0;
        }
        if (windowCount < eventsPerSecond) windowCount++;
        else if (level == LogLevel.ERROR && reservedErrors < errorReservePerSecond) reservedErrors++;
        else {
            if (rateLimited < Long.MAX_VALUE) rateLimited++;
            return Result.RATE_LIMITED;
        }
        BoundedText bounded = BoundedText.prefix(message.text, messageByteLimit);
        if (bounded == null) return Result.INVALID_MESSAGE;
        LogEvent event = new LogEvent(sessionId, context.sequence, captureController.clock.wallTimeMillis(), level,
            category, bounded.text, message.isPrivate, bounded.truncated);
        try {
            LogSink.WriteResult result = sink.append(event);
            if (result == LogSink.WriteResult.STORED) return Result.RECORDED;
            if (result == LogSink.WriteResult.BUSY) return Result.BUSY;
            if (result == LogSink.WriteResult.TOO_LARGE) return Result.TOO_LARGE;
            if (result == LogSink.WriteResult.PROTECTED_CAPACITY) return Result.PROTECTED_CAPACITY;
            return Result.SINK_FAILURE;
        } catch (RuntimeException failure) {
            return Result.SINK_FAILURE;
        }
    }

    private static boolean validCategory(String value) {
        if (value == null || value.isEmpty() || value.length() > 64) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_')) return false;
        }
        return true;
    }

}
