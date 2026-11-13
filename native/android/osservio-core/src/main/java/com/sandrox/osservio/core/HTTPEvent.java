package com.sandrox.osservio.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Arrays;
import java.util.Locale;

/** Sanitized metadata. Body content is queried separately and may be evicted independently. */
public final class HTTPEvent {
    public enum State { PENDING, RECEIVING, COMPLETED, FAILED, CANCELLED, CLOSED_EARLY, CAPTURE_ENDED }
    public enum Failure { TRANSPORT, TIMEOUT, TLS, CONNECTION, UNKNOWN }
    public enum Source { HOST, OKHTTP, URL_SESSION }
    public enum BodyState { PENDING, OMITTED, UNSUPPORTED, INCOMPLETE, INVALID_TEXT, AVAILABLE, TRUNCATED }

    public static final class BodyReference {
        public final BodyState state;
        public final String blobId;
        public final long observedBytes;
        public final boolean isProcessed;
        BodyReference(BodyState state, String blobId, long observedBytes) {
            this(state, blobId, observedBytes, false);
        }
        BodyReference(BodyState state, String blobId, long observedBytes, boolean isProcessed) {
            this.state = state; this.blobId = blobId; this.observedBytes = observedBytes; this.isProcessed = isProcessed;
        }
        public static BodyReference restore(BodyState state, String blobId, long observedBytes) {
            return restore(state, blobId, observedBytes, false);
        }
        public static BodyReference restore(BodyState state, String blobId, long observedBytes, boolean isProcessed) {
            boolean content = state == BodyState.AVAILABLE || state == BodyState.TRUNCATED;
            if ((!content && isProcessed) || state == null || observedBytes < 0 || content != (blobId != null) || (blobId != null && !EvidenceValidation.httpBodyId(blobId))
                || (state == BodyState.PENDING && observedBytes != 0) || (state == BodyState.AVAILABLE && observedBytes > 65536)
                || (state == BodyState.TRUNCATED && observedBytes == 0)) throw EvidenceValidation.invalid();
            return new BodyReference(state, blobId, observedBytes, isProcessed);
        }
    }

    public final String id;
    public final String sessionId;
    public final long sequence;
    public final long revision;
    public final long timestampMillis;
    public final String method;
    public final Source source;
    public final String url;
    public final String responseURL;
    public final List<HTTPHeader> requestHeaders;
    public final List<HTTPHeader> responseHeaders;
    public final boolean headersLimited;
    public final Integer statusCode;
    public final State state;
    public final Failure failure;
    public final Long durationMillis;
    public final HTTPMetrics metrics;
    public final BodyReference requestBody;
    public final BodyReference responseBody;
    public final int textByteCount;

    HTTPEvent(String id, CaptureController.Context context, long timestamp, String method, String url,
              List<HTTPHeader> headers, boolean limited, Source source) {
        this.id = id; sessionId = context.sessionId; sequence = context.sequence; revision = 0;
        timestampMillis = timestamp; this.method = method; this.url = url; this.source = source; responseURL = null;
        requestHeaders = Collections.unmodifiableList(new ArrayList<>(headers)); responseHeaders = Collections.emptyList();
        headersLimited = limited; statusCode = null; state = State.PENDING; failure = null; durationMillis = null; metrics = null;
        requestBody = new BodyReference(BodyState.PENDING, null, 0); responseBody = new BodyReference(BodyState.PENDING, null, 0);
        textByteCount = size();
    }

    HTTPEvent(HTTPEvent previous, List<HTTPHeader> headers, boolean limited, Integer code, String responseURL, State state,
              Failure failure, Long duration, BodyReference request, BodyReference response) {
        this(previous, headers, limited, code, responseURL, state, failure, duration, request, response, null);
    }
    HTTPEvent(HTTPEvent previous, List<HTTPHeader> headers, boolean limited, Integer code, String responseURL, State state,
              Failure failure, Long duration, BodyReference request, BodyReference response, HTTPMetrics metrics) {
        id = previous.id; sessionId = previous.sessionId; sequence = previous.sequence; revision = previous.revision + 1;
        timestampMillis = previous.timestampMillis; method = previous.method; url = previous.url; source = previous.source;
        this.responseURL = responseURL == null ? previous.responseURL : responseURL;
        requestHeaders = previous.requestHeaders;
        responseHeaders = headers == null ? previous.responseHeaders : Collections.unmodifiableList(new ArrayList<>(headers));
        headersLimited = limited || previous.headersLimited; statusCode = code == null ? previous.statusCode : code;
        this.state = state; this.failure = failure; durationMillis = duration; this.metrics = metrics;
        requestBody = request == null ? previous.requestBody : request;
        responseBody = response == null ? previous.responseBody : response;
        textByteCount = size();
    }

    private int size() {
        int count = method.getBytes(StandardCharsets.UTF_8).length + url.getBytes(StandardCharsets.UTF_8).length;
        if (responseURL != null) count += responseURL.getBytes(StandardCharsets.UTF_8).length;
        for (HTTPHeader header : requestHeaders) count += header.name.getBytes(StandardCharsets.UTF_8).length + header.value.getBytes(StandardCharsets.UTF_8).length;
        for (HTTPHeader header : responseHeaders) count += header.name.getBytes(StandardCharsets.UTF_8).length + header.value.getBytes(StandardCharsets.UTF_8).length;
        return count + (metrics == null ? 0 : metrics.textByteCount());
    }

