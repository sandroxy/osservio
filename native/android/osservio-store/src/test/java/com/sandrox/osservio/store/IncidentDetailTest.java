package com.sandrox.osservio.store;

import com.sandrox.osservio.core.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;
import static org.junit.Assert.*;

public final class IncidentDetailTest {
    private static final String ID = "12345678-1234-1234-1234-123456789abc";
    @Test public void sharedNewestContiguousWindowAndLossAccounting() throws Exception {
        for (String line : Files.readAllLines(Paths.get(System.getProperty("osservio.fixtures"), "incident-window.tsv"), StandardCharsets.UTF_8)) {
            String[] row = line.split("\t"); assertEquals(7, row.length);
            List<LogEvent> events = new ArrayList<>(); int bytes = 0;
            if (!row[1].equals("-")) for (String size : row[1].split(",")) {
                LogEvent event = LogEvent.restore(ID, events.size() + 1L, 10, LogLevel.INFO, "a", "x".repeat(Integer.parseInt(size)), false, false);
                events.add(event); bytes += event.textByteCount;
            }
            LogSnapshot source = new LogSnapshot(events, bytes, Long.parseLong(row[4]), 0);
            try {
                IncidentLogs result = IncidentLogs.capture(source, -9, Integer.parseInt(row[2]), Integer.parseInt(row[3]));
                assertEquals(row[0], "true", row[6]); assertEquals(-9, result.observedAtMillis);
                List<Long> expected = new ArrayList<>(), actual = new ArrayList<>();
                if (!row[5].equals("-")) for (String value : row[5].split(",")) expected.add(Long.valueOf(value));
                for (LogEvent event : result.events) actual.add(event.sequence);
                assertEquals(row[0], expected, actual); assertEquals(source.evictedCount, result.sourceEvictedCount);
                assertEquals(events.size(), result.sourceEventCount); assertEquals(events.size() - expected.size(), result.omittedEventCount);
            } catch (IllegalArgumentException failure) { assertEquals(row[0], "false", row[6]); }
        }
    }
    @Test public void duplicateEvidenceAndDetachedPayloadsAreRejected() {
        LogEvent event = LogEvent.restore(ID, 1, 0, LogLevel.INFO, "a", "x", false, false);
        LogEvent same = LogEvent.restore(ID.toUpperCase(Locale.ROOT), 1, 1, LogLevel.ERROR, "a", "other", false, false);
        assertThrows(IllegalArgumentException.class, () -> IncidentLogs.restore(Arrays.asList(event, same), 0, 2, 0));
        IncidentPayload extra = IncidentPayload.restore(ID, IncidentPayload.State.UNAVAILABLE, null);
        assertThrows(IllegalArgumentException.class, () -> IncidentWebSockets.restore(Collections.emptyList(), Collections.singletonMap(ID, extra), 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> IncidentPayload.restore(ID, IncidentPayload.State.AVAILABLE, null));
        assertThrows(IllegalArgumentException.class, () -> IncidentPayload.restore(ID, IncidentPayload.State.UNAVAILABLE, ""));
    }
    @Test public void httpSelectionDoesNotLookupUnselectedBodiesAndSurvivesLiveClear() {
        MemoryHTTPStore history = new MemoryHTTPStore(); CaptureController capture = new CaptureController(); capture.startDetailed();
        HTTPRecorder recorder = new HTTPRecorder(history, capture, new HTTPPrivacyPolicy(false, Collections.emptySet(), HTTPPrivacyPolicy.BodyPolicy.PUBLIC_UTF8, 16384));
        HTTPRecorder.Token first = response(recorder, "abcd"), second = response(recorder, "xy");
        HTTPSnapshot source = history.snapshot();
        IncidentHTTP metadata = IncidentHTTP.capture(source, 5, new LookupHTTP(true), false);
        for (IncidentPayload payload : metadata.payloads.values()) { assertEquals(IncidentPayload.State.NOT_SELECTED, payload.state); assertNull(payload.text); }
        IncidentHTTP limited = IncidentHTTP.capture(source, 6, history, true, 50, 65536, 3);
        assertEquals("xy", limited.payloads.get(second.id + ".response").text);
        assertEquals(IncidentPayload.State.BUDGET_EXCEEDED, limited.payloads.get(first.id + ".response").state);
        assertEquals(2, limited.retainedPayloadBytes);
        history.clear();
        assertEquals(2, limited.events.size()); assertEquals("xy", limited.payloads.get(second.id + ".response").text);
        IncidentHTTP missing = IncidentHTTP.capture(source, 7, history, true);
        for (IncidentPayload payload : missing.payloads.values()) { assertEquals(IncidentPayload.State.UNAVAILABLE, payload.state); assertNull(payload.text); }
        for (HTTPEvent event : missing.events) assertEquals(HTTPEvent.BodyState.AVAILABLE, event.responseBody.state);
        IncidentHTTP busy = IncidentHTTP.capture(source, 8, new LookupHTTP(false), true);
        for (IncidentPayload payload : busy.payloads.values()) assertEquals(IncidentPayload.State.BUSY, payload.state);
        Map<String, IncidentPayload> invalid = new LinkedHashMap<>(limited.payloads);
        invalid.put(second.id + ".response", IncidentPayload.restore(second.id + ".response", IncidentPayload.State.AVAILABLE, "wrong-length"));
        assertThrows(IllegalArgumentException.class, () -> IncidentHTTP.restore(limited.events, invalid, 0, 2, 0, 0));
        assertThrows(UnsupportedOperationException.class, limited.payloads::clear);
    }
    @Test public void emptyBodiesAndWebSocketSubmissionsKeepDistinctMeaning() {
        MemoryWebSocketStore history = new MemoryWebSocketStore(); CaptureController capture = new CaptureController(); capture.startDetailed();
        WebSocketRecorder recorder = new WebSocketRecorder(history, capture, new WebSocketPrivacyPolicy(false, Collections.emptySet(), true, 4096));
        WebSocketRecorder.Token token = recorder.begin("wss://example.com", Collections.emptyList(), WebSocketEvent.Source.HOST).token; assertNotNull(token);
        UUID first = UUID.randomUUID(), empty = UUID.randomUUID();
        assertEquals(WebSocketRecorder.Result.RECORDED, recorder.text(token, WebSocketEvent.Direction.OUTGOING, WebSocketEvent.Outcome.SUBMITTED, "😀", first));
        assertEquals(WebSocketRecorder.Result.RECORDED, recorder.text(token, WebSocketEvent.Direction.INCOMING, WebSocketEvent.Outcome.RECEIVED, "", empty));
        WebSocketSnapshot source = history.snapshot();
        IncidentWebSockets metadata = IncidentWebSockets.capture(source, 1, new LookupWebSocket(true), false);
        for (IncidentPayload payload : metadata.payloads.values()) assertEquals(IncidentPayload.State.NOT_SELECTED, payload.state);
        IncidentWebSockets bounded = IncidentWebSockets.capture(source, 2, history, true, 200, 65536, 0);
        assertEquals(IncidentPayload.State.AVAILABLE, bounded.payloads.get(empty.toString()).state); assertEquals("", bounded.payloads.get(empty.toString()).text);
        assertEquals(IncidentPayload.State.BUDGET_EXCEEDED, bounded.payloads.get(first.toString()).state);
        assertEquals(WebSocketRecorder.Result.RECORDED, recorder.sendResult(token, first, WebSocketEvent.Outcome.SENT, null));
        IncidentWebSockets resultOnly = IncidentWebSockets.capture(history.snapshot(), 3, history, false, 1, 65536, 65536);
        assertEquals(first.toString(), resultOnly.events.get(0).relatedMessageId); assertTrue(resultOnly.payloads.isEmpty());
        assertEquals(3, resultOnly.omittedEventCount);
        history.clear();
        assertEquals(WebSocketEvent.Outcome.SUBMITTED, bounded.events.get(1).outcome);
        IncidentWebSockets unavailable = IncidentWebSockets.capture(source, 4, history, true);
        for (IncidentPayload payload : unavailable.payloads.values()) assertEquals(IncidentPayload.State.UNAVAILABLE, payload.state);
        IncidentWebSockets busy = IncidentWebSockets.capture(source, 5, new LookupWebSocket(false), true);
        for (IncidentPayload payload : busy.payloads.values()) assertEquals(IncidentPayload.State.BUSY, payload.state);
    }
    private static HTTPRecorder.Token response(HTTPRecorder recorder, String text) {
        HTTPRecorder.Token token = recorder.begin("GET", "https://example.com", Collections.emptyList()).token; assertNotNull(token);
        assertEquals(HTTPRecorder.Result.RECORDED, recorder.receiveResponse(token, 200, Collections.singletonList(new HTTPHeader("Content-Type", "text/plain")), false));
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        assertEquals(HTTPRecorder.Result.RECORDED, recorder.observeBody(token, HTTPRecorder.Direction.RESPONSE, data.length, count -> Arrays.copyOf(data, count)));
        assertEquals(HTTPRecorder.Result.RECORDED, recorder.finish(token, HTTPEvent.State.COMPLETED, null, true, true)); return token;
    }
    private static final class LookupHTTP implements HTTPHistory {
        private final boolean failOnRead;
        LookupHTTP(boolean failOnRead) { this.failOnRead = failOnRead; }
        @Override public HTTPSnapshot snapshot() { return null; }
        @Override public BodyLookup body(String id) {
            if (failOnRead) fail("Unselected content was queried");
            return new BodyLookup(BodyLookup.State.BUSY, null);
        }
        @Override public void clear() { fail("Capture must not clear history"); }
    }
    private static final class LookupWebSocket implements WebSocketHistory {
        private final boolean failOnRead;
        LookupWebSocket(boolean failOnRead) { this.failOnRead = failOnRead; }
        @Override public WebSocketSnapshot snapshot() { return null; }
        @Override public PayloadLookup payload(String id) {
            if (failOnRead) fail("Unselected content was queried");
            return new PayloadLookup(PayloadLookup.State.BUSY, null);
        }
        @Override public void clear() { fail("Capture must not clear history"); }
    }
}
