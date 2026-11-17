package com.sandrox.osservio.store;

import com.sandrox.osservio.core.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NetworkErrorTest {
    @Test public void errorDetailsAreAdmittedBoundedAndIncludedInFrozenAccounting() {
        CaptureController capture = new CaptureController(false, true, LogClock.SYSTEM);
        MemoryHTTPStore history = new MemoryHTTPStore();
        HTTPRecorder recorder = new HTTPRecorder(history, capture, new HTTPPrivacyPolicy());
        HTTPRecorder.Token token = recorder.begin("GET", "https://example.com", Collections.emptyList()).token;
        AtomicInteger reads = new AtomicInteger();
        String message = String.join("", Collections.nCopies(2000, "例"));
        NetworkError.Source source = () -> { reads.incrementAndGet(); return new NetworkError.Input("fixture.domain", "-1009", "SyntheticFailure", message); };
        assertEquals(HTTPRecorder.Result.INVALID, recorder.finish(token, HTTPEvent.State.COMPLETED, null, true, true, null, source));
        assertEquals(0, reads.get());
        assertEquals(HTTPRecorder.Result.RECORDED, recorder.finish(token, HTTPEvent.State.FAILED, HTTPEvent.Failure.CONNECTION, true, false, null, source));
        HTTPEvent event = history.snapshot().events.get(0); NetworkError error = event.error;
        assertEquals(1, reads.get()); assertEquals("fixture.domain", error.domain); assertEquals("-1009", error.code);
        assertEquals("SyntheticFailure", error.type); assertEquals(4095, error.message.getBytes(StandardCharsets.UTF_8).length);
        assertTrue(error.isLimited); assertFalse(error.isProcessed);
        assertEquals(event.textByteCount, history.snapshot().retainedTextBytes);
        IncidentHTTP frozen = IncidentHTTP.capture(history.snapshot(), 1, history, false, 50, 65536, 65536);
        assertEquals(error.message, frozen.events.get(0).error.message); assertEquals(event.textByteCount, frozen.retainedTextBytes);
        assertEquals(HTTPRecorder.Result.STALE, recorder.finish(token, HTTPEvent.State.FAILED, HTTPEvent.Failure.UNKNOWN, true, false, null, source));
        assertEquals(1, reads.get());
        HTTPRecorder.Token next = recorder.begin("GET", "https://example.com/next", Collections.emptyList()).token;
        capture.close();
        assertEquals(HTTPRecorder.Result.INACTIVE, recorder.finish(next, HTTPEvent.State.FAILED, HTTPEvent.Failure.UNKNOWN, true, false, null, source));
        assertEquals(1, reads.get());
    }

    @Test public void policyFailureCannotExposeRawErrorOrCloseText() {
        DiagnosticTextPolicy policy = input -> {
            if (input.field == DiagnosticTextPolicy.Field.NETWORK_ERROR_MESSAGE) throw new IllegalStateException("synthetic policy failure");
            if (input.field == DiagnosticTextPolicy.Field.WEB_SOCKET_CLOSE_REASON) return null;
            if (input.field == DiagnosticTextPolicy.Field.NETWORK_ERROR_CODE) return "reviewed";
            return input.text;
        };
        CaptureController capture = new CaptureController(false, true, LogClock.SYSTEM);
        MemoryWebSocketStore history = new MemoryWebSocketStore();
        WebSocketRecorder recorder = new WebSocketRecorder(history, capture, new WebSocketPrivacyPolicy(true, null, true, 4096, true, 4096, policy));
        WebSocketRecorder.Token token = recorder.begin("wss://example.com", Collections.emptyList(), WebSocketEvent.Source.HOST).token;
        assertEquals(WebSocketRecorder.Result.RECORDED, recorder.lifecycle(token, WebSocketEvent.Kind.CLOSING, Collections.emptyList(), null, 1000, null, null, () -> "synthetic private reason"));
        assertEquals(WebSocketRecorder.Result.RECORDED, recorder.lifecycle(token, WebSocketEvent.Kind.FAILED, Collections.emptyList(), null, 1000, WebSocketEvent.Failure.TRANSPORT,
            () -> new NetworkError.Input("domain", "42", "type", "synthetic private error"), () -> "synthetic private reason"));
        WebSocketEvent event = history.snapshot().events.get(2);
        assertNull(event.error.message); assertEquals("reviewed", event.error.code); assertTrue(event.error.isProcessed);
        assertNull(event.closeReason.text); assertTrue(event.closeReason.isProcessed); assertEquals(Integer.valueOf(1000), event.closeCode);
        assertEquals(WebSocketRecorder.Result.STALE, recorder.lifecycle(token, WebSocketEvent.Kind.CLOSED, Collections.emptyList(), null, 1000, null));
        assertEquals(WebSocketEvent.Kind.FAILED, history.snapshot().events.get(2).kind);
    }

    @Test public void sourceFailureIsVisibleWithoutLosingTheTransportOutcome() {
        MemoryHTTPStore history = new MemoryHTTPStore();
        HTTPRecorder recorder = new HTTPRecorder(history, new CaptureController(false, true, LogClock.SYSTEM), new HTTPPrivacyPolicy());
        HTTPRecorder.Token token = recorder.begin("GET", "https://example.com", Collections.emptyList()).token;
        assertEquals(HTTPRecorder.Result.RECORDED, recorder.finish(token, HTTPEvent.State.CANCELLED, null, true, false, null,
            () -> { throw new IllegalStateException("source unavailable"); }));
        HTTPEvent event = history.snapshot().events.get(0);
        assertEquals(HTTPEvent.State.CANCELLED, event.state); assertTrue(event.error.isLimited); assertEquals(0, event.error.textByteCount);
        assertThrows(IllegalArgumentException.class, () -> NetworkError.restore(null, null, null, null, false, false));
        assertThrows(IllegalArgumentException.class, () -> NetworkError.restore(null, String.join("", Collections.nCopies(65, "x")), null, null, false, false));
        assertThrows(IllegalArgumentException.class, () -> WebSocketCloseReason.restore(null, false, false));
    }

    @Test public void rateLimitedSocketDoesNotReadCloseReason() {
        MemoryWebSocketStore history = new MemoryWebSocketStore();
        LogClock clock = new LogClock() {
            @Override public long wallTimeMillis() { return 1000; }
            @Override public long monotonicMillis() { return 0; }
        };
        WebSocketRecorder recorder = new WebSocketRecorder(history, new CaptureController(false, true, clock), new WebSocketPrivacyPolicy(), 16, 1);
        WebSocketRecorder.Token token = recorder.begin("wss://example.com", Collections.emptyList(), WebSocketEvent.Source.HOST).token;
        assertEquals(WebSocketRecorder.Result.RATE_LIMITED, recorder.lifecycle(token, WebSocketEvent.Kind.CLOSED, Collections.emptyList(), null, 1000, null, null,
            () -> { throw new AssertionError("Rejected work must not read source fields"); }));
        assertEquals(0, recorder.status().activeConnections);
    }
}
