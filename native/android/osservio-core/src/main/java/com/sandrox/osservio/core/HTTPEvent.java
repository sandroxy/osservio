package com.sandrox.osservio.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
        BodyReference(BodyState state, String blobId, long observedBytes) {
            this.state = state; this.blobId = blobId; this.observedBytes = observedBytes;
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
    public final BodyReference requestBody;
    public final BodyReference responseBody;
    public final int textByteCount;

    HTTPEvent(String id, CaptureController.Context context, long timestamp, String method, String url,
              List<HTTPHeader> headers, boolean limited, Source source) {
        this.id = id; sessionId = context.sessionId; sequence = context.sequence; revision = 0;
        timestampMillis = timestamp; this.method = method; this.url = url; this.source = source; responseURL = null;
        requestHeaders = Collections.unmodifiableList(new ArrayList<>(headers)); responseHeaders = Collections.emptyList();
        headersLimited = limited; statusCode = null; state = State.PENDING; failure = null; durationMillis = null;
        requestBody = new BodyReference(BodyState.PENDING, null, 0); responseBody = new BodyReference(BodyState.PENDING, null, 0);
        textByteCount = size();
    }

    HTTPEvent(HTTPEvent previous, List<HTTPHeader> headers, boolean limited, Integer code, String responseURL, State state,
              Failure failure, Long duration, BodyReference request, BodyReference response) {
        id = previous.id; sessionId = previous.sessionId; sequence = previous.sequence; revision = previous.revision + 1;
        timestampMillis = previous.timestampMillis; method = previous.method; url = previous.url; source = previous.source;
        this.responseURL = responseURL == null ? previous.responseURL : responseURL;
        requestHeaders = previous.requestHeaders;
        responseHeaders = headers == null ? previous.responseHeaders : Collections.unmodifiableList(new ArrayList<>(headers));
        headersLimited = limited || previous.headersLimited; statusCode = code == null ? previous.statusCode : code;
        this.state = state; this.failure = failure; durationMillis = duration;
        requestBody = request == null ? previous.requestBody : request;
        responseBody = response == null ? previous.responseBody : response;
        textByteCount = size();
    }

    private int size() {
        int count = method.getBytes(StandardCharsets.UTF_8).length + url.getBytes(StandardCharsets.UTF_8).length;
        if (responseURL != null) count += responseURL.getBytes(StandardCharsets.UTF_8).length;
        for (HTTPHeader header : requestHeaders) count += header.name.getBytes(StandardCharsets.UTF_8).length + header.value.getBytes(StandardCharsets.UTF_8).length;
        for (HTTPHeader header : responseHeaders) count += header.name.getBytes(StandardCharsets.UTF_8).length + header.value.getBytes(StandardCharsets.UTF_8).length;
        return count;
    }
}
