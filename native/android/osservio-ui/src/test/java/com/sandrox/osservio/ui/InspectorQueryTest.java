package com.sandrox.osservio.ui;

import com.sandrox.osservio.core.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class InspectorQueryTest {
    private static final String ID = "00000000-0000-0000-0000-000000000001";

    @Test public void fieldUnionAndIntersectionWithText() {
        InspectorQuery query = new InspectorQuery();
        query.values.put(InspectorQuery.Field.LEVEL, new HashSet<>(Arrays.asList("WARNING", "ERROR")));
        query.values.put(InspectorQuery.Field.CATEGORY, Collections.singleton("checkout")); query.text = "TIMEOUT";
        assertTrue(query.matches(log(LogLevel.ERROR, "checkout", "request timeout"), 1));
        assertTrue(query.matches(log(LogLevel.WARNING, "checkout", "request timeout"), 1));
        assertFalse(query.matches(log(LogLevel.INFO, "checkout", "request timeout"), 1));
        assertFalse(query.matches(log(LogLevel.ERROR, "profile", "request timeout"), 1));
        assertFalse(query.matches(log(LogLevel.ERROR, "checkout", "completed"), 1));
        query.values.put(InspectorQuery.Field.LEVEL, Collections.emptySet());
        assertTrue(query.matches(log(LogLevel.INFO, "checkout", "request timeout"), 1));
    }
    @Test public void statusCodeDoesNotBecomeTransportFailure() {
        HTTPEvent.BodyReference body = HTTPEvent.BodyReference.restore(HTTPEvent.BodyState.OMITTED, null, 0);
        HTTPEvent event = HTTPEvent.restore(ID, ID, 1, 2, 1, "POST", HTTPEvent.Source.HOST, "https://example.test/pay", null,
            Collections.emptyList(), Collections.emptyList(), false, 503, HTTPEvent.State.COMPLETED, null, 12L, body, body);
        InspectorQuery query = new InspectorQuery();
        query.values.put(InspectorQuery.Field.STATUS, Collections.singleton("5xx"));
        query.values.put(InspectorQuery.Field.STATE, Collections.singleton("COMPLETED"));
        assertTrue(query.matches(event, 1));
        query.values.put(InspectorQuery.Field.STATE, Collections.singleton("FAILED"));
        assertFalse(query.matches(event, 1));
    }
    @Test public void exactStatusAndFamiliesShareOneUnion() {
        HTTPEvent.BodyReference body = HTTPEvent.BodyReference.restore(HTTPEvent.BodyState.OMITTED, null, 0);
        HTTPEvent event = HTTPEvent.restore(ID, ID, 1, 2, 1, "POST", HTTPEvent.Source.HOST, "https://example.test/401", null,
            Collections.emptyList(), Collections.emptyList(), false, 503, HTTPEvent.State.COMPLETED, null, 12L, body, body);
        InspectorQuery query = new InspectorQuery(); query.select(InspectorQuery.Field.STATUS, "401");
        assertFalse(query.matchesFacets(event, 1));
        query.select(InspectorQuery.Field.STATUS, "503"); assertTrue(query.matchesFacets(event, 1));
        query.select(InspectorQuery.Field.STATUS, "503"); query.select(InspectorQuery.Field.STATUS, "5xx");
        assertTrue(query.matchesFacets(event, 1));
        query.select(InspectorQuery.Field.TRANSPORT, "webSocket"); assertFalse(query.values.containsKey(InspectorQuery.Field.STATUS));
    }
    @Test public void socketActivityAndSearchScopesKeepApplicableConditions() {
        InspectorQuery query = new InspectorQuery(); query.select(InspectorQuery.Field.TRANSPORT, "webSocket");
        query.select(InspectorQuery.Field.ACTIVITY, "messages");
        assertTrue(query.matchesFacets(socket(1, WebSocketEvent.Kind.MESSAGE), 10));
        assertFalse(query.matchesFacets(socket(2, WebSocketEvent.Kind.OPENED), 10));
        query.select(InspectorQuery.Field.KIND, "MESSAGE"); query.select(InspectorQuery.Field.DIRECTION, "INCOMING"); query.select(InspectorQuery.Field.ACTIVITY, "connections");
        assertTrue(query.values.get(InspectorQuery.Field.KIND).isEmpty());
        assertFalse(query.values.containsKey(InspectorQuery.Field.DIRECTION));
        assertTrue(query.matchesFacets(socket(2, WebSocketEvent.Kind.OPENED), 10));
        query.select(InspectorQuery.Field.SEARCH_SCOPE, "headers"); query.select(InspectorQuery.Field.TRANSPORT, "http");
        assertEquals("headers", query.searchScope()); assertFalse(query.values.containsKey(InspectorQuery.Field.ACTIVITY));
        query.select(InspectorQuery.Field.SEARCH_SCOPE, "response"); query.select(InspectorQuery.Field.TRANSPORT, "webSocket");
        assertEquals("all", query.searchScope());
    }
    @Test public void groupingKeepsLifecycleAndCompleteRetainedCount() {
        WebSocketEvent open = socket(1, WebSocketEvent.Kind.OPENED), message = socket(2, WebSocketEvent.Kind.MESSAGE), close = socket(3, WebSocketEvent.Kind.CLOSED);
        List<InspectorQuery.Connection> groups = InspectorQuery.connections(Arrays.asList(close, message, open));
        assertEquals(1, groups.size()); assertEquals(3, groups.get(0).count); assertEquals("CLOSED", groups.get(0).state());
        assertEquals(1, groups.get(0).first.sequence);
        assertEquals("unknown", InspectorQuery.connections(Collections.singletonList(message)).get(0).state());
        InspectorQuery query = new InspectorQuery(); query.values.put(InspectorQuery.Field.STATE, Collections.singleton("FAILED"));
        assertFalse(query.matches(groups.get(0), 3));
    }
    @Test public void timeBoundsAndEncodedPathStayExact() {
        InspectorQuery query = new InspectorQuery(); query.recentMinutes = 5;
        assertTrue(query.includesTime(0, 300000)); assertFalse(query.includesTime(-1, 300000));
        assertFalse(query.includesTime(300001, 300000)); assertTrue(query.includesTime(Long.MIN_VALUE, Long.MIN_VALUE));
        assertEquals("api.example.test", InspectorQuery.host("https://API.example.test/a%2Fb?q=a%26b"));
        assertEquals("/a%2Fb?q=a%26b", InspectorQuery.path("https://API.example.test/a%2Fb?q=a%26b"));
        InspectorQuery copy = query.copy(); copy.recentMinutes = 30; assertEquals(5, query.recentMinutes);
    }
    private static LogEvent log(LogLevel level, String category, String message) {
        return LogEvent.restore(ID, 1, 1, level, category, message, false, false);
    }
    @Test public void sameMillisecondTimelineKeepsCloseAfterMessage() {
        List<WebSocketEvent> events = Arrays.asList(socket(2, WebSocketEvent.Kind.MESSAGE, 123),
            socket(1, WebSocketEvent.Kind.OPENED, 123), socket(3, WebSocketEvent.Kind.CLOSED, 123));
        Collections.sort(events, InspectorQuery::compareTimeline);
        assertEquals(3, events.get(0).sequence); assertEquals(2, events.get(1).sequence); assertEquals(1, events.get(2).sequence);
    }
    private static WebSocketEvent socket(long sequence, WebSocketEvent.Kind kind) {
        return socket(sequence, kind, sequence);
    }
    private static WebSocketEvent socket(long sequence, WebSocketEvent.Kind kind, long timestamp) {
        boolean message = kind == WebSocketEvent.Kind.MESSAGE;
        return WebSocketEvent.restore(String.format(java.util.Locale.ROOT, "00000000-0000-0000-0000-%012d", sequence), ID, ID,
            sequence, timestamp, "wss://example.test/ws", WebSocketEvent.Source.HOST, kind, Collections.emptyList(), false, null, null, null,
            message ? WebSocketEvent.Direction.INCOMING : null, message ? WebSocketEvent.Outcome.RECEIVED : null, null,
            message ? WebSocketEvent.PayloadReference.restore(WebSocketEvent.PayloadState.OMITTED, null, null) : null);
    }
}
