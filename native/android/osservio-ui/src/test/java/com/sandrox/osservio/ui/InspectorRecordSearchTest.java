package com.sandrox.osservio.ui;

import com.sandrox.osservio.core.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public final class InspectorRecordSearchTest {
    private static String id(int value) { return String.format(Locale.ROOT, "00000000-0000-0000-0000-%012d", value); }
    @Test public void sharedFieldsPreserveEvidence() throws Exception {
        for (String line : Files.readAllLines(Paths.get(System.getProperty("osservio.fixtures"), "json-field-cases.tsv"), StandardCharsets.UTF_8)) {
            if (line.startsWith("#")) continue;
            String[] parts = line.split("\t");
            assertEquals(parts[0], parts[1], InspectorJsonField.matches(decode(parts[4]), decode(parts[2]), decode(parts[3]), () -> false).name().toLowerCase(Locale.ROOT));
        }
    }
    private static String decode(String value) { return value.equals("-") ? "" : new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8); }
    @Test public void unifiedTypeKeepsCommonConditionsAndClearsInapplicableOnes() {
        InspectorQuery query = new InspectorQuery(); query.text = "needle"; query.values.put(InspectorQuery.Field.HOST, Collections.singleton("example.test"));
        query.select(InspectorQuery.Field.STATUS, "5xx"); assertEquals(Collections.singleton("http"), query.values.get(InspectorQuery.Field.TRANSPORT));
        query.minimumDurationMillis = 10;
        query.select(InspectorQuery.Field.DIRECTION, "INCOMING");
        assertEquals(Collections.singleton("webSocket"), query.values.get(InspectorQuery.Field.TRANSPORT));
        assertNull(query.values.get(InspectorQuery.Field.STATUS)); assertEquals(0, query.minimumDurationMillis);
        assertEquals(Collections.singleton("example.test"), query.values.get(InspectorQuery.Field.HOST));
        query.select(InspectorQuery.Field.TRANSPORT, null); assertNull(query.values.get(InspectorQuery.Field.DIRECTION));
        query.resetFilters(); assertEquals("needle", query.text); assertEquals(0, query.activeCount());
    }
    @Test public void crossConnectionContentFieldsExclusionBudgetsAndCancellation() {
        History history = new History();
        List<InspectorRecordSearch.Record> records = Arrays.asList(
            new InspectorRecordSearch.Record(message(1, 10, "{\"kind\":\"changed\",\"id\":900719925474099312345}", history, 100)),
            new InspectorRecordSearch.Record(message(2, 20, "{\"action\":\"changed\",\"id\":2}", history, 100)));
        InspectorQuery query = new InspectorQuery(); query.text = "changed";
        assertEquals(2, search(records, query, history, InspectorRecordSearch.MAX_UNITS).keys.size()); assertEquals(2, history.reads);
        query.fieldPath = "/id"; query.fieldValue = "900719925474099312345";
        assertEquals(Collections.singleton(records.get(0).key()), search(records, query, history, InspectorRecordSearch.MAX_UNITS).keys);
        query.fieldPath = ""; query.excludedText = "action";
        assertEquals(Collections.singleton(records.get(0).key()), search(records, query, history, InspectorRecordSearch.MAX_UNITS).keys);
        assertTrue(search(records, query, history, 1).limited);
        int reads = history.reads;
        assertNull(InspectorRecordSearch.run(records, query, 1_000_000, null, history, InspectorRecordSearch.MAX_UNITS, () -> true));
        assertEquals(reads, history.reads);
    }
    @Test public void metadataDoesNotReadBodiesAndEvictionIsReported() {
        History history = new History();
        List<InspectorRecordSearch.Record> records = Collections.singletonList(new InspectorRecordSearch.Record(message(1, 10, "present", new History(), 100)));
        InspectorQuery query = new InspectorQuery(); query.text = "example.test";
        assertEquals(1, search(records, query, history, InspectorRecordSearch.MAX_UNITS).keys.size()); assertEquals(0, history.reads);
        query.text = "present";
        InspectorRecordSearch.Result result = search(records, query, history, InspectorRecordSearch.MAX_UNITS);
        assertTrue(result.keys.isEmpty()); assertEquals(1, result.unavailable);
    }
    @Test public void recentConnectionUsesLatestActivity() {
        History history = new History(); InspectorQuery query = new InspectorQuery(); query.recentMinutes = 5;
        List<WebSocketEvent> events = Arrays.asList(message(1, 10, "old", history, 1), message(2, 10, "recent", history, 1_000_000));
        assertTrue(query.matches(InspectorQuery.connections(events).get(0), 1_000_000));
    }
    @Test public void logFieldsDoNotTrustTruncatedOrPrivateContent() {
        InspectorQuery query = new InspectorQuery(); query.fieldPath = "/id"; query.fieldValue = "42";
        LogEvent full = LogEvent.restore(id(100), 1, 100, LogLevel.INFO, "transport", "{\"id\":42}", false, false);
        LogEvent partial = LogEvent.restore(id(100), 1, 100, LogLevel.INFO, "transport", "{\"id\":42}", false, true);
        assertEquals(1, logSearch(full, query).keys.size());
        assertTrue(logSearch(partial, query).keys.isEmpty()); assertEquals(1, logSearch(partial, query).truncated);
        assertEquals(1, logSearch(LogEvent.restore(id(100), 1, 100, LogLevel.INFO, "transport", "[private]", true, false), query).unavailable);
        query.fieldPath = ""; query.text = "42"; assertEquals(1, logSearch(partial, query).keys.size());
    }
    private static InspectorRecordSearch.Result logSearch(LogEvent event, InspectorQuery query) {
        return InspectorRecordSearch.run(Collections.singletonList(new InspectorRecordSearch.Record(event)), query, 100, null, null,
            InspectorRecordSearch.MAX_UNITS, () -> false);
    }
    @Test public void httpSearchesBothBodiesAndAppliesDurationBeforeReads() {
        Requests history = new Requests();
        history.bodies.put(id(1) + ".request", HTTPBody.restore(id(1) + ".request", "{\"value\":\"a\"}"));
        history.bodies.put(id(1) + ".response", HTTPBody.restore(id(1) + ".response", "{\"value\":\"b\"}"));
        HTTPEvent event = HTTPEvent.restore(id(1), id(100), 1, 2, 100, "POST", HTTPEvent.Source.HOST, "https://example.test/data", null,
            Collections.emptyList(), Collections.emptyList(), false, 200, HTTPEvent.State.COMPLETED, null, 20L,
            HTTPEvent.BodyReference.restore(HTTPEvent.BodyState.AVAILABLE, id(1) + ".request", 13),
            HTTPEvent.BodyReference.restore(HTTPEvent.BodyState.AVAILABLE, id(1) + ".response", 13));
        List<InspectorRecordSearch.Record> records = Collections.singletonList(new InspectorRecordSearch.Record(event));
        InspectorQuery query = new InspectorQuery(); query.fieldPath = "/value"; query.fieldValue = "b";
        assertEquals(1, InspectorRecordSearch.run(records, query, 100, history, null, InspectorRecordSearch.MAX_UNITS, () -> false).keys.size());
        assertEquals(2, history.reads); query.minimumDurationMillis = 21;
        assertTrue(InspectorRecordSearch.run(records, query, 100, history, null, InspectorRecordSearch.MAX_UNITS, () -> false).keys.isEmpty());
        assertEquals(2, history.reads);
    }
    private static final class Requests implements HTTPHistory {
        final Map<String, HTTPBody> bodies = new HashMap<>(); int reads;
        @Override public HTTPSnapshot snapshot() { throw new AssertionError("Search must use its input snapshot"); }
        @Override public BodyLookup body(String id) { reads++; HTTPBody body = bodies.get(id); return new BodyLookup(body == null ? BodyLookup.State.UNAVAILABLE : BodyLookup.State.AVAILABLE, body); }
        @Override public void clear() { throw new AssertionError("Search must not clear history"); }
    }
    private static InspectorRecordSearch.Result search(List<InspectorRecordSearch.Record> records, InspectorQuery query, History history, int limit) {
        return InspectorRecordSearch.run(records, query, 1_000_000, null, history, limit, () -> false);
    }
    private static WebSocketEvent message(int value, int connection, String text, History history, long time) {
        history.bodies.put(id(value), WebSocketPayload.restore(id(value), text));
        return WebSocketEvent.restore(id(value), id(connection), id(100), value, time, "wss://example.test/stream", WebSocketEvent.Source.HOST,
            WebSocketEvent.Kind.MESSAGE, Collections.emptyList(), false, null, null, null, WebSocketEvent.Direction.INCOMING, WebSocketEvent.Outcome.RECEIVED,
            null, WebSocketEvent.PayloadReference.restore(WebSocketEvent.PayloadState.AVAILABLE, id(value), (long) text.getBytes(StandardCharsets.UTF_8).length));
    }
    private static final class History implements WebSocketHistory {
        final Map<String, WebSocketPayload> bodies = new HashMap<>(); int reads;
        @Override public WebSocketSnapshot snapshot() { throw new AssertionError("Search must use its input snapshot"); }
        @Override public PayloadLookup payload(String id) {
            reads++; WebSocketPayload payload = bodies.get(id);
            return new PayloadLookup(payload == null ? PayloadLookup.State.UNAVAILABLE : PayloadLookup.State.AVAILABLE, payload);
        }
        @Override public void clear() { throw new AssertionError("Search must not clear history"); }
    }
}
