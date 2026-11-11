package com.sandrox.osservio.store;

import com.sandrox.osservio.core.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class DiagnosticPolicyTest {
    private static final List<HTTPHeader> HEADERS = Arrays.asList(
        new HTTPHeader("Content-Type", "application/json; charset=utf-8"),
        new HTTPHeader("Authorization", "Bearer " + String.join("", Collections.nCopies(1200, "a"))),
        new HTTPHeader("X_Custom", "trace-value"));

    @Test public void defaultsPreserveUsefulContentAndExactRequestMetadata() {
        CaptureController capture = new CaptureController(false, true, LogClock.SYSTEM);
        MemoryHTTPStore store = new MemoryHTTPStore();
        HTTPRecorder recorder = new HTTPRecorder(store, capture, new HTTPPrivacyPolicy());
        String url = "https://user:fixture@example.com/api?name=%E4%BD%A0&empty=&x=1&x=2#section";
        HTTPRecorder.Token token = recorder.begin("PROPFIND", url, HEADERS).token;
        assertNotNull(token);
        byte[] bytes = "{\"token\":\"synthetic-only\",\"page\":2}".getBytes(StandardCharsets.UTF_8);
        assertEquals(HTTPRecorder.Result.RECORDED, recorder.observeBody(token, HTTPRecorder.Direction.REQUEST, bytes.length, count -> Arrays.copyOf(bytes, count)));
        recorder.receiveResponse(token, 400, HEADERS, false);
        recorder.finish(token, HTTPEvent.State.COMPLETED, null, true, true);
        HTTPEvent event = store.snapshot().events.get(0);
        assertEquals(url, event.url); assertEquals("PROPFIND", event.method);
        for (int i = 0; i < HEADERS.size(); i++) assertEquals(HEADERS.get(i).value, event.requestHeaders.get(i).value);
        assertFalse(event.headersLimited); assertFalse(event.requestBody.isProcessed);
        assertEquals(new String(bytes, StandardCharsets.UTF_8), store.body(event.requestBody.blobId).body.text);
        EvidenceRestorationTest.check(event);
    }

    @Test public void hostProcessingPreservesOriginalByteAccountingInFrozenAttachments() {
        DiagnosticTextPolicy policy = input -> {
            if (input.field == DiagnosticTextPolicy.Field.HEADER && "authorization".equals(input.headerName)) return null;
            if (input.field == DiagnosticTextPolicy.Field.REQUEST_BODY || input.field == DiagnosticTextPolicy.Field.WEB_SOCKET_MESSAGE) return "{\"reviewed\":true}";
            return input.text;
        };
        CaptureController capture = new CaptureController(false, true, LogClock.SYSTEM);
        MemoryHTTPStore http = new MemoryHTTPStore(); MemoryWebSocketStore sockets = new MemoryWebSocketStore();
        HTTPRecorder recorder = new HTTPRecorder(http, capture,
            new HTTPPrivacyPolicy(true, null, HTTPPrivacyPolicy.BodyPolicy.PUBLIC_UTF8, 16384, true, 4096, policy));
        HTTPRecorder.Token token = recorder.begin("POST", "https://example.com?q=value", HEADERS).token;
        recorder.observeBody(token, HTTPRecorder.Direction.REQUEST, 2, count -> "{}".getBytes(StandardCharsets.UTF_8));
        recorder.finish(token, HTTPEvent.State.FAILED, HTTPEvent.Failure.CONNECTION, true, false);
        WebSocketRecorder socket = new WebSocketRecorder(sockets, capture,
            new WebSocketPrivacyPolicy(true, null, true, 4096, true, 4096, policy));
        WebSocketRecorder.Token connection = socket.begin("wss://example.com?token=fixture", Collections.emptyList(), WebSocketEvent.Source.HOST).token;
        socket.text(connection, WebSocketEvent.Direction.INCOMING, WebSocketEvent.Outcome.RECEIVED, "{}");
        IncidentHTTP frozen = IncidentHTTP.capture(http.snapshot(), 1, http, true, 50, 65536, 65536);
        HTTPEvent event = frozen.events.get(0);
        assertTrue(event.requestBody.isProcessed); assertEquals(2, event.requestBody.observedBytes);
        assertEquals("[redacted]", event.requestHeaders.get(1).value);
        assertEquals("{\"reviewed\":true}", frozen.payloads.get(event.requestBody.blobId).text);
        IncidentWebSockets messages = IncidentWebSockets.capture(sockets.snapshot(), 1, sockets, true, 200, 65536, 65536);
        WebSocketEvent.PayloadReference payload = messages.events.get(1).payload;
        assertTrue(payload.isProcessed); assertEquals(Long.valueOf(2), payload.byteCount);
    }

    @Test public void failedAndOversizedPolicyNeverFallsBackToRawContent() {
        for (DiagnosticTextPolicy policy : new DiagnosticTextPolicy[] {
            input -> { throw new IllegalStateException("fixture"); },
            input -> String.join("", Collections.nCopies(70000, "x"))
        }) {
            MemoryHTTPStore store = new MemoryHTTPStore();
            HTTPRecorder recorder = new HTTPRecorder(store, new CaptureController(false, true, LogClock.SYSTEM),
                new HTTPPrivacyPolicy(true, null, HTTPPrivacyPolicy.BodyPolicy.PUBLIC_UTF8, 16384, true, 4096, policy));
            HTTPRecorder.Token token = recorder.begin("POST", "https://example.com?secret=fixture", HEADERS).token;
            recorder.observeBody(token, HTTPRecorder.Direction.REQUEST, 2, count -> "{}".getBytes(StandardCharsets.UTF_8));
            recorder.finish(token, HTTPEvent.State.FAILED, HTTPEvent.Failure.CONNECTION, true, false);
            HTTPEvent event = store.snapshot().events.get(0);
            assertEquals("[unavailable]", event.url); assertEquals(HTTPEvent.BodyState.OMITTED, event.requestBody.state);
            for (HTTPHeader header : event.requestHeaders) assertEquals("[redacted]", header.value);
            assertEquals(0, store.snapshot().retainedBodyBytes);
        }
    }

    @Test public void closedAccountOwnerCannotResumeOrAcceptLateCallbacks() {
        CaptureController old = new CaptureController(true, true, LogClock.SYSTEM);
        MemoryHTTPStore oldStore = new MemoryHTTPStore();
        HTTPRecorder recorder = new HTTPRecorder(oldStore, old, new HTTPPrivacyPolicy());
        HTTPRecorder.Token token = recorder.begin("GET", "https://example.com/account-a", Collections.emptyList()).token;
        MemoryLogStore logs = new MemoryLogStore(); MemoryBreadcrumbStore crumbs = new MemoryBreadcrumbStore();
        MemoryWebSocketStore sockets = new MemoryWebSocketStore();
        LogRecorder logger = new LogRecorder(logs, old); BreadcrumbRecorder breadcrumb = new BreadcrumbRecorder(crumbs, old);
        WebSocketRecorder socket = new WebSocketRecorder(sockets, old, new WebSocketPrivacyPolicy());
        WebSocketRecorder.Token connection = socket.begin("wss://example.com/account-a", Collections.emptyList(), WebSocketEvent.Source.HOST).token;
        assertTrue(old.close()); assertTrue(old.close()); assertTrue(old.status().isClosed);
        assertThrows(IllegalStateException.class, () -> old.setDebugEnabled(true));
        assertThrows(IllegalStateException.class, old::startDetailed); assertFalse(old.setBaselineEnabled(true));
        assertEquals(HTTPRecorder.Result.INACTIVE, recorder.receiveResponse(token, 200, HEADERS, false));
        assertEquals(LogRecorder.Result.INACTIVE, logger.record(LogLevel.INFO, "account", LogMessage.publicText("late A")));
        assertEquals(BreadcrumbRecorder.Result.INACTIVE, breadcrumb.record(BreadcrumbEvent.Kind.HOST_MARKER, "account.late"));
        assertEquals(WebSocketRecorder.Result.INACTIVE, socket.text(connection, WebSocketEvent.Direction.INCOMING, WebSocketEvent.Outcome.RECEIVED, "late A"));
        assertEquals(0, socket.status().activeConnections);
        MemoryHTTPStore nextStore = new MemoryHTTPStore();
        HTTPRecorder next = new HTTPRecorder(nextStore, new CaptureController(false, true, LogClock.SYSTEM), new HTTPPrivacyPolicy());
        next.begin("GET", "https://example.com/account-b", Collections.emptyList());
        assertEquals(HTTPEvent.State.CAPTURE_ENDED, oldStore.snapshot().events.get(0).state);
        assertEquals(1, nextStore.snapshot().events.size());
        assertEquals("https://example.com/account-b", nextStore.snapshot().events.get(0).url);
        assertEquals(HTTPRecorder.Result.STALE, next.receiveResponse(token, 200, HEADERS, false));
    }
}
