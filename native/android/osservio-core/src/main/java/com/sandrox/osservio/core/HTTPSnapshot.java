package com.sandrox.osservio.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class HTTPSnapshot {
    public final List<HTTPEvent> events;
    public final int retainedTextBytes;
    public final int retainedBodyBytes;
    public final long evictedEvents;
    public final long evictedBodies;
    public final long revision;
    public HTTPSnapshot(List<HTTPEvent> events, int textBytes, int bodyBytes, long evictedEvents, long evictedBodies, long revision) {
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
        retainedTextBytes = textBytes; retainedBodyBytes = bodyBytes;
        this.evictedEvents = evictedEvents; this.evictedBodies = evictedBodies; this.revision = revision;
    }
}
