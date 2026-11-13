package com.sandrox.osservio.core;

import java.nio.charset.StandardCharsets;

/** Immutable evidence after admission, privacy handling, and UTF-8 bounding. */
public final class LogEvent {
    public final String sessionId;
    public final long sequence;
    public final long timestampMillis;
    public final LogLevel level;
    public final String category;
    public final String message;
    public final boolean isPrivate;
    public final boolean isTruncated;
    public final int textByteCount;
    public final LogDetailsReference details;

    LogEvent(String sessionId, long sequence, long timestampMillis, LogLevel level,
             String category, String message, boolean isPrivate, boolean isTruncated) {
        this(sessionId, sequence, timestampMillis, level, category, message, isPrivate, isTruncated, null);
    }
    LogEvent(String sessionId, long sequence, long timestampMillis, LogLevel level,
             String category, String message, boolean isPrivate, boolean isTruncated, LogDetailsReference details) {
        this.sessionId = sessionId;
        this.sequence = sequence;
        this.timestampMillis = timestampMillis;
        this.level = level;
        this.category = category;
        this.message = message;
        this.isPrivate = isPrivate;
        this.isTruncated = isTruncated;
        this.details = details;
        this.textByteCount = category.getBytes(StandardCharsets.UTF_8).length
            + message.getBytes(StandardCharsets.UTF_8).length;
    }
    /** Restores sanitized evidence without regenerating identity or truncating invalid stored text. */
    public static LogEvent restore(String sessionId, long sequence, long timestampMillis, LogLevel level,
                                   String category, String message, boolean isPrivate, boolean isTruncated) {
        return restore(sessionId, sequence, timestampMillis, level, category, message, isPrivate, isTruncated, null);
    }
    public static LogEvent restore(String sessionId, long sequence, long timestampMillis, LogLevel level,
                                   String category, String message, boolean isPrivate, boolean isTruncated, LogDetailsReference details) {
        if (!EvidenceValidation.id(sessionId) || sequence <= 0 || level == null || !EvidenceValidation.label(category)
            || !EvidenceValidation.text(message, 65536) || (isPrivate && (!"[private]".equals(message) || isTruncated))) throw EvidenceValidation.invalid();
        return new LogEvent(sessionId, sequence, timestampMillis, level, category, message, isPrivate, isTruncated, details);
    }
}
