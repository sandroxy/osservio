package com.sandrox.osservio.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Frozen HTTP metadata and separately selected bodies. Live lookups are not an atomic history read. */
public final class IncidentHTTP {
    public final long observedAtMillis;
    public final List<HTTPEvent> events;
    public final Map<String, IncidentPayload> payloads;
    public final int sourceEventCount;
    public final long sourceEvictedEvents;
    public final long sourceEvictedBodies;
    public final int retainedTextBytes;
    public final int retainedPayloadBytes;
    public final int omittedEventCount;
    private IncidentHTTP(List<HTTPEvent> events, Map<String, IncidentPayload> payloads, long observedAtMillis,
                         int sourceEventCount, long sourceEvictedEvents, long sourceEvictedBodies, int bytes, int payloadBytes) {
        this.events = Collections.unmodifiableList(new ArrayList<>(events)); this.payloads = Collections.unmodifiableMap(new LinkedHashMap<>(payloads));
        this.observedAtMillis = observedAtMillis; this.sourceEventCount = sourceEventCount;
        this.sourceEvictedEvents = sourceEvictedEvents; this.sourceEvictedBodies = sourceEvictedBodies;
        retainedTextBytes = bytes; retainedPayloadBytes = payloadBytes; omittedEventCount = sourceEventCount - events.size();
    }
    public static IncidentHTTP capture(HTTPSnapshot source, long observedAtMillis, HTTPHistory history, boolean includeBodies) {
        return capture(source, observedAtMillis, history, includeBodies, 50, 65536, 65536);
    }
    /** Selects a newest contiguous window, looking up each selected body at most once without retry. */
    public static IncidentHTTP capture(HTTPSnapshot source, long observedAtMillis, HTTPHistory history, boolean includeBodies,
                                       int eventLimit, int textByteLimit, int payloadByteLimit) {
        if (payloadByteLimit < 0 || payloadByteLimit > 262144) throw new IllegalArgumentException("Invalid incident payload limit");
        List<HTTPEvent> events = IncidentWindow.select(source.events, eventLimit, textByteLimit, event -> event.textByteCount);
        Map<String, IncidentPayload> payloads = selectPayloads(events, history, includeBodies, payloadByteLimit);
        return restore(events, payloads, observedAtMillis, source.events.size(), source.evictedEvents, source.evictedBodies);
    }
    /** Returns a new copy with bounded body lookups, preserving all frozen metadata and source counters. */
    public IncidentHTTP includingBodies(HTTPHistory history, int payloadByteLimit) {
        Map<String, IncidentPayload> payloads = selectPayloads(events, history, true, payloadByteLimit);
        return restore(events, payloads, observedAtMillis, sourceEventCount, sourceEvictedEvents, sourceEvictedBodies);
    }
    private static Map<String, IncidentPayload> selectPayloads(List<HTTPEvent> events, HTTPHistory history, boolean include, int byteLimit) {
        if (byteLimit < 0 || byteLimit > 262144) throw new IllegalArgumentException("Invalid incident payload limit");
        Map<String, IncidentPayload> payloads = new LinkedHashMap<>(); int remaining = byteLimit;
        // Prefer newer responses, then their requests; do not truncate captured content again.
        for (int index = events.size() - 1; index >= 0; index--) {
            HTTPEvent event = events.get(index);
            for (HTTPEvent.BodyReference reference : new HTTPEvent.BodyReference[] {event.responseBody, event.requestBody}) {
                if (reference.blobId == null) continue;
                String id = reference.blobId; IncidentPayload payload;
                if (!include) payload = IncidentPayload.restore(id, IncidentPayload.State.NOT_SELECTED, null);
                else {
                    HTTPHistory.BodyLookup result = history.body(id);
                    if (result == null || result.state == null) throw EvidenceValidation.invalid();
                    switch (result.state) {
                        case AVAILABLE:
                            if (result.body == null || !result.body.id.equals(id)) throw EvidenceValidation.invalid();
                            payload = IncidentPayload.restore(id, result.body.byteCount <= remaining ? IncidentPayload.State.AVAILABLE : IncidentPayload.State.BUDGET_EXCEEDED,
                                result.body.byteCount <= remaining ? result.body.text : null); break;
                        case UNAVAILABLE: payload = IncidentPayload.restore(id, IncidentPayload.State.UNAVAILABLE, null); break;
                        case BUSY: payload = IncidentPayload.restore(id, IncidentPayload.State.BUSY, null); break;
                        default: throw EvidenceValidation.invalid();
                    }
                }
                if (payloads.put(id, payload) != null) throw EvidenceValidation.invalid();
                remaining -= payload.textByteCount;
            }
        }
        return payloads;
    }
    public static IncidentHTTP restore(List<HTTPEvent> events, Map<String, IncidentPayload> payloads, long observedAtMillis,
                                       int sourceEventCount, long sourceEvictedEvents, long sourceEvictedBodies) {
        int bytes = IncidentWindow.validate(events, sourceEventCount, sourceEvictedEvents, event -> event.textByteCount, event -> event.id.toLowerCase(Locale.ROOT));
        if (sourceEvictedBodies < 0) throw EvidenceValidation.invalid();
        int payloadBytes = IncidentWindow.payloadBytes(payloads, events.size() * 2);
        Set<String> references = new HashSet<>();
        for (HTTPEvent event : events) {
            for (HTTPEvent.BodyReference reference : new HTTPEvent.BodyReference[] {event.requestBody, event.responseBody}) {
                if (reference.blobId == null) continue;
                IncidentPayload payload = payloads.get(reference.blobId);
                if (!references.add(reference.blobId) || payload == null) throw EvidenceValidation.invalid();
                if (payload.state == IncidentPayload.State.AVAILABLE) {
                    boolean valid = reference.state == HTTPEvent.BodyState.AVAILABLE ? payload.textByteCount == reference.observedBytes
                        : reference.state == HTTPEvent.BodyState.TRUNCATED && payload.textByteCount < reference.observedBytes;
                    if (!valid) throw EvidenceValidation.invalid();
                }
            }
        }
        if (references.size() != payloads.size()) throw EvidenceValidation.invalid();
        return new IncidentHTTP(events, payloads, observedAtMillis, sourceEventCount, sourceEvictedEvents, sourceEvictedBodies, bytes, payloadBytes);
    }
}
