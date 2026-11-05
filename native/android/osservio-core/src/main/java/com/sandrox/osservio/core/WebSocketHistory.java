package com.sandrox.osservio.core;

public interface WebSocketHistory {
    final class PayloadLookup {
        public enum State { AVAILABLE, UNAVAILABLE, BUSY }
        public final State state;
        public final WebSocketPayload payload;
        public PayloadLookup(State state, WebSocketPayload payload) { this.state = state; this.payload = payload; }
    }
    /** Null means contention, not empty history. */
    WebSocketSnapshot snapshot();
    PayloadLookup payload(String id);
    void clear();
}
