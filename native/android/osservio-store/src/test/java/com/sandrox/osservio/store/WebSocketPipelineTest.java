package com.sandrox.osservio.store;

import com.sandrox.osservio.core.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class WebSocketPipelineTest {
    @Test public void sharedLifecycleAndConnectionLimit() throws Exception {
        MemoryWebSocketStore store = new MemoryWebSocketStore();
        CaptureController capture = new CaptureController(); capture.startDetailed();
        WebSocketRecorder recorder = new WebSocketRecorder(store, capture, new WebSocketPrivacyPolicy(), 1, 100);
        WebSocketRecorder.Token token = null;
        for (String line : Files.readAllLines(Paths.get(System.getProperty("osservio.fixtures"), "websocket-lifecycle.tsv"), StandardCharsets.UTF_8)) {
            String[] row = line.split("\t");
            WebSocketRecorder.Result result;
            if (row[1].equals("connecting")) {
                WebSocketRecorder.BeginResult begin = recorder.begin("wss://example.com", EMPTY, WebSocketEvent.Source.HOST);
                token = begin.token; result = begin.result;
                assertEquals(WebSocketRecorder.Result.CONNECTION_LIMIT, recorder.begin("wss://example.com/other", EMPTY, WebSocketEvent.Source.HOST).result);
            } else result = lifecycle(recorder, token, WebSocketEvent.Kind.valueOf(row[1].toUpperCase(java.util.Locale.ROOT)));
            assertEquals(row[0], row[2], result.name().toLowerCase(java.util.Locale.ROOT));
            assertEquals(row[0], Integer.parseInt(row[3]), store.snapshot().events.size());
            assertEquals(row[0], Integer.parseInt(row[4]), recorder.status().activeConnections);
        }
    }
    private static final List<HTTPHeader> EMPTY = Collections.emptyList();
    private static WebSocketRecorder recorder(MemoryWebSocketStore store, boolean publicText, int limit, int rate) {
        CaptureController capture = new CaptureController(); capture.startDetailed();
        return new WebSocketRecorder(store, capture, new WebSocketPrivacyPolicy(false, Collections.emptySet(), publicText, limit), 16, rate);
    }
    private static WebSocketRecorder.Token begin(WebSocketRecorder recorder) {
        WebSocketRecorder.Token token = recorder.begin("wss://user:secret@example.com/private?token=secret",
            Collections.singletonList(new HTTPHeader("Authorization", "Bearer secret")), WebSocketEvent.Source.HOST).token;
        assertNotNull(token); return token;
    }
    private static WebSocketRecorder.Result lifecycle(WebSocketRecorder recorder, WebSocketRecorder.Token token, WebSocketEvent.Kind kind) {
        return recorder.lifecycle(token, kind, EMPTY, null, null, null);
    }
    @Test public void defaultPrivacyAndBinaryCount() {
        MemoryWebSocketStore store = new MemoryWebSocketStore();
        WebSocketRecorder recorder = recorder(store, false, 4096, 100);
        WebSocketRecorder.Token token = begin(recorder);
        assertEquals(WebSocketRecorder.Result.RECORDED, recorder.text(token, WebSocketEvent.Direction.OUTGOING, WebSocketEvent.Outcome.QUEUED, "secret"));
        assertEquals(WebSocketRecorder.Result.RECORDED, recorder.binary(token, WebSocketEvent.Direction.INCOMING, WebSocketEvent.Outcome.RECEIVED, 1234));
        List<WebSocketEvent> events = store.snapshot().events;
        assertEquals("[redacted]", events.get(0).headers.get(0).value);
        assertEquals("wss://example.com/[redacted]?[redacted]", events.get(0).url);
        assertEquals(WebSocketEvent.PayloadState.OMITTED, events.get(1).payload.state);
        assertNull(events.get(1).payload.byteCount); assertNull(events.get(1).payload.blobId);
        assertEquals(WebSocketEvent.PayloadState.BINARY, events.get(2).payload.state);
        assertEquals(Long.valueOf(1234), events.get(2).payload.byteCount);
        assertEquals(0, store.snapshot().retainedPayloadBytes);
    }
    private static String decode(String value) {
        return value.equals("~") ? "" : new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
    @Test public void sharedTextBoundaries() throws Exception {
        for (String line : Files.readAllLines(Paths.get(System.getProperty("osservio.fixtures"), "websocket-text-cases.tsv"), StandardCharsets.UTF_8)) {
            String[] row = line.split("\t"); assertEquals(5, row.length);
            MemoryWebSocketStore store = new MemoryWebSocketStore();
            WebSocketRecorder recorder = recorder(store, true, Integer.parseInt(row[1]), 100);
            WebSocketRecorder.Token token = begin(recorder);
            assertEquals(WebSocketRecorder.Result.RECORDED, recorder.text(token, WebSocketEvent.Direction.INCOMING, WebSocketEvent.Outcome.RECEIVED, decode(row[2])));
            WebSocketEvent.PayloadReference ref = store.snapshot().events.get(1).payload;
            boolean truncated = Boolean.parseBoolean(row[4]);
            assertEquals(row[0], truncated ? WebSocketEvent.PayloadState.TRUNCATED : WebSocketEvent.PayloadState.AVAILABLE, ref.state);
            WebSocketPayload payload = store.payload(ref.blobId).payload;
            assertEquals(row[0], decode(row[3]), payload.text);
            assertEquals(truncated ? null : Long.valueOf(payload.byteCount), ref.byteCount);
        }
        MemoryWebSocketStore store = new MemoryWebSocketStore();
        WebSocketRecorder recorder = recorder(store, true, 10, 100);
        recorder.text(begin(recorder), WebSocketEvent.Direction.INCOMING, WebSocketEvent.Outcome.RECEIVED, "\uD800");
        assertEquals(WebSocketEvent.PayloadState.INVALID_TEXT, store.snapshot().events.get(1).payload.state);
    }
    @Test public void lifecycleValidationAndSessionBoundaries() {
        MemoryWebSocketStore store = new MemoryWebSocketStore();
        WebSocketRecorder recorder = recorder(store, false, 4096, 100);
        WebSocketRecorder.Token token = begin(recorder);
        assertEquals(WebSocketRecorder.Result.RECORDED, recorder.lifecycle(token, WebSocketEvent.Kind.OPENED, EMPTY, 101, null, null));
        assertEquals(WebSocketRecorder.Result.INVALID, lifecycle(recorder, token, WebSocketEvent.Kind.OPENED));
        assertEquals(WebSocketRecorder.Result.INVALID, recorder.text(token, WebSocketEvent.Direction.INCOMING, WebSocketEvent.Outcome.QUEUED, "x"));
        assertEquals(WebSocketRecorder.Result.INVALID, recorder.lifecycle(token, WebSocketEvent.Kind.CLOSED, EMPTY, null, 1, null));
        assertEquals(WebSocketRecorder.Result.INVALID, lifecycle(recorder, token, WebSocketEvent.Kind.FAILED));
        assertTrue(recorder.captureController.stopDetailed()); assertEquals(0, recorder.status().activeConnections);
        assertEquals(2, store.snapshot().events.size());
        recorder.captureController.startDetailed();
        assertEquals(WebSocketRecorder.Result.STALE, recorder.text(token, WebSocketEvent.Direction.INCOMING, WebSocketEvent.Outcome.RECEIVED, "late"));
        WebSocketRecorder.Token resumed = recorder.begin("wss://example.com", EMPTY, WebSocketEvent.Source.HOST, true, java.util.UUID.randomUUID()).token;
        assertEquals(WebSocketEvent.Kind.ATTACHED, store.snapshot().events.get(2).kind);
        assertEquals(WebSocketRecorder.Result.RECORDED, recorder.lifecycle(resumed, WebSocketEvent.Kind.CLOSED, EMPTY, null, 1000, null));
        assertEquals(0, recorder.status().activeConnections);
    }
    @Test public void rateLimitedTerminalReleasesConnection() {
        MemoryWebSocketStore store = new MemoryWebSocketStore();
        WebSocketRecorder recorder = recorder(store, false, 4096, 2);
        WebSocketRecorder.Token token = begin(recorder);
        recorder.text(token, WebSocketEvent.Direction.OUTGOING, WebSocketEvent.Outcome.QUEUED, "x");
        assertEquals(WebSocketRecorder.Result.RATE_LIMITED, lifecycle(recorder, token, WebSocketEvent.Kind.CLOSED));
        assertEquals(0, recorder.status().activeConnections); assertEquals(1, recorder.status().rateLimitedCount);
        assertEquals(WebSocketRecorder.Result.STALE, lifecycle(recorder, token, WebSocketEvent.Kind.CLOSED));
    }
    @Test public void rejectedHandshakeRetainsOnlySanitizedResponseMetadata() {
        MemoryWebSocketStore store = new MemoryWebSocketStore();
        WebSocketRecorder recorder = recorder(store, false, 4096, 100);
        assertEquals(WebSocketRecorder.Result.RECORDED, recorder.lifecycle(begin(recorder), WebSocketEvent.Kind.FAILED,
            Collections.singletonList(new HTTPHeader("Set-Cookie", "secret")), 403, null, WebSocketEvent.Failure.TRANSPORT));
        WebSocketEvent event = store.snapshot().events.get(1);
        assertEquals(Integer.valueOf(403), event.statusCode);
        assertEquals("[redacted]", event.headers.get(0).value);
        assertEquals(0, recorder.status().activeConnections);
    }
    @Test public void independentPayloadEvictionClearAndImmutableSnapshots() {
        MemoryWebSocketStore store = new MemoryWebSocketStore(3, 1000, 4);
        WebSocketRecorder recorder = recorder(store, true, 4096, 100);
        WebSocketRecorder.Token token = begin(recorder);
        recorder.text(token, WebSocketEvent.Direction.INCOMING, WebSocketEvent.Outcome.RECEIVED, "abcd");
        WebSocketSnapshot before = store.snapshot(); String first = before.events.get(1).payload.blobId;
        recorder.text(token, WebSocketEvent.Direction.INCOMING, WebSocketEvent.Outcome.RECEIVED, "efgh");
        assertEquals(3, store.snapshot().events.size());
        assertEquals(WebSocketHistory.PayloadLookup.State.UNAVAILABLE, store.payload(first).state);
        assertEquals(2, before.events.size());
        store.clear(); assertEquals(1, recorder.status().activeConnections);
        recorder.text(token, WebSocketEvent.Direction.INCOMING, WebSocketEvent.Outcome.RECEIVED, "new");
        assertEquals(1, store.snapshot().events.size()); assertEquals(WebSocketEvent.Kind.MESSAGE, store.snapshot().events.get(0).kind);
        assertEquals(2, before.events.size());
    }
    @Test public void contentionAndSinkFailureReleaseTerminalSlot() {
        CaptureController capture = new CaptureController(); capture.startDetailed();
        WebSocketRecorder recorder = new WebSocketRecorder((event, payload) -> {
            assertNull(capture.status());
            return event.kind == WebSocketEvent.Kind.CONNECTING ? WebSocketSink.WriteResult.STORED : WebSocketSink.WriteResult.BUSY;
        }, capture, new WebSocketPrivacyPolicy());
        assertEquals(WebSocketRecorder.Result.BUSY, recorder.lifecycle(begin(recorder), WebSocketEvent.Kind.FAILED, EMPTY, null, null, WebSocketEvent.Failure.TRANSPORT));
        assertEquals(0, recorder.status().activeConnections);
        WebSocketRecorder tiny = recorder(new MemoryWebSocketStore(1, 1, 0), false, 4096, 100);
        assertEquals(WebSocketRecorder.Result.TOO_LARGE, tiny.begin("wss://example.com", EMPTY, WebSocketEvent.Source.HOST).result);
        assertEquals(0, tiny.status().activeConnections);
    }
}
