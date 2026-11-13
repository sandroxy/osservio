package com.sandrox.osservio.core;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Bounded HTTP observation. Never owns or reads a host stream, and performs no I/O. */
public final class HTTPRecorder {
    public enum Direction { REQUEST, RESPONSE }
    public enum Result { RECORDED, INACTIVE, BUSY, STALE, RATE_LIMITED, ACTIVE_LIMIT, INVALID, TOO_LARGE, MISSING, SINK_FAILURE, SEQUENCE_EXHAUSTED }
    public interface BodyBytes {
        /** Copy only this prefix from already-observed memory. Never read a host stream. */
        byte[] copyPrefix(int maximumBytes);
    }
    public static final class Token {
        public final String id;
        final String sessionId;
        private Token(String id, String sessionId) { this.id = id; this.sessionId = sessionId; }
    }
    public static final class BeginResult {
        public final Result result;
        public final Token token;
        private BeginResult(Result result, Token token) { this.result = result; this.token = token; }
    }

    public final CaptureController captureController;
    public final HTTPPrivacyPolicy privacy;
    private final HTTPSink sink;
    private final int activeLimit;
    private final int requestsPerSecond;
    private final long exchangeLifetimeMillis;
    private final Map<String, Exchange> active = new LinkedHashMap<>();
    private String rateSession = "";
    private long windowStart;
    private int windowCount;
    private final CaptureController.SessionObserver sessionObserver = this::captureSessionDidEnd;

    private static final class BodyBuffer {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        long observed;
        boolean supportsText;
        boolean hasGap;
        BodyBuffer(boolean supportsText) { this.supportsText = supportsText; }
    }
    private static final class Exchange {
        HTTPEvent event;
        final long started;
        final BodyBuffer request;
        final BodyBuffer response = new BodyBuffer(false);
        Exchange(HTTPEvent event, long started, boolean supportsRequest) {
            this.event = event; this.started = started; request = new BodyBuffer(supportsRequest);
        }
    }
    private interface ExchangeOperation { Result apply(Exchange exchange, long now); }
    private static final class FinalBody {
        final HTTPEvent.BodyReference reference;
        final HTTPBody body;
        FinalBody(HTTPEvent.BodyState state, long observed, HTTPBody body) { this(state, observed, body, false); }
        FinalBody(HTTPEvent.BodyState state, long observed, HTTPBody body, boolean isProcessed) {
            reference = new HTTPEvent.BodyReference(state, body == null ? null : body.id, observed, isProcessed); this.body = body;
        }
    }

    public HTTPRecorder(HTTPSink sink, CaptureController captureController, HTTPPrivacyPolicy privacy) {
        this(sink, captureController, privacy, 32, 50, 120_000);
    }
    public HTTPRecorder(HTTPSink sink, CaptureController captureController, HTTPPrivacyPolicy privacy,
                        int activeLimit, int requestsPerSecond, long exchangeLifetimeMillis) {
        if (activeLimit < 1 || activeLimit > 256 || requestsPerSecond < 1 || requestsPerSecond > 1000
            || exchangeLifetimeMillis < 1 || exchangeLifetimeMillis > 600_000) throw new IllegalArgumentException("Invalid HTTP capture limit");
        this.sink = Objects.requireNonNull(sink, "sink"); this.captureController = Objects.requireNonNull(captureController, "captureController");
        this.privacy = Objects.requireNonNull(privacy, "privacy"); this.activeLimit = activeLimit;
        this.requestsPerSecond = requestsPerSecond; this.exchangeLifetimeMillis = exchangeLifetimeMillis;
        captureController.register(sessionObserver);
    }

    public BeginResult begin(String method, String url, List<HTTPHeader> headers) {
        return begin(method, url, headers, HTTPEvent.Source.HOST);
    }

    public BeginResult begin(String method, String url, List<HTTPHeader> headers, HTTPEvent.Source source) {
        if (source == null) return new BeginResult(captureController.statistics.observe(Result.INVALID), null);
        CaptureController.Access<BeginResult> access = captureController.record(true, context -> {
            expire(context.monotonicMillis);
            if (!rateSession.equals(context.sessionId)) { rateSession = context.sessionId; windowStart = context.sessionStartMillis; windowCount = 0; }
            if (context.monotonicMillis >= windowStart && context.monotonicMillis - windowStart >= 1000) { windowStart = context.monotonicMillis; windowCount = 0; }
            if (windowCount >= requestsPerSecond) return new BeginResult(Result.RATE_LIMITED, null);
            windowCount++;
            if (active.size() >= activeLimit) return new BeginResult(Result.ACTIVE_LIMIT, null);
            HTTPPrivacyPolicy.Headers sanitized = privacy.headers(headers);
            String id = UUID.randomUUID().toString();
            HTTPEvent event = new HTTPEvent(id, context, captureController.clock.wallTimeMillis(), privacy.method(method), privacy.url(url), sanitized.values, sanitized.limited, source);
            Result result = write(new HTTPUpdate(event, true));
            if (result != Result.RECORDED) return new BeginResult(result, null);
            active.put(id, new Exchange(event, context.monotonicMillis, privacy.supportsBody(headers, false)));
            return new BeginResult(Result.RECORDED, new Token(id, context.sessionId));
        });
        BeginResult observed = access.admission == CaptureController.Admission.VALUE ? access.value : new BeginResult(admission(access.admission), null);
        captureController.statistics.observe(observed.result);
        return observed;
    }

