package com.sandrox.osservio.core;

public interface WebSocketSink {
    enum WriteResult { STORED, BUSY, TOO_LARGE }
    /** Atomic, bounded, non-blocking append of already-sanitized values. */
    WriteResult append(WebSocketEvent event, WebSocketPayload payload);
}
