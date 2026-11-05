package com.sandrox.osservio.core;

public interface HTTPHistory {
    final class BodyLookup {
        public enum State { AVAILABLE, UNAVAILABLE, BUSY }
        public final State state;
        public final HTTPBody body;
        public BodyLookup(State state, HTTPBody body) { this.state = state; this.body = body; }
    }
    /** Null means contention, not an empty history. */
    HTTPSnapshot snapshot();
    BodyLookup body(String id);
    void clear();
}