    /** Validates sanitized metadata while preserving identity, revision, time, and transport outcome. */
    public static HTTPEvent restore(String id, String sessionId, long sequence, long revision, long timestampMillis,
                                    String method, Source source, String url, String responseURL, List<HTTPHeader> requestHeaders,
                                    List<HTTPHeader> responseHeaders, boolean headersLimited, Integer statusCode, State state,
                                    Failure failure, Long durationMillis, BodyReference requestBody, BodyReference responseBody) {
        return restore(id, sessionId, sequence, revision, timestampMillis, method, source, url, responseURL, requestHeaders,
            responseHeaders, headersLimited, statusCode, state, failure, durationMillis, requestBody, responseBody, null);
    }
    public static HTTPEvent restore(String id, String sessionId, long sequence, long revision, long timestampMillis,
                                    String method, Source source, String url, String responseURL, List<HTTPHeader> requestHeaders,
                                    List<HTTPHeader> responseHeaders, boolean headersLimited, Integer statusCode, State state,
                                    Failure failure, Long durationMillis, BodyReference requestBody, BodyReference responseBody, HTTPMetrics metrics) {
        if (!EvidenceValidation.id(id) || !EvidenceValidation.id(sessionId) || sequence <= 0 || revision < 0 || source == null || state == null
            || !EvidenceValidation.method(method)
            || !EvidenceValidation.url(url) || (responseURL != null && !EvidenceValidation.url(responseURL))
            || !EvidenceValidation.headers(requestHeaders) || !EvidenceValidation.headers(responseHeaders)
            || (statusCode != null && (statusCode < 100 || statusCode > 599)) || (durationMillis != null && durationMillis < 0)
            || (state == State.FAILED) != (failure != null) || requestBody == null || responseBody == null
            || (requestBody.blobId != null && !requestBody.blobId.toLowerCase(Locale.ROOT).equals(id.toLowerCase(Locale.ROOT) + ".request"))
            || (responseBody.blobId != null && !responseBody.blobId.toLowerCase(Locale.ROOT).equals(id.toLowerCase(Locale.ROOT) + ".response"))
            || (statusCode == null && (responseURL != null || !responseHeaders.isEmpty()))) throw EvidenceValidation.invalid();
        switch (state) {
            case PENDING: case RECEIVING:
                if (metrics != null || durationMillis != null || requestBody.state != BodyState.PENDING || responseBody.state != BodyState.PENDING
                    || revision != (state == State.PENDING ? 0 : 1) || (state == State.RECEIVING) != (statusCode != null)) throw EvidenceValidation.invalid();
                break;
            case CAPTURE_ENDED:
                if (metrics != null || durationMillis != null || !unfinished(requestBody.state) || !unfinished(responseBody.state)
                    || revision != (statusCode == null ? 1 : 2)) throw EvidenceValidation.invalid();
                break;
            case COMPLETED: case FAILED: case CANCELLED: case CLOSED_EARLY:
                if (requestBody.state == BodyState.PENDING || responseBody.state == BodyState.PENDING || revision != (statusCode == null ? 1 : 2)
                    || (state == State.COMPLETED && statusCode == null) || (state != State.COMPLETED && !unfinished(responseBody.state))) throw EvidenceValidation.invalid();
                break;
            default: throw EvidenceValidation.invalid();
        }
        return new HTTPEvent(id, sessionId, sequence, revision, timestampMillis, method, source, url, responseURL, requestHeaders,
            responseHeaders, headersLimited, statusCode, state, failure, durationMillis, requestBody, responseBody, metrics);
    }
    private static boolean unfinished(BodyState state) { return state == BodyState.OMITTED || state == BodyState.INCOMPLETE; }
    private HTTPEvent(String id, String sessionId, long sequence, long revision, long timestampMillis, String method, Source source,
                      String url, String responseURL, List<HTTPHeader> requestHeaders, List<HTTPHeader> responseHeaders,
                      boolean headersLimited, Integer statusCode, State state, Failure failure, Long durationMillis,
                      BodyReference requestBody, BodyReference responseBody, HTTPMetrics metrics) {
        this.id = id; this.sessionId = sessionId; this.sequence = sequence; this.revision = revision; this.timestampMillis = timestampMillis;
        this.method = method; this.source = source; this.url = url; this.responseURL = responseURL;
        this.requestHeaders = Collections.unmodifiableList(new ArrayList<>(requestHeaders));
        this.responseHeaders = Collections.unmodifiableList(new ArrayList<>(responseHeaders)); this.headersLimited = headersLimited;
        this.statusCode = statusCode; this.state = state; this.failure = failure; this.durationMillis = durationMillis; this.metrics = metrics;
        this.requestBody = requestBody; this.responseBody = responseBody; textByteCount = size();
    }
}