    /** Callers must report any rejected chunk as incomplete when finishing that body. */
    public Result observeBody(Token token, Direction direction, long byteCount, BodyBytes copy) {
        return update(token, (exchange, now) -> {
            if (direction == null) return Result.INVALID;
            BodyBuffer buffer = direction == Direction.REQUEST ? exchange.request : exchange.response;
            if (direction == Direction.RESPONSE && exchange.event.state != HTTPEvent.State.RECEIVING) { buffer.hasGap = true; return Result.INVALID; }
            if (byteCount < 0) { buffer.hasGap = true; return Result.INVALID; }
            buffer.observed = byteCount > Long.MAX_VALUE - buffer.observed ? Long.MAX_VALUE : buffer.observed + byteCount;
            if (!buffer.supportsText || buffer.hasGap) return Result.RECORDED;
            int requested = (int) Math.min(byteCount, privacy.bodyByteLimit - buffer.bytes.size());
            if (requested == 0) return Result.RECORDED;
            try {
                byte[] bytes = copy.copyPrefix(requested);
                if (bytes == null || bytes.length != requested) { buffer.hasGap = true; buffer.bytes = new ByteArrayOutputStream(); return Result.INVALID; }
                buffer.bytes.write(bytes, 0, bytes.length);
                return Result.RECORDED;
            } catch (RuntimeException failure) { buffer.hasGap = true; buffer.bytes = new ByteArrayOutputStream(); return Result.INVALID; }
        });
    }

    public Result receiveResponse(Token token, int statusCode, List<HTTPHeader> headers, boolean bodyIsDecoded) {
        return receiveResponse(token, statusCode, headers, bodyIsDecoded, null);
    }

    public Result receiveResponse(Token token, int statusCode, List<HTTPHeader> headers, boolean bodyIsDecoded, String url) {
        return update(token, (exchange, now) -> {
            if (statusCode < 100 || statusCode > 599 || exchange.event.state != HTTPEvent.State.PENDING) return Result.INVALID;
            HTTPPrivacyPolicy.Headers sanitized = privacy.headers(headers);
            exchange.response.supportsText = privacy.supportsBody(headers, bodyIsDecoded);
            exchange.event = new HTTPEvent(exchange.event, sanitized.values, sanitized.limited, statusCode, url == null ? null : privacy.url(url), HTTPEvent.State.RECEIVING, null, null, null, null);
            return write(new HTTPUpdate(exchange.event, false));
        });
    }

    /** Completed means the collector confirmed response-body completion, not server-side business success. */
    public Result finish(Token token, HTTPEvent.State state, HTTPEvent.Failure failure, boolean requestComplete, boolean responseComplete) {
        return update(token, (exchange, now) -> {
            if ((state != HTTPEvent.State.COMPLETED && state != HTTPEvent.State.FAILED && state != HTTPEvent.State.CANCELLED && state != HTTPEvent.State.CLOSED_EARLY)
                || (state == HTTPEvent.State.COMPLETED && exchange.event.statusCode == null)
                || ((state == HTTPEvent.State.FAILED) != (failure != null))) return Result.INVALID;
            FinalBody request = finalizeBody(exchange.request, token.id + ".request", requestComplete, DiagnosticTextPolicy.Field.REQUEST_BODY);
            FinalBody response = finalizeBody(exchange.response, token.id + ".response", responseComplete && state == HTTPEvent.State.COMPLETED, DiagnosticTextPolicy.Field.RESPONSE_BODY);
            exchange.event = new HTTPEvent(exchange.event, null, false, null, null, state, failure,
                now >= exchange.started ? now - exchange.started : null, request.reference, response.reference);
            List<HTTPBody> bodies = new ArrayList<>();
            if (request.body != null) bodies.add(request.body);
            if (response.body != null) bodies.add(response.body);
            Result result = write(new HTTPUpdate(exchange.event, false, bodies));
            // Never retry terminal observations with raw content when storage is unavailable.
            active.remove(token.id);
            return result;
        });
    }

