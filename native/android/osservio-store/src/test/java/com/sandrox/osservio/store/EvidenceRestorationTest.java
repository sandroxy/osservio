package com.sandrox.osservio.store;

import com.sandrox.osservio.core.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.junit.Test;
import static org.junit.Assert.*;

public final class EvidenceRestorationTest {
    private static final String ID = "12345678-1234-1234-1234-123456789abc";
    private static final String OTHER = "abcdefab-1234-1234-1234-123456789abc";

    @Test public void sharedRestorationBoundaries() throws Exception {
        for (String line : Files.readAllLines(Paths.get(System.getProperty("osservio.fixtures"), "evidence-restoration.tsv"), StandardCharsets.UTF_8)) {
            String[] row = line.split("\t"); assertEquals(5, row.length);
            try {
                switch (row[1]) {
                    case "log": log(row[2], row[3]); break;
                    case "http": http(row[2], row[3]); break;
                    case "websocket": socket(row[2], row[3]); break;
                    default: fail("Unknown fixture family: " + row[1]);
                }
                assertEquals(row[0], "true", row[4]);
            } catch (IllegalArgumentException failure) { assertEquals(row[0], "false", row[4]); }
        }
    }
    @Test public void privateLogsAndExactIdentitySurviveRestoration() {
        LogEvent event = LogEvent.restore(ID, Long.MAX_VALUE, Long.MIN_VALUE, LogLevel.ERROR, "test", "[private]", true, false);
        assertEquals(ID, event.sessionId); assertEquals(Long.MAX_VALUE, event.sequence); assertEquals(Long.MIN_VALUE, event.timestampMillis);
        assertEquals("[private]", event.message); assertTrue(event.isPrivate);
        assertThrows(IllegalArgumentException.class, () -> LogEvent.restore(ID, 1, 0, LogLevel.ERROR, "test", "[private]", true, true));
        assertThrows(IllegalArgumentException.class, () -> LogEvent.restore(ID, 1, 0, LogLevel.INFO, "test", "\uD800", false, false));
    }
    @Test public void bodyIdentityBoundsAndReferencesCannotInventContent() {
        String text = "😀".repeat(16384);
        HTTPBody body = HTTPBody.restore(ID + ".response", text);
        assertEquals(65536, body.byteCount); assertEquals(text, body.text);
        assertThrows(IllegalArgumentException.class, () -> HTTPBody.restore(ID + ".response", text + "x"));
        assertThrows(IllegalArgumentException.class, () -> HTTPBody.restore(ID + "..request", ""));
        assertThrows(IllegalArgumentException.class, () -> bodyReference(HTTPEvent.BodyState.OMITTED, body.id, 65536));
        assertThrows(IllegalArgumentException.class, () -> bodyReference(HTTPEvent.BodyState.AVAILABLE, null, 1));
        assertThrows(IllegalArgumentException.class, () -> bodyReference(HTTPEvent.BodyState.AVAILABLE, body.id, 65537));
        assertThrows(IllegalArgumentException.class, () -> bodyReference(HTTPEvent.BodyState.PENDING, null, 1));
        assertThrows(IllegalArgumentException.class, () -> bodyReference(HTTPEvent.BodyState.TRUNCATED, body.id, 0));
        WebSocketPayload payload = WebSocketPayload.restore(ID, text); assertEquals(65536, payload.byteCount);
        assertThrows(IllegalArgumentException.class, () -> WebSocketPayload.restore(ID, text + "x"));
        assertThrows(IllegalArgumentException.class, () -> WebSocketPayload.restore(ID, "\uD800"));
        assertThrows(IllegalArgumentException.class, () -> WebSocketEvent.PayloadReference.restore(WebSocketEvent.PayloadState.BINARY, ID, 1L));
        assertThrows(IllegalArgumentException.class, () -> WebSocketEvent.PayloadReference.restore(WebSocketEvent.PayloadState.TRUNCATED, ID, 9L));
        assertThrows(IllegalArgumentException.class, () -> WebSocketEvent.PayloadReference.restore(WebSocketEvent.PayloadState.AVAILABLE, ID, null));
        assertThrows(IllegalArgumentException.class, () -> WebSocketEvent.PayloadReference.restore(WebSocketEvent.PayloadState.OMITTED, null, 9L));
        assertEquals(Long.valueOf(0), WebSocketEvent.PayloadReference.restore(WebSocketEvent.PayloadState.AVAILABLE, ID, 0L).byteCount);
    }
    @Test public void recordedHTTPStatesRestoreWithoutInventingTransportResults() {
        for (HTTPEvent.State terminal : Arrays.asList(HTTPEvent.State.COMPLETED, HTTPEvent.State.FAILED, HTTPEvent.State.CANCELLED, HTTPEvent.State.CLOSED_EARLY, HTTPEvent.State.CAPTURE_ENDED)) {
            for (boolean received : new boolean[] {false, true}) {
                if (!received && terminal == HTTPEvent.State.COMPLETED) continue;
                CaptureController capture = new CaptureController(); capture.startDetailed();
                MemoryHTTPStore history = new MemoryHTTPStore();
                HTTPRecorder recorder = new HTTPRecorder(history, capture, new HTTPPrivacyPolicy(false, Collections.emptySet(), HTTPPrivacyPolicy.BodyPolicy.PUBLIC_UTF8, 16384));
                HTTPRecorder.Token token = recorder.begin("GET", "https://example.com", Collections.emptyList()).token; assertNotNull(token);
                check(history.snapshot().events.get(0));
                if (received) {
                    assertEquals(HTTPRecorder.Result.RECORDED, recorder.receiveResponse(token, 503, Collections.singletonList(new HTTPHeader("Content-Type", "text/plain")), false));
                    check(history.snapshot().events.get(0));
                }
                if (terminal == HTTPEvent.State.CAPTURE_ENDED) assertEquals(HTTPRecorder.Result.RECORDED, recorder.stopObserving(token));
                else assertEquals(HTTPRecorder.Result.RECORDED, recorder.finish(token, terminal, terminal == HTTPEvent.State.FAILED ? HTTPEvent.Failure.CONNECTION : null, true, true));
                HTTPEvent event = history.snapshot().events.get(0); check(event); assertEquals(terminal, event.state);
                assertEquals(received ? Integer.valueOf(503) : null, event.statusCode);
            }
        }
    }
    private static HTTPEvent.BodyReference bodyReference(HTTPEvent.BodyState state, String id, long bytes) {
        return HTTPEvent.BodyReference.restore(state, id, bytes);
    }
    static void check(HTTPEvent event) {
        HTTPEvent.BodyReference request = bodyReference(event.requestBody.state, event.requestBody.blobId, event.requestBody.observedBytes);
        HTTPEvent.BodyReference response = bodyReference(event.responseBody.state, event.responseBody.blobId, event.responseBody.observedBytes);
        HTTPEvent value = HTTPEvent.restore(event.id, event.sessionId, event.sequence, event.revision, event.timestampMillis,
            event.method, event.source, event.url, event.responseURL, event.requestHeaders, event.responseHeaders, event.headersLimited,
            event.statusCode, event.state, event.failure, event.durationMillis, request, response);
        assertEquals(event.id, value.id); assertEquals(event.sessionId, value.sessionId); assertEquals(event.sequence, value.sequence);
        assertEquals(event.revision, value.revision); assertEquals(event.timestampMillis, value.timestampMillis);
        assertEquals(event.requestHeaders, value.requestHeaders); assertEquals(event.responseHeaders, value.responseHeaders);
        assertEquals(event.requestBody.blobId, value.requestBody.blobId); assertEquals(event.responseBody.blobId, value.responseBody.blobId);
        assertEquals(event.textByteCount, value.textByteCount); assertEquals(event.durationMillis, value.durationMillis);
    }
    static void check(WebSocketEvent event) {
        WebSocketEvent.PayloadReference payload = event.payload == null ? null
            : WebSocketEvent.PayloadReference.restore(event.payload.state, event.payload.blobId, event.payload.byteCount);
        WebSocketEvent value = WebSocketEvent.restore(event.id, event.connectionId, event.sessionId, event.sequence, event.timestampMillis,
            event.url, event.source, event.kind, event.headers, event.headersLimited, event.statusCode, event.closeCode, event.failure,
            event.direction, event.outcome, event.relatedMessageId, payload);
        assertEquals(event.id, value.id); assertEquals(event.connectionId, value.connectionId); assertEquals(event.sequence, value.sequence);
        assertEquals(event.timestampMillis, value.timestampMillis); assertEquals(event.headers, value.headers);
        assertEquals(event.relatedMessageId, value.relatedMessageId); assertEquals(event.outcome, value.outcome);
        assertEquals(event.payload == null ? null : event.payload.byteCount, value.payload == null ? null : value.payload.byteCount);
        assertEquals(event.textByteCount, value.textByteCount);
    }
    private static LogEvent log(String field, String value) {
        assertTrue(Arrays.asList("sequence", "sessionID", "category", "messageBytes", "private", "truncated").contains(field));
        return LogEvent.restore(field.equals("sessionID") ? value : ID, field.equals("sequence") ? Long.parseLong(value) : 1,
            Long.MIN_VALUE, LogLevel.INFO, field.equals("category") ? (value.equals("~") ? "" : value) : "test",
            field.equals("messageBytes") ? "x".repeat(Integer.parseInt(value)) : "synthetic",
            field.equals("private") && value.equals("true"), field.equals("truncated") && value.equals("true"));
    }
    private static HTTPEvent http(String field, String value) {
        assertTrue(Arrays.asList("url", "authorization", "headerCount", "method", "status", "revision", "sequence", "responseURL").contains(field));
        List<HTTPHeader> headers = field.equals("authorization") ? Collections.singletonList(new HTTPHeader("authorization", value))
            : field.equals("headerCount") ? Collections.nCopies(Integer.parseInt(value), new HTTPHeader("x-public", "synthetic")) : Collections.emptyList();
        HTTPEvent.BodyReference pending = bodyReference(HTTPEvent.BodyState.PENDING, null, 0);
        return HTTPEvent.restore(ID, OTHER, field.equals("sequence") ? Long.parseLong(value) : 1,
            field.equals("revision") ? Long.parseLong(value) : 0, Long.MIN_VALUE, field.equals("method") ? value : "GET", HTTPEvent.Source.HOST,
            field.equals("url") ? value : "https://example.com/[redacted]", field.equals("responseURL") ? value : null, headers,
            Collections.emptyList(), false, field.equals("status") ? Integer.valueOf(value) : null, HTTPEvent.State.PENDING, null, null, pending, pending);
    }
    private static WebSocketEvent socket(String field, String value) {
        assertTrue(Arrays.asList("sequence", "connectionID", "url", "outcome", "kind", "status", "closeCode").contains(field));
        return WebSocketEvent.restore(ID, field.equals("connectionID") ? value : OTHER, OTHER, field.equals("sequence") ? Long.parseLong(value) : 1,
            Long.MIN_VALUE, field.equals("url") ? value : "wss://example.com/[redacted]", WebSocketEvent.Source.HOST,
            field.equals("kind") ? WebSocketEvent.Kind.valueOf(value.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT)) : WebSocketEvent.Kind.MESSAGE,
            Collections.emptyList(), false, field.equals("status") ? Integer.valueOf(value) : null, field.equals("closeCode") ? Integer.valueOf(value) : null,
            null, WebSocketEvent.Direction.OUTGOING, field.equals("outcome") ? WebSocketEvent.Outcome.valueOf(value.toUpperCase(Locale.ROOT)) : WebSocketEvent.Outcome.SUBMITTED,
            null, WebSocketEvent.PayloadReference.restore(WebSocketEvent.PayloadState.OMITTED, null, null));
    }
}
