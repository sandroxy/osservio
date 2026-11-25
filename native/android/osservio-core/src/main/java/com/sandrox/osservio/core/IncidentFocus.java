package com.sandrox.osservio.core;

/** Explicitly selected evidence, identified independently of a localized title or note. */
public final class IncidentFocus {
    public enum Kind { LOG, HTTP, WEB_SOCKET }
    public final Kind kind;
    public final String sessionId;
    public final long sequence;
    /** Logs use session and sequence; network observations additionally retain their event ID. */
    public final String eventId;
    private IncidentFocus(Kind kind, String sessionId, long sequence, String eventId) {
        this.kind = kind; this.sessionId = sessionId; this.sequence = sequence; this.eventId = eventId;
    }
    public static IncidentFocus log(LogEvent event) { return restore(Kind.LOG, event.sessionId, event.sequence, null); }
    public static IncidentFocus http(HTTPEvent event) { return restore(Kind.HTTP, event.sessionId, event.sequence, event.id); }
    public static IncidentFocus webSocket(WebSocketEvent event) { return restore(Kind.WEB_SOCKET, event.sessionId, event.sequence, event.id); }
    public static IncidentFocus restore(Kind kind, String sessionId, long sequence, String eventId) {
        if (kind == null || !EvidenceValidation.id(sessionId) || sequence <= 0
            || (kind == Kind.LOG ? eventId != null : !EvidenceValidation.id(eventId))) throw EvidenceValidation.invalid();
        return new IncidentFocus(kind, sessionId, sequence, eventId);
    }
    public boolean matches(LogEvent event) { return kind == Kind.LOG && sameSession(event.sessionId, event.sequence); }
    public boolean matches(HTTPEvent event) { return kind == Kind.HTTP && sameSession(event.sessionId, event.sequence) && eventId.equalsIgnoreCase(event.id); }
    public boolean matches(WebSocketEvent event) { return kind == Kind.WEB_SOCKET && sameSession(event.sessionId, event.sequence) && eventId.equalsIgnoreCase(event.id); }
    private boolean sameSession(String id, long value) { return sequence == value && sessionId.equalsIgnoreCase(id); }
    boolean existsIn(IncidentLogs logs, IncidentHTTP http, IncidentWebSockets sockets) {
        if (kind == Kind.LOG && logs != null) for (LogEvent event : logs.events) { if (matches(event)) return true; }
        if (kind == Kind.HTTP && http != null) for (HTTPEvent event : http.events) { if (matches(event)) return true; }
        if (kind == Kind.WEB_SOCKET && sockets != null) for (WebSocketEvent event : sockets.events) { if (matches(event)) return true; }
        return false;
    }
}
