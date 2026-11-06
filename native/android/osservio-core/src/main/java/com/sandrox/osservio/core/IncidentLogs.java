package com.sandrox.osservio.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** An explicitly chosen immutable window, with its own observation time and source loss counters. */
public final class IncidentLogs {
    public final long observedAtMillis;
    public final List<LogEvent> events;
    public final int sourceEventCount;
    public final long sourceEvictedCount;
    public final int retainedTextBytes;
    public final int omittedEventCount;
    private IncidentLogs(List<LogEvent> events, long observedAtMillis, int sourceEventCount, long sourceEvictedCount, int bytes) {
        this.events = Collections.unmodifiableList(new ArrayList<>(events)); this.observedAtMillis = observedAtMillis;
        this.sourceEventCount = sourceEventCount; this.sourceEvictedCount = sourceEvictedCount;
        retainedTextBytes = bytes; omittedEventCount = sourceEventCount - events.size();
    }
    public static IncidentLogs capture(LogSnapshot source, long observedAtMillis) { return capture(source, observedAtMillis, 200, 65536); }
    public static IncidentLogs capture(LogSnapshot source, long observedAtMillis, int eventLimit, int textByteLimit) {
        List<LogEvent> events = IncidentWindow.select(source.events, eventLimit, textByteLimit, event -> event.textByteCount);
        return restore(events, observedAtMillis, source.events.size(), source.evictedCount);
    }
    public static IncidentLogs restore(List<LogEvent> events, long observedAtMillis, int sourceEventCount, long sourceEvictedCount) {
        int bytes = IncidentWindow.validate(events, sourceEventCount, sourceEvictedCount, event -> event.textByteCount,
            event -> event.sessionId.toLowerCase(Locale.ROOT) + ":" + event.sequence);
        return new IncidentLogs(events, observedAtMillis, sourceEventCount, sourceEvictedCount, bytes);
    }
}
