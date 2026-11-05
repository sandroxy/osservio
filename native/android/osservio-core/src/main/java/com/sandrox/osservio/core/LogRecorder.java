package com.sandrox.osservio.core;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** A finite capture session. Capture uses try-lock admission and has no async queue. */
public final class LogRecorder {
    public enum Result {
        RECORDED, INACTIVE, RATE_LIMITED, BUSY, INVALID_CATEGORY, INVALID_MESSAGE,
        INVALID_LEVEL, TOO_LARGE, SINK_FAILURE, SEQUENCE_EXHAUSTED
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

    private final ReentrantLock lock = new ReentrantLock();
    private final LogSink sink;
    private final LogClock clock;
    private final int messageByteLimit;
    private final int eventsPerSecond;
    private boolean enabled;
    private long deadline;
    private String sessionId = "";
    private long sequence;
    private long windowStart;
    private int windowCount;
    private long rateLimited;

    public LogRecorder(LogSink sink) { this(sink, 4096, 200, LogClock.SYSTEM); }

    public LogRecorder(LogSink sink, int messageByteLimit, int eventsPerSecond, LogClock clock) {
        if (messageByteLimit < 9 || messageByteLimit > 65536 || eventsPerSecond < 1 || eventsPerSecond > 10000) {
            throw new IllegalArgumentException("Invalid log capture limit");
        }
        this.sink = Objects.requireNonNull(sink, "sink");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.messageByteLimit = messageByteLimit;
        this.eventsPerSecond = eventsPerSecond;
    }

    public void start() { start(900_000L); }

    /** Starts or replaces a session without clearing history. Maximum duration is one day. */
    public void start(long durationMillis) {
        if (durationMillis < 1 || durationMillis > 86_400_000L) {
            throw new IllegalArgumentException("Invalid capture duration");
        }
        if (!enter()) throw new IllegalStateException("Recorder is busy");
        try {
            long now = clock.monotonicMillis();
            if (now > Long.MAX_VALUE - durationMillis) throw new IllegalArgumentException("Invalid clock deadline");
            sessionId = UUID.randomUUID().toString();
            sequence = 0;
            deadline = now + durationMillis;
            enabled = true;
            windowStart = now;
            windowCount = 0;
        } finally { lock.unlock(); }
    }

    public boolean stop() {
        if (!enter()) return false;
        try { enabled = false; return true; }
        finally { lock.unlock(); }
    }

    /** Null means capture is currently using the state; retry later. */
    public Status status() {
        if (!enter()) return null;
        try {
            long now = clock.monotonicMillis();
            boolean recording = enabled && now < deadline;
            return new Status(recording, recording ? deadline - now : 0, rateLimited);
        } finally { lock.unlock(); }
    }

    /** Category is a static ASCII label, not a place for user or request data. */
    public Result record(LogLevel level, String category, LogMessage message) {
        if (!enter()) return Result.BUSY;
        try {
            long now = clock.monotonicMillis();
            if (!enabled || now >= deadline) return Result.INACTIVE;
            if (level == null) return Result.INVALID_LEVEL;
            if (!validCategory(category)) return Result.INVALID_CATEGORY;
            if (message == null || message.text == null) return Result.INVALID_MESSAGE;
            if (now >= windowStart && now - windowStart >= 1000L) {
                windowStart = now;
                windowCount = 0;
            }
            if (windowCount >= eventsPerSecond) {
                if (rateLimited < Long.MAX_VALUE) rateLimited++;
                return Result.RATE_LIMITED;
            }
            windowCount++;
            if (sequence == Long.MAX_VALUE) return Result.SEQUENCE_EXHAUSTED;
            sequence++;
            BoundedText bounded = bound(message.text, messageByteLimit);
            if (bounded == null) return Result.INVALID_MESSAGE;
            LogEvent event = new LogEvent(sessionId, sequence, clock.wallTimeMillis(), level,
                category, bounded.text, message.isPrivate, bounded.truncated);
            try {
                LogSink.WriteResult result = sink.append(event);
                if (result == LogSink.WriteResult.STORED) return Result.RECORDED;
                if (result == LogSink.WriteResult.BUSY) return Result.BUSY;
                if (result == LogSink.WriteResult.TOO_LARGE) return Result.TOO_LARGE;
                return Result.SINK_FAILURE;
            } catch (RuntimeException failure) {
                return Result.SINK_FAILURE;
            }
        } finally { lock.unlock(); }
    }

    private boolean enter() {
        return !lock.isHeldByCurrentThread() && lock.tryLock();
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

    private static final class BoundedText {
        final String text;
        final boolean truncated;
        BoundedText(String text, boolean truncated) { this.text = text; this.truncated = truncated; }
    }

    private static BoundedText bound(String value, int limit) {
        int offset = 0;
        int bytes = 0;
        while (offset < value.length()) {
            int point = value.codePointAt(offset);
            if (point >= 0xD800 && point <= 0xDFFF) return null;
            int size = point <= 0x7F ? 1 : point <= 0x7FF ? 2 : point <= 0xFFFF ? 3 : 4;
            if (bytes > limit - size) break;
            bytes += size;
            offset += Character.charCount(point);
        }
        return new BoundedText(value.substring(0, offset), offset < value.length());
    }
}