    /** Lazy timeout/expiry cleanup, also useful when refreshing without new traffic. */
    public Result maintenance() {
        Result value = captureController.inspect(status -> {
            if (status.mode != CaptureController.Mode.DETAILED) return Result.INACTIVE;
            expire(captureController.clock.monotonicMillis());
            return Result.RECORDED;
        });
        return value == null ? Result.BUSY : value;
    }

    /** Ends SDK observation without asserting a transport outcome or cancelling the host request. */
    public Result stopObserving(Token token) {
        return update(token, (exchange, now) -> {
            Result result = endObservation(exchange);
            active.remove(token.id);
            return result;
        });
    }

    private void captureSessionDidEnd() {
        for (Exchange exchange : active.values()) captureController.statistics.observe(endObservation(exchange));
        active.clear();
    }

    private void expire(long now) {
        Iterator<Exchange> iterator = active.values().iterator();
        while (iterator.hasNext()) {
            Exchange exchange = iterator.next();
            if (now >= exchange.started && now - exchange.started >= exchangeLifetimeMillis) { iterator.remove(); captureController.statistics.observe(endObservation(exchange)); }
        }
    }

    private Result endObservation(Exchange exchange) {
        HTTPEvent.BodyState state = privacy.bodyPolicy == HTTPPrivacyPolicy.BodyPolicy.OMIT ? HTTPEvent.BodyState.OMITTED : HTTPEvent.BodyState.INCOMPLETE;
        HTTPEvent event = new HTTPEvent(exchange.event, null, false, null, null, HTTPEvent.State.CAPTURE_ENDED, null, null,
            new HTTPEvent.BodyReference(state, null, exchange.request.observed),
            new HTTPEvent.BodyReference(state, null, exchange.response.observed));
        return write(new HTTPUpdate(event, false));
    }

    private Result update(Token token, ExchangeOperation body) {
        if (token == null) return captureController.statistics.observe(Result.INVALID);
        return result(captureController.record(true, context -> {
            expire(context.monotonicMillis);
            Exchange exchange = active.get(token.id);
            if (!token.sessionId.equals(context.sessionId) || exchange == null) return Result.STALE;
            return body.apply(exchange, context.monotonicMillis);
        }));
    }

    private Result result(CaptureController.Access<Result> access) {
        return captureController.statistics.observe(access.admission == CaptureController.Admission.VALUE ? access.value : admission(access.admission));
    }
    private static Result admission(CaptureController.Admission admission) {
        switch (admission) {
            case INACTIVE: return Result.INACTIVE;
            case BUSY: return Result.BUSY;
            case SEQUENCE_EXHAUSTED: return Result.SEQUENCE_EXHAUSTED;
            default: return Result.SINK_FAILURE;
        }
    }
    private Result write(HTTPUpdate update) {
        try {
            HTTPSink.WriteResult result = sink.write(update);
            if (result == HTTPSink.WriteResult.STORED) return Result.RECORDED;
            if (result == HTTPSink.WriteResult.BUSY) return Result.BUSY;
            if (result == HTTPSink.WriteResult.TOO_LARGE) return Result.TOO_LARGE;
            if (result == HTTPSink.WriteResult.MISSING) return Result.MISSING;
            return Result.SINK_FAILURE;
        } catch (RuntimeException failure) { return Result.SINK_FAILURE; }
    }

    private FinalBody finalizeBody(BodyBuffer buffer, String id, boolean complete, DiagnosticTextPolicy.Field field) {
        if (privacy.bodyPolicy == HTTPPrivacyPolicy.BodyPolicy.OMIT) return new FinalBody(HTTPEvent.BodyState.OMITTED, buffer.observed, null);
        if (!complete || buffer.hasGap) return new FinalBody(HTTPEvent.BodyState.INCOMPLETE, buffer.observed, null);
        if (!buffer.supportsText) return new FinalBody(HTTPEvent.BodyState.UNSUPPORTED, buffer.observed, null);
        byte[] bytes = buffer.bytes.toByteArray();
        boolean truncated = buffer.observed > bytes.length;
        int count = truncated ? HTTPPrivacyPolicy.completeUTF8Prefix(bytes, bytes.length) : bytes.length;
        String text = HTTPPrivacyPolicy.decode(bytes, count);
        if (text == null) return new FinalBody(HTTPEvent.BodyState.INVALID_TEXT, buffer.observed, null);
        String processed = DiagnosticTextPolicy.apply(privacy.textPolicy, field, null, text, truncated, privacy.bodyByteLimit);
        if (processed == null) return new FinalBody(HTTPEvent.BodyState.OMITTED, buffer.observed, null);
        return new FinalBody(truncated ? HTTPEvent.BodyState.TRUNCATED : HTTPEvent.BodyState.AVAILABLE,
            buffer.observed, new HTTPBody(id, processed), !processed.equals(text));
    }
}
