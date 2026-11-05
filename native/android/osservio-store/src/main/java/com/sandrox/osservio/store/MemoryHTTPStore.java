package com.sandrox.osservio.store;

import com.sandrox.osservio.core.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/** Independent metadata/body budgets; all content has already passed Core's privacy policy. */
public final class MemoryHTTPStore implements HTTPSink, HTTPHistory {
    private final ReentrantLock lock = new ReentrantLock();
    private final int recordLimit;
    private final int textByteLimit;
    private final int bodyByteLimit;
    private final Map<String, HTTPEvent> events = new LinkedHashMap<>();
    private final Map<String, HTTPBody> bodies = new LinkedHashMap<>();
    private int textBytes;
    private int bodyBytes;
    private long evictedEvents;
    private long evictedBodies;
    private long revision;

    public MemoryHTTPStore() { this(200, 262144, 524288); }
    public MemoryHTTPStore(int recordLimit, int textByteLimit, int bodyByteLimit) {
        if (recordLimit < 1 || recordLimit > 2000 || textByteLimit < 1 || textByteLimit > 16777216 || bodyByteLimit < 0 || bodyByteLimit > 16777216) {
            throw new IllegalArgumentException("Invalid HTTP store limit");
        }
        this.recordLimit = recordLimit; this.textByteLimit = textByteLimit; this.bodyByteLimit = bodyByteLimit;
    }

    @Override public WriteResult write(HTTPUpdate update) {
        if (!lock.tryLock()) return WriteResult.BUSY;
        try {
            HTTPEvent event = update.event;
            if (event.textByteCount > textByteLimit) return WriteResult.TOO_LARGE;
            HTTPEvent previous = events.get(event.id);
            if (update.isNew ? previous != null : previous == null || event.revision <= previous.revision) return WriteResult.MISSING;
            int previousSize = previous == null ? 0 : previous.textByteCount;
            while (!events.isEmpty() && ((update.isNew && events.size() >= recordLimit) || textBytes - previousSize > textByteLimit - event.textByteCount)) {
                String oldest = null;
                for (String id : events.keySet()) if (!id.equals(event.id)) { oldest = id; break; }
                if (oldest == null) break;
                removeEvent(oldest);
                if (evictedEvents < Long.MAX_VALUE) evictedEvents++;
            }
            events.put(event.id, event); textBytes += event.textByteCount - previousSize;
            for (HTTPBody body : update.bodies) {
                if (body.byteCount > bodyByteLimit || bodyByteLimit == 0) { if (evictedBodies < Long.MAX_VALUE) evictedBodies++; continue; }
                if (bodies.containsKey(body.id)) removeBody(body.id, false);
                while (bodyBytes > bodyByteLimit - body.byteCount && !bodies.isEmpty()) removeBody(bodies.keySet().iterator().next(), true);
                bodies.put(body.id, body); bodyBytes += body.byteCount;
            }
            revision++;
            return WriteResult.STORED;
        } finally { lock.unlock(); }
    }

    @Override public HTTPSnapshot snapshot() {
        if (!lock.tryLock()) return null;
        try { return new HTTPSnapshot(new ArrayList<>(events.values()), textBytes, bodyBytes, evictedEvents, evictedBodies, revision); }
        finally { lock.unlock(); }
    }
    @Override public BodyLookup body(String id) {
        if (!lock.tryLock()) return new BodyLookup(BodyLookup.State.BUSY, null);
        try {
            HTTPBody body = bodies.get(id);
            return new BodyLookup(body == null ? BodyLookup.State.UNAVAILABLE : BodyLookup.State.AVAILABLE, body);
        } finally { lock.unlock(); }
    }
    @Override public void clear() {
        lock.lock();
        try { events.clear(); bodies.clear(); textBytes = 0; bodyBytes = 0; revision++; }
        finally { lock.unlock(); }
    }
    private void removeEvent(String id) {
        HTTPEvent event = events.remove(id);
        if (event != null) {
            textBytes -= event.textByteCount;
            removeBody(id + ".request", true); removeBody(id + ".response", true);
        }
    }
    private void removeBody(String id, boolean evicted) {
        HTTPBody body = bodies.remove(id);
        if (body != null) { bodyBytes -= body.byteCount; if (evicted && evictedBodies < Long.MAX_VALUE) evictedBodies++; }
    }
}
