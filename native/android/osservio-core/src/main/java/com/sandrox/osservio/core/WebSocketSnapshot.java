package com.sandrox.osservio.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class WebSocketSnapshot {
    public final List<WebSocketEvent> events;
    public final int retainedTextBytes;
    public final int retainedPayloadBytes;
    public final long evictedEvents;
    public final long evictedPayloads;
    public final long revision;
    public WebSocketSnapshot(List<WebSocketEvent> events, int retainedTextBytes, int retainedPayloadBytes,
                             long evictedEvents, long evictedPayloads, long revision) {
        this.events = Collections.unmodifiableList(new ArrayList<>(events)); this.retainedTextBytes = retainedTextBytes;
        this.retainedPayloadBytes = retainedPayloadBytes; this.evictedEvents = evictedEvents;
        this.evictedPayloads = evictedPayloads; this.revision = revision;
    }
}
