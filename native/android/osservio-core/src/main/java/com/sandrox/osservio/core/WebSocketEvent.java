package com.sandrox.osservio.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Arrays;
import java.util.Locale;

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
        public static PayloadReference restore(PayloadState state, String blobId, Long byteCount) {
            boolean content = state == PayloadState.AVAILABLE || state == PayloadState.TRUNCATED;
            if (state == null || content != (blobId != null) || (blobId != null && !EvidenceValidation.id(blobId))
                || (byteCount != null && byteCount < 0)) throw EvidenceValidation.invalid();
            switch (state) {
                case AVAILABLE: if (byteCount == null || byteCount > 65536) throw EvidenceValidation.invalid(); break;
                case BINARY: if (byteCount == null) throw EvidenceValidation.invalid(); break;
                case OMITTED: case INVALID_TEXT: case TRUNCATED: if (byteCount != null) throw EvidenceValidation.invalid(); break;
                default: throw EvidenceValidation.invalid();
            }
            return new PayloadReference(state, blobId, byteCount);
        }
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

    /** Restores an observation, not a reconstructed connection. Related submissions may be absent. */
    public static WebSocketEvent restore(String id, String connectionId, String sessionId, long sequence, long timestampMillis,
                                         String url, Source source, Kind kind, List<HTTPHeader> headers, boolean headersLimited,
                                         Integer statusCode, Integer closeCode, Failure failure, Direction direction, Outcome outcome,
                                         String relatedMessageId, PayloadReference payload) {
        if (!EvidenceValidation.id(id) || !EvidenceValidation.id(connectionId) || !EvidenceValidation.id(sessionId)
            || sequence <= 0 || source == null || kind == null || !EvidenceValidation.url(url) || !EvidenceValidation.headers(headers)
            || (statusCode != null && (!Arrays.asList(Kind.OPENED, Kind.FAILED).contains(kind) || statusCode < 100 || statusCode > 599))
            || (closeCode != null && (!Arrays.asList(Kind.CLOSE_REQUESTED, Kind.CLOSING, Kind.CLOSED, Kind.CANCEL_REQUESTED).contains(kind) || closeCode < 1000 || closeCode > 4999))
            || ((!headers.isEmpty() || headersLimited) && !Arrays.asList(Kind.CONNECTING, Kind.ATTACHED, Kind.OPENED, Kind.FAILED).contains(kind))) throw EvidenceValidation.invalid();
        switch (kind) {
            case MESSAGE:
                if (direction == null || outcome == null || payload == null || outcome == Outcome.UNOBSERVED
                    || (direction == Direction.INCOMING) != (outcome == Outcome.RECEIVED) || failure != null || relatedMessageId != null
                    || (payload.blobId != null && !payload.blobId.toLowerCase(Locale.ROOT).equals(id.toLowerCase(Locale.ROOT)))) throw EvidenceValidation.invalid();
                break;
            case MESSAGE_RESULT:
                if (direction != Direction.OUTGOING || !Arrays.asList(Outcome.SENT, Outcome.FAILED, Outcome.UNOBSERVED).contains(outcome)
                    || (outcome == Outcome.FAILED) != (failure != null) || !EvidenceValidation.id(relatedMessageId) || payload != null) throw EvidenceValidation.invalid();
                break;
            default:
                if (direction != null || outcome != null || payload != null || relatedMessageId != null || (kind == Kind.FAILED) != (failure != null)) throw EvidenceValidation.invalid();
        }
        return new WebSocketEvent(id, connectionId, sessionId, sequence, timestampMillis, url, source, kind, headers,
            headersLimited, statusCode, closeCode, failure, direction, outcome, relatedMessageId, payload);
    }
    private WebSocketEvent(String id, String connectionId, String sessionId, long sequence, long timestampMillis,
                           String url, Source source, Kind kind, List<HTTPHeader> headers, boolean headersLimited, Integer statusCode,
                           Integer closeCode, Failure failure, Direction direction, Outcome outcome, String relatedMessageId, PayloadReference payload) {
        this.id = id; this.connectionId = connectionId; this.sessionId = sessionId; this.sequence = sequence; this.timestampMillis = timestampMillis;
        this.url = url; this.source = source; this.kind = kind; this.headers = Collections.unmodifiableList(new ArrayList<>(headers));
        this.headersLimited = headersLimited; this.statusCode = statusCode; this.closeCode = closeCode; this.failure = failure;
        this.direction = direction; this.outcome = outcome; this.relatedMessageId = relatedMessageId; this.payload = payload;
        int bytes = url.getBytes(StandardCharsets.UTF_8).length;
        for (HTTPHeader header : headers) bytes += header.name.getBytes(StandardCharsets.UTF_8).length + header.value.getBytes(StandardCharsets.UTF_8).length;
        textByteCount = bytes;
    }
}
