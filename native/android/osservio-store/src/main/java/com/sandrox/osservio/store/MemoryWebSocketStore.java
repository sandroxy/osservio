package com.sandrox.osservio.store;

import com.sandrox.osservio.core.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/** Append-only evidence and independently evictable payloads, with no reconstructed socket state. */
public final class MemoryWebSocketStore implements WebSocketSink, WebSocketHistory {
    private final ReentrantLock lock = new ReentrantLock();
    private final int recordLimit;
    private final int textByteLimit;
    private final int payloadByteLimit;
    private final Map<String, WebSocketEvent> events = new LinkedHashMap<>();
    private final Map<String, WebSocketPayload> payloads = new LinkedHashMap<>();
    private int textBytes;
    private int payloadBytes;
    private long evictedEvents;
    private long evictedPayloads;
    private long revision;
    public MemoryWebSocketStore() { this(500, 262144, 262144); }
    public MemoryWebSocketStore(int recordLimit, int textByteLimit, int payloadByteLimit) {
        if (recordLimit < 1 || recordLimit > 2000 || textByteLimit < 1 || textByteLimit > 16777216 || payloadByteLimit < 0 || payloadByteLimit > 16777216) {
            throw new IllegalArgumentException("Invalid WebSocket store limit");
        }
        this.recordLimit = recordLimit; this.textByteLimit = textByteLimit; this.payloadByteLimit = payloadByteLimit;
    }
    @Override public WriteResult append(WebSocketEvent event, WebSocketPayload payload) {
        if (!lock.tryLock()) return WriteResult.BUSY;
        try {
            if (event.textByteCount > textByteLimit) return WriteResult.TOO_LARGE;
            if (events.containsKey(event.id)) return WriteResult.STORED;
            while (!events.isEmpty() && (events.size() >= recordLimit || textBytes > textByteLimit - event.textByteCount)) {
                WebSocketEvent oldest = events.remove(events.keySet().iterator().next()); textBytes -= oldest.textByteCount;
                if (evictedEvents < Long.MAX_VALUE) evictedEvents++;
                removePayload(oldest.id);
            }
            events.put(event.id, event); textBytes += event.textByteCount;
            if (payload != null && event.payload != null && payload.id.equals(event.payload.blobId)) {
                if (payloadByteLimit > 0 && payload.byteCount <= payloadByteLimit) {
                    while (payloadBytes > payloadByteLimit - payload.byteCount && !payloads.isEmpty()) removePayload(payloads.keySet().iterator().next());
                    payloads.put(payload.id, payload); payloadBytes += payload.byteCount;
                } else if (evictedPayloads < Long.MAX_VALUE) evictedPayloads++;
            }
            revision++; return WriteResult.STORED;
        } finally { lock.unlock(); }
    }
    @Override public WebSocketSnapshot snapshot() {
        if (!lock.tryLock()) return null;
        try { return new WebSocketSnapshot(new ArrayList<>(events.values()), textBytes, payloadBytes, evictedEvents, evictedPayloads, revision); }
        finally { lock.unlock(); }
    }
    @Override public PayloadLookup payload(String id) {
        if (!lock.tryLock()) return new PayloadLookup(PayloadLookup.State.BUSY, null);
        try {
            WebSocketPayload body = payloads.get(id);
            return new PayloadLookup(body == null ? PayloadLookup.State.UNAVAILABLE : PayloadLookup.State.AVAILABLE, body);
        } finally { lock.unlock(); }
    }
    @Override public void clear() {
        lock.lock();
        try { events.clear(); payloads.clear(); textBytes = 0; payloadBytes = 0; revision++; }
        finally { lock.unlock(); }
    }
    private void removePayload(String id) {
        WebSocketPayload body = payloads.remove(id);
        if (body != null) { payloadBytes -= body.byteCount; if (evictedPayloads < Long.MAX_VALUE) evictedPayloads++; }
    }
}
