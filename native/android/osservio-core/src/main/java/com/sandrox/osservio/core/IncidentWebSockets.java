package com.sandrox.osservio.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Frozen WebSocket observations. Missing handshakes or send submissions are not reconstructed. */
public final class IncidentWebSockets {
    public final long observedAtMillis;
    public final List<WebSocketEvent> events;
    public final Map<String, IncidentPayload> payloads;
    public final int sourceEventCount;
    public final long sourceEvictedEvents;
    public final long sourceEvictedPayloads;
    public final int retainedTextBytes;
    public final int retainedPayloadBytes;
    public final int omittedEventCount;
    private IncidentWebSockets(List<WebSocketEvent> events, Map<String, IncidentPayload> payloads, long observedAtMillis,
                               int sourceEventCount, long sourceEvictedEvents, long sourceEvictedPayloads, int bytes, int payloadBytes) {
        this.events = Collections.unmodifiableList(new ArrayList<>(events)); this.payloads = Collections.unmodifiableMap(new LinkedHashMap<>(payloads));
        this.observedAtMillis = observedAtMillis; this.sourceEventCount = sourceEventCount;
        this.sourceEvictedEvents = sourceEvictedEvents; this.sourceEvictedPayloads = sourceEvictedPayloads;
        retainedTextBytes = bytes; retainedPayloadBytes = payloadBytes; omittedEventCount = sourceEventCount - events.size();
    }
    public static IncidentWebSockets capture(WebSocketSnapshot source, long observedAtMillis, WebSocketHistory history, boolean includePayloads) {
        return capture(source, observedAtMillis, history, includePayloads, 200, 65536, 65536);
    }
    public static IncidentWebSockets capture(WebSocketSnapshot source, long observedAtMillis, WebSocketHistory history, boolean includePayloads,
                                             int eventLimit, int textByteLimit, int payloadByteLimit) {
        if (payloadByteLimit < 0 || payloadByteLimit > 262144) throw new IllegalArgumentException("Invalid incident payload limit");
        List<WebSocketEvent> events = IncidentWindow.select(source.events, eventLimit, textByteLimit, event -> event.textByteCount);
        Map<String, IncidentPayload> payloads = new LinkedHashMap<>(); int remaining = payloadByteLimit;
        for (int index = events.size() - 1; index >= 0; index--) {
            WebSocketEvent event = events.get(index);
            if (event.payload == null || event.payload.blobId == null) continue;
            String id = event.payload.blobId; IncidentPayload payload;
            if (!includePayloads) payload = IncidentPayload.restore(id, IncidentPayload.State.NOT_SELECTED, null);
            else {
                WebSocketHistory.PayloadLookup result = history.payload(id);
                if (result == null || result.state == null) throw EvidenceValidation.invalid();
                switch (result.state) {
                    case AVAILABLE:
                        if (result.payload == null || !result.payload.id.equals(id)) throw EvidenceValidation.invalid();
                        payload = IncidentPayload.restore(id, result.payload.byteCount <= remaining ? IncidentPayload.State.AVAILABLE : IncidentPayload.State.BUDGET_EXCEEDED,
                            result.payload.byteCount <= remaining ? result.payload.text : null); break;
                    case UNAVAILABLE: payload = IncidentPayload.restore(id, IncidentPayload.State.UNAVAILABLE, null); break;
                    case BUSY: payload = IncidentPayload.restore(id, IncidentPayload.State.BUSY, null); break;
                    default: throw EvidenceValidation.invalid();
                }
            }
            if (payloads.put(id, payload) != null) throw EvidenceValidation.invalid();
            remaining -= payload.textByteCount;
        }
        return restore(events, payloads, observedAtMillis, source.events.size(), source.evictedEvents, source.evictedPayloads);
    }
    public static IncidentWebSockets restore(List<WebSocketEvent> events, Map<String, IncidentPayload> payloads, long observedAtMillis,
                                             int sourceEventCount, long sourceEvictedEvents, long sourceEvictedPayloads) {
        int bytes = IncidentWindow.validate(events, sourceEventCount, sourceEvictedEvents, event -> event.textByteCount, event -> event.id.toLowerCase(Locale.ROOT));
        if (sourceEvictedPayloads < 0) throw EvidenceValidation.invalid();
        int payloadBytes = IncidentWindow.payloadBytes(payloads, events.size()); Set<String> references = new HashSet<>();
        for (WebSocketEvent event : events) {
            if (event.payload == null || event.payload.blobId == null) continue;
            String id = event.payload.blobId; IncidentPayload payload = payloads.get(id);
            if (!references.add(id) || payload == null) throw EvidenceValidation.invalid();
            if (payload.state == IncidentPayload.State.AVAILABLE && event.payload.state == WebSocketEvent.PayloadState.AVAILABLE
                && !Long.valueOf(payload.textByteCount).equals(event.payload.byteCount)) throw EvidenceValidation.invalid();
        }
        if (references.size() != payloads.size()) throw EvidenceValidation.invalid();
        return new IncidentWebSockets(events, payloads, observedAtMillis, sourceEventCount, sourceEvictedEvents, sourceEvictedPayloads, bytes, payloadBytes);
    }
}
