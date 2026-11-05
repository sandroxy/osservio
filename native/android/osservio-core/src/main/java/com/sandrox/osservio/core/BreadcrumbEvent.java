package com.sandrox.osservio.core;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Immutable admitted context, not an inferred cause of a crash or blank screen. */
public final class BreadcrumbEvent {
    public enum Kind {
        HOST_MARKER, APP_ACTIVE, APP_INACTIVE, APP_FOREGROUND, APP_BACKGROUND,
        ACTIVITY_STARTED, ACTIVITY_RESUMED, ACTIVITY_PAUSED, ACTIVITY_STOPPED, ACTIVITY_DESTROYED,
        MEMORY_WARNING, MEMORY_TRIM, THERMAL_STATE, RESPONSIVENESS
    }
    public enum Retention { MEMORY_ONLY, RESTART_RELEVANT }
    public final String id;
    public final String sessionId;
    public final long sequence;
    public final long timestampMillis;
    /** Elapsed monotonic time in this capture session, never device boot time. */
    public final Long sessionElapsedMillis;
    public final Kind kind;
    /** Static ASCII label, never a user ID, URL, or arbitrary object description. */
    public final String label;
    public final String message;
    public final boolean isPrivate;
    public final boolean isTruncated;
    /** Platform code or measured value; its meaning depends on kind and label. */
    public final Long value;
    public final Retention retention;
    public final int textByteCount;
    BreadcrumbEvent(CaptureController.Context context, long timestamp, Kind kind, String label, String message,
                    boolean isPrivate, boolean isTruncated, Long value, Retention retention) {
        id = UUID.randomUUID().toString(); sessionId = context.sessionId; sequence = context.sequence;
        timestampMillis = timestamp;
        sessionElapsedMillis = context.monotonicMillis >= context.sessionStartMillis
            ? context.monotonicMillis - context.sessionStartMillis : null;
        this.kind = kind; this.label = label; this.message = message; this.isPrivate = isPrivate;
        this.isTruncated = isTruncated; this.value = value; this.retention = retention;
        textByteCount = label.getBytes(StandardCharsets.UTF_8).length + (message == null ? 0 : message.getBytes(StandardCharsets.UTF_8).length);
    }
    /** Reconstitutes sanitized evidence, never classifies raw text or silently repairs invalid persisted values. */
    public static BreadcrumbEvent restore(String id, String sessionId, long sequence, long timestampMillis, Long sessionElapsedMillis,
                                          Kind kind, String label, String message, boolean isPrivate, boolean isTruncated,
                                          Long value, Retention retention) {
        if (!validUUID(id) || !validUUID(sessionId) || sequence <= 0 || (sessionElapsedMillis != null && sessionElapsedMillis < 0)
            || kind == null || retention == null || !BreadcrumbRecorder.validLabel(label)
            || (isPrivate && (!"[private]".equals(message) || isTruncated)) || (isTruncated && message == null)) throw invalid();
        if (message != null) {
            BoundedText bounded = BoundedText.prefix(message, 4096);
            if (bounded == null || bounded.truncated) throw invalid();
        }
        return new BreadcrumbEvent(id, sessionId, sequence, timestampMillis, sessionElapsedMillis, kind, label, message,
            isPrivate, isTruncated, value, retention);
    }
    private BreadcrumbEvent(String id, String sessionId, long sequence, long timestampMillis, Long sessionElapsedMillis,
                            Kind kind, String label, String message, boolean isPrivate, boolean isTruncated, Long value, Retention retention) {
        this.id = id; this.sessionId = sessionId; this.sequence = sequence; this.timestampMillis = timestampMillis;
        this.sessionElapsedMillis = sessionElapsedMillis; this.kind = kind; this.label = label; this.message = message;
        this.isPrivate = isPrivate; this.isTruncated = isTruncated; this.value = value; this.retention = retention;
        textByteCount = label.getBytes(StandardCharsets.UTF_8).length + (message == null ? 0 : message.getBytes(StandardCharsets.UTF_8).length);
    }
    private static boolean validUUID(String id) {
        if (id == null || id.length() != 36) return false;
        for (int i = 0; i < 36; i++) {
            char c = id.charAt(i);
            if (i == 8 || i == 13 || i == 18 || i == 23) { if (c != '-') return false; }
            else if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
        }
        return true;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid retained breadcrumb"); }
}
