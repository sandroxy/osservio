package com.sandrox.osservio.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Explicit bounded observations, with no socket ownership, transport queue, or retries. */
public final class WebSocketRecorder {
    public enum Result { RECORDED, INACTIVE, BUSY, STALE, INVALID, CONNECTION_LIMIT, RATE_LIMITED, TOO_LARGE, SINK_FAILURE, SEQUENCE_EXHAUSTED }
    public static final class Token {
        public final String connectionId;
        public final String sessionId;
        private Token(String connectionId, String sessionId) { this.connectionId = connectionId; this.sessionId = sessionId; }
    }
    public static final class BeginResult {
        public final Result result;
        public final Token token;
        private BeginResult(Result result, Token token) { this.result = result; this.token = token; }
    }
    public static final class Status {
        public final int activeConnections;
        public final long rateLimitedCount;
        private Status(int activeConnections, long rateLimitedCount) { this.activeConnections = activeConnections; this.rateLimitedCount = rateLimitedCount; }
    }
    private static final class Connection {
        final String url;
        final WebSocketEvent.Source source;
        boolean opened;
        Connection(String url, WebSocketEvent.Source source) { this.url = url; this.source = source; }
    }
    private interface PayloadFactory { Payload create(String id); }
    private static final class Payload {
        final WebSocketEvent.PayloadReference reference;
        final WebSocketPayload body;
        Payload(WebSocketEvent.PayloadState state, Long bytes, WebSocketPayload body) {
            reference = new WebSocketEvent.PayloadReference(state, body == null ? null : body.id, bytes); this.body = body;
        }
    }
    public final CaptureController captureController;
    public final WebSocketPrivacyPolicy privacy;
    private final WebSocketSink sink;
    private final int connectionLimit;
    private final int eventsPerSecond;
    private final Map<String, Connection> connections = new LinkedHashMap<>();
    private final CaptureController.SessionObserver observer = connections::clear;
    private String rateSession = "";
    private long windowStart;
    private int windowCount;
    private long rateLimited;

