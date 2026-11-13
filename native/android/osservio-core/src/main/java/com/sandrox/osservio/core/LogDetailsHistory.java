package com.sandrox.osservio.core;

public interface LogDetailsHistory extends LogHistory {
    final class Lookup {
        public enum State { AVAILABLE, UNAVAILABLE, BUSY }
        public final State state;
        public final LogDetailsContent content;
        private Lookup(State state, LogDetailsContent content) { this.state = state; this.content = content; }
        public static Lookup available(LogDetailsContent content) {
            if (content == null) throw new IllegalArgumentException("Missing log details");
            return new Lookup(State.AVAILABLE, content);
        }
        public static Lookup unavailable() { return new Lookup(State.UNAVAILABLE, null); }
        public static Lookup busy() { return new Lookup(State.BUSY, null); }
    }
    Lookup details(String id);
}
