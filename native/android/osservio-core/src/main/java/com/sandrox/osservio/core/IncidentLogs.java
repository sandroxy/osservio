package com.sandrox.osservio.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.HashSet;

/** An explicitly chosen immutable window, with its own observation time and source loss counters. */
public final class IncidentLogs {
    public final long observedAtMillis;
    public final List<LogEvent> events;
    public final int sourceEventCount;
    public final long sourceEvictedCount;
    public final int retainedTextBytes;
    public final int omittedEventCount;
    public final Map<String, IncidentPayload> payloads;
    public final int retainedPayloadBytes;
    public final long sourceEvictedDetails;
    private IncidentLogs(List<LogEvent> events, long observedAtMillis, int sourceEventCount, long sourceEvictedCount, int bytes,
                         Map<String, IncidentPayload> payloads, int payloadBytes, long sourceEvictedDetails) {
        this.events = Collections.unmodifiableList(new ArrayList<>(events)); this.observedAtMillis = observedAtMillis;
        this.sourceEventCount = sourceEventCount; this.sourceEvictedCount = sourceEvictedCount;
        retainedTextBytes = bytes; omittedEventCount = sourceEventCount - events.size();
        this.payloads = Collections.unmodifiableMap(new LinkedHashMap<>(payloads)); retainedPayloadBytes = payloadBytes; this.sourceEvictedDetails = sourceEvictedDetails;
    }
    public static IncidentLogs capture(LogSnapshot source, long observedAtMillis) { return capture(source, observedAtMillis, 200, 65536); }
    public static IncidentLogs capture(LogSnapshot source, long observedAtMillis, int eventLimit, int textByteLimit) {
        return capture(source, observedAtMillis, eventLimit, textByteLimit, null, false, 65536);
    }
    public static IncidentLogs capture(LogSnapshot source, long observedAtMillis, int eventLimit, int textByteLimit,
                                       LogDetailsHistory history, boolean includeDetails, int payloadByteLimit) {
        List<LogEvent> events = IncidentWindow.select(source.events, eventLimit, textByteLimit, event -> event.textByteCount);
        return restore(events, observedAtMillis, source.events.size(), source.evictedCount,
            selectPayloads(events, history, includeDetails, payloadByteLimit), source.evictedDetails);
    }
    public IncidentLogs includingDetails(LogDetailsHistory history, int payloadByteLimit) {
        return restore(events, observedAtMillis, sourceEventCount, sourceEvictedCount,
            selectPayloads(events, history, true, payloadByteLimit), sourceEvictedDetails);
    }
    private static Map<String, IncidentPayload> selectPayloads(List<LogEvent> events, LogDetailsHistory history, boolean include, int byteLimit) {
        if (byteLimit < 0 || byteLimit > 262144) throw new IllegalArgumentException("Invalid log details budget");
        Map<String, IncidentPayload> payloads = new LinkedHashMap<>(); int remaining = byteLimit;
        for (int index = events.size() - 1; index >= 0; index--) {
            LogDetailsReference reference = events.get(index).details; if (reference == null || reference.blobId == null) continue;
            String id = reference.blobId, text = null;
            IncidentPayload.State state = include ? IncidentPayload.State.UNAVAILABLE : IncidentPayload.State.NOT_SELECTED;
            if (include && history != null) {
                LogDetailsHistory.Lookup result = history.details(id);
                if (result.state == LogDetailsHistory.Lookup.State.AVAILABLE) {
                    LogDetailsContent content = result.content;
                    if (!id.equals(content.id) || content.byteCount != reference.textByteCount) throw EvidenceValidation.invalid();
                    state = content.byteCount <= remaining ? IncidentPayload.State.AVAILABLE : IncidentPayload.State.BUDGET_EXCEEDED;
                    if (state == IncidentPayload.State.AVAILABLE) { text = content.text; remaining -= content.byteCount; }
                } else if (result.state == LogDetailsHistory.Lookup.State.BUSY) state = IncidentPayload.State.BUSY;
            }
            if (payloads.put(id, IncidentPayload.restore(id, state, text)) != null) throw EvidenceValidation.invalid();
        }
        return payloads;
    }
    public static IncidentLogs restore(List<LogEvent> events, long observedAtMillis, int sourceEventCount, long sourceEvictedCount) {
        return restore(events, observedAtMillis, sourceEventCount, sourceEvictedCount, Collections.emptyMap(), 0);
    }
    public static IncidentLogs restore(List<LogEvent> events, long observedAtMillis, int sourceEventCount, long sourceEvictedCount,
                                       Map<String, IncidentPayload> payloads, long sourceEvictedDetails) {
        int bytes = IncidentWindow.validate(events, sourceEventCount, sourceEvictedCount, event -> event.textByteCount,
            event -> event.sessionId.toLowerCase(Locale.ROOT) + ":" + event.sequence);
        int payloadBytes = IncidentWindow.payloadBytes(payloads, events.size());
        if (sourceEvictedDetails < 0) throw EvidenceValidation.invalid();
        Set<String> references = new HashSet<>();
        for (LogEvent event : events) {
            if (event.details == null || event.details.blobId == null) continue;
            String id = event.details.blobId; IncidentPayload payload = payloads.get(id);
            if (!references.add(id) || payload == null || payload.state == IncidentPayload.State.AVAILABLE && payload.textByteCount != event.details.textByteCount) throw EvidenceValidation.invalid();
        }
        if (references.size() != payloads.size()) throw EvidenceValidation.invalid();
        return new IncidentLogs(events, observedAtMillis, sourceEventCount, sourceEvictedCount, bytes, payloads, payloadBytes, sourceEvictedDetails);
    }
}