    public WebSocketRecorder(WebSocketSink sink, CaptureController captureController, WebSocketPrivacyPolicy privacy) {
        this(sink, captureController, privacy, 16, 100);
    }
    public WebSocketRecorder(WebSocketSink sink, CaptureController captureController, WebSocketPrivacyPolicy privacy,
                             int connectionLimit, int eventsPerSecond) {
        if (connectionLimit < 1 || connectionLimit > 64 || eventsPerSecond < 1 || eventsPerSecond > 1000) throw new IllegalArgumentException("Invalid WebSocket capture limit");
        this.sink = Objects.requireNonNull(sink, "sink"); this.captureController = Objects.requireNonNull(captureController, "captureController");
        this.privacy = Objects.requireNonNull(privacy, "privacy"); this.connectionLimit = connectionLimit; this.eventsPerSecond = eventsPerSecond;
        captureController.register(observer);
    }
    public BeginResult begin(String url, List<HTTPHeader> headers, WebSocketEvent.Source source) {
        return begin(url, headers, source, false, UUID.randomUUID());
    }
    /** Attached means observation began after socket creation, not proof that a handshake succeeded. */
    public BeginResult begin(String url, List<HTTPHeader> headers, WebSocketEvent.Source source, boolean attached, UUID connectionId) {
        Token[] token = new Token[1];
        Result result = access(context -> {
            if (source == null || connectionId == null) return Result.INVALID;
            if (connections.size() >= connectionLimit) return Result.CONNECTION_LIMIT;
            String id = connectionId.toString();
            if (connections.containsKey(id)) return Result.INVALID;
            if (!admit(context)) return Result.RATE_LIMITED;
            String safeURL = privacy.metadata.url(url);
            HTTPPrivacyPolicy.Headers safeHeaders = privacy.metadata.headers(headers);
            WebSocketEvent event = new WebSocketEvent(UUID.randomUUID().toString(), id, context,
                captureController.clock.wallTimeMillis(), safeURL, source,
                attached ? WebSocketEvent.Kind.ATTACHED : WebSocketEvent.Kind.CONNECTING, safeHeaders.values,
                safeHeaders.limited, null, null, null, null, null, null);
            Result written = write(event, null);
            if (written == Result.RECORDED) {
                connections.put(id, new Connection(safeURL, source)); token[0] = new Token(id, context.sessionId);
            }
            return written;
        });
        return new BeginResult(result, token[0]);
    }
    /** Close reasons and raw error descriptions are deliberately not accepted. */
    public Result lifecycle(Token token, WebSocketEvent.Kind kind, List<HTTPHeader> headers, Integer statusCode,
                            Integer closeCode, WebSocketEvent.Failure failure) {
        return access(context -> {
            Connection connection = connection(token, context);
            if (connection == null) return Result.STALE;
            if (kind == null || kind == WebSocketEvent.Kind.CONNECTING || kind == WebSocketEvent.Kind.ATTACHED || kind == WebSocketEvent.Kind.MESSAGE || kind == WebSocketEvent.Kind.MESSAGE_RESULT
                || headers == null || (!headers.isEmpty() && kind != WebSocketEvent.Kind.OPENED && kind != WebSocketEvent.Kind.FAILED)
                || (statusCode != null && ((kind != WebSocketEvent.Kind.OPENED && kind != WebSocketEvent.Kind.FAILED) || statusCode < 100 || statusCode > 599))
                || (closeCode != null && ((kind != WebSocketEvent.Kind.CLOSE_REQUESTED && kind != WebSocketEvent.Kind.CLOSING && kind != WebSocketEvent.Kind.CLOSED && kind != WebSocketEvent.Kind.CANCEL_REQUESTED)
                    || closeCode < 1000 || closeCode > 4999))
                || ((kind == WebSocketEvent.Kind.FAILED) != (failure != null))
                || (kind == WebSocketEvent.Kind.OPENED && connection.opened)) return Result.INVALID;
            boolean terminal = kind == WebSocketEvent.Kind.CLOSED || kind == WebSocketEvent.Kind.FAILED
                || kind == WebSocketEvent.Kind.CANCELLED || kind == WebSocketEvent.Kind.CANCEL_REQUESTED || kind == WebSocketEvent.Kind.OBSERVATION_ENDED;
            if (terminal) connections.remove(token.connectionId);
            if (kind == WebSocketEvent.Kind.OPENED) connection.opened = true;
            if (!admit(context)) return Result.RATE_LIMITED;
            HTTPPrivacyPolicy.Headers safeHeaders = privacy.metadata.headers(headers);
            return write(new WebSocketEvent(UUID.randomUUID().toString(), token.connectionId, context,
                captureController.clock.wallTimeMillis(), connection.url, connection.source, kind, safeHeaders.values,
                safeHeaders.limited, statusCode, closeCode, failure, null, null, null), null);
        });
    }
    public Result text(Token token, WebSocketEvent.Direction direction, WebSocketEvent.Outcome outcome, String value) {
        return text(token, direction, outcome, value, UUID.randomUUID());
    }
    public Result text(Token token, WebSocketEvent.Direction direction, WebSocketEvent.Outcome outcome, String value, UUID messageId) {
        if (value == null) return Result.INVALID;
        return message(token, direction, outcome, messageId, id -> {
            if (!privacy.publicMessageText) return new Payload(WebSocketEvent.PayloadState.OMITTED, null, null);
            BoundedText bounded = BoundedText.prefix(value, privacy.messageByteLimit);
            if (bounded == null) return new Payload(WebSocketEvent.PayloadState.INVALID_TEXT, null, null);
            WebSocketPayload body = new WebSocketPayload(id, bounded.text);
            return new Payload(bounded.truncated ? WebSocketEvent.PayloadState.TRUNCATED : WebSocketEvent.PayloadState.AVAILABLE,
                bounded.truncated ? null : Long.valueOf(body.byteCount), body);
        });
    }
    /** Binary bytes stay with the host. Only their already-known count is accepted. */
    public Result binary(Token token, WebSocketEvent.Direction direction, WebSocketEvent.Outcome outcome, long byteCount) {
        return binary(token, direction, outcome, byteCount, UUID.randomUUID());
    }
    public Result binary(Token token, WebSocketEvent.Direction direction, WebSocketEvent.Outcome outcome, long byteCount, UUID messageId) {
        if (byteCount < 0) return Result.INVALID;
        return message(token, direction, outcome, messageId, id -> new Payload(WebSocketEvent.PayloadState.BINARY, byteCount, null));
    }
    /** Correlates a completion without retaining its original content. The submission may have been evicted. */
    public Result sendResult(Token token, UUID messageId, WebSocketEvent.Outcome outcome, WebSocketEvent.Failure failure) {
        return access(context -> {
            Connection connection = connection(token, context);
            if (connection == null) return Result.STALE;
            if (messageId == null || (outcome != WebSocketEvent.Outcome.SENT && outcome != WebSocketEvent.Outcome.FAILED && outcome != WebSocketEvent.Outcome.UNOBSERVED)
                || ((outcome == WebSocketEvent.Outcome.FAILED) != (failure != null))) return Result.INVALID;
            if (!admit(context)) return Result.RATE_LIMITED;
            return write(new WebSocketEvent(UUID.randomUUID().toString(), token.connectionId, context,
                captureController.clock.wallTimeMillis(), connection.url, connection.source, WebSocketEvent.Kind.MESSAGE_RESULT,
                Collections.emptyList(), false, null, null, failure, WebSocketEvent.Direction.OUTGOING, outcome, null, messageId.toString()), null);
        });
    }
    public Status status() { return captureController.inspect(ignored -> new Status(connections.size(), rateLimited)); }
    private Result message(Token token, WebSocketEvent.Direction direction, WebSocketEvent.Outcome outcome, UUID messageId, PayloadFactory payload) {
        return access(context -> {
            Connection connection = connection(token, context);
            if (connection == null) return Result.STALE;
            if (messageId == null || direction == null || outcome == null || outcome == WebSocketEvent.Outcome.UNOBSERVED
                || ((direction == WebSocketEvent.Direction.INCOMING) != (outcome == WebSocketEvent.Outcome.RECEIVED))) return Result.INVALID;
            if (!admit(context)) return Result.RATE_LIMITED;
            String id = messageId.toString();
            Payload body = payload.create(id);
            return write(new WebSocketEvent(id, token.connectionId, context, captureController.clock.wallTimeMillis(),
                connection.url, connection.source, WebSocketEvent.Kind.MESSAGE, Collections.emptyList(), false,
                null, null, null, direction, outcome, body.reference), body.body);
        });
    }
    private Connection connection(Token token, CaptureController.Context context) {
        return token == null || !token.sessionId.equals(context.sessionId) ? null : connections.get(token.connectionId);
    }
    private boolean admit(CaptureController.Context context) {
        if (!rateSession.equals(context.sessionId)) { rateSession = context.sessionId; windowStart = context.sessionStartMillis; windowCount = 0; }
        if (context.monotonicMillis >= windowStart && context.monotonicMillis - windowStart >= 1000) { windowStart = context.monotonicMillis; windowCount = 0; }
        if (windowCount >= eventsPerSecond) { if (rateLimited < Long.MAX_VALUE) rateLimited++; return false; }
        windowCount++; return true;
    }
    private Result access(CaptureController.Operation<CaptureController.Context, Result> body) {
        CaptureController.Access<Result> result = captureController.record(true, body);
        switch (result.admission) {
            case VALUE: return result.value;
            case INACTIVE: return Result.INACTIVE;
            case BUSY: return Result.BUSY;
            case SEQUENCE_EXHAUSTED: return Result.SEQUENCE_EXHAUSTED;
            default: throw new IllegalStateException("Unknown capture admission");
        }
    }
    private Result write(WebSocketEvent event, WebSocketPayload payload) {
        try {
            WebSocketSink.WriteResult result = sink.append(event, payload);
            if (result == WebSocketSink.WriteResult.STORED) return Result.RECORDED;
            if (result == WebSocketSink.WriteResult.BUSY) return Result.BUSY;
            if (result == WebSocketSink.WriteResult.TOO_LARGE) return Result.TOO_LARGE;
            return Result.SINK_FAILURE;
        } catch (RuntimeException failure) { return Result.SINK_FAILURE; }
    }
}
