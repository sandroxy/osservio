package com.sandrox.osservio.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable sanitized evidence, not reconstructed connection state or a wire frame. */
public final class WebSocketEvent {
    public enum Kind { CONNECTING, ATTACHED, OPENED, MESSAGE, MESSAGE_RESULT, CLOSE_REQUESTED, CLOSING, CLOSED, FAILED, CANCELLED, CANCEL_REQUESTED, OBSERVATION_ENDED, OBSERVATION_LIMITED }
    public enum Source { HOST, OKHTTP, URL_SESSION }
    public enum Direction { INCOMING, OUTGOING }
    /** QUEUED and SENT describe client API results, never proof of peer receipt. */
    public enum Outcome { RECEIVED, SUBMITTED, QUEUED, SENT, REJECTED, FAILED, UNOBSERVED }
    public enum PayloadState { OMITTED, BINARY, AVAILABLE, TRUNCATED, INVALID_TEXT }
    public enum Failure { TRANSPORT, TIMEOUT, TLS, CONNECTION, UNKNOWN }
    public static final class PayloadReference {
        public final PayloadState state;
        public final String blobId;
        /** Null means the byte count was unavailable without inspecting more content. */
        public final Long byteCount;
        PayloadReference(PayloadState state, String blobId, Long byteCount) { this.state = state; this.blobId = blobId; this.byteCount = byteCount; }
    }
    public final String id;
    public final String connectionId;
    public final String sessionId;
    public final long sequence;
    public final long timestampMillis;
    public final String url;
    public final Source source;
    public final Kind kind;
    public final List<HTTPHeader> headers;
    public final boolean headersLimited;
    public final Integer statusCode;
    public final Integer closeCode;
    public final Failure failure;
    public final Direction direction;
    public final Outcome outcome;
    public final String relatedMessageId;
    public final PayloadReference payload;
    public final int textByteCount;
    WebSocketEvent(String id, String connectionId, CaptureController.Context context, long timestampMillis, String url,
                   Source source, Kind kind, List<HTTPHeader> headers, boolean headersLimited, Integer statusCode,
                   Integer closeCode, Failure failure, Direction direction, Outcome outcome, PayloadReference payload) {
        this(id, connectionId, context, timestampMillis, url, source, kind, headers, headersLimited, statusCode,
            closeCode, failure, direction, outcome, payload, null);
    }
    WebSocketEvent(String id, String connectionId, CaptureController.Context context, long timestampMillis, String url,
                   Source source, Kind kind, List<HTTPHeader> headers, boolean headersLimited, Integer statusCode,
                   Integer closeCode, Failure failure, Direction direction, Outcome outcome, PayloadReference payload, String relatedMessageId) {
        this.id = id; this.connectionId = connectionId; sessionId = context.sessionId; sequence = context.sequence;
        this.timestampMillis = timestampMillis; this.url = url; this.source = source; this.kind = kind;
        this.headers = Collections.unmodifiableList(new ArrayList<>(headers)); this.headersLimited = headersLimited;
        this.statusCode = statusCode; this.closeCode = closeCode; this.failure = failure;
        this.direction = direction; this.outcome = outcome; this.payload = payload; this.relatedMessageId = relatedMessageId;
        int bytes = url.getBytes(StandardCharsets.UTF_8).length;
        for (HTTPHeader header : headers) bytes += header.name.getBytes(StandardCharsets.UTF_8).length + header.value.getBytes(StandardCharsets.UTF_8).length;
        textByteCount = bytes;
    }
}
