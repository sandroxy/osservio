package com.sandrox.osservio.store;

import com.sandrox.osservio.core.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public final class StructuredLogTest {
    private static final LogMessage MESSAGE = LogMessage.publicText("Request failed");
    private static LogDetails example() {
        return LogDetails.publicValues(new LogValue[] {LogValue.nullValue(), LogValue.bool(true), LogValue.integer(Long.MIN_VALUE),
            LogValue.integer(Long.MAX_VALUE), LogValue.number(1.25), LogValue.string("中文🙂\0\n\"\\"), LogValue.array(LogValue.bool(false)),
            LogValue.object(new LogField("attempt", LogValue.integer(1)), new LogField("attempt", LogValue.integer(2)))},
            new LogField[] {new LogField("requestId", LogValue.string("fixture-42"))},
            new LogError("TimeoutError", "Try again", "transport", "E_TIMEOUT", "Origin.run\nCaller.run"),
            new LogCallSite("Host/Client.swift", "send()", 42));
    }
    private static LogDetails values(LogValue... values) { return LogDetails.publicValues(values, new LogField[0], null, null); }
    private static LogEvent latest(MemoryLogStore store) { LogSnapshot snapshot = store.snapshot(); return snapshot.events.get(snapshot.events.size() - 1); }
    @Test public void sharedEvidenceAndFrozenSelectionSurviveClear() throws Exception {
        MemoryLogStore store = new MemoryLogStore(); LogRecorder recorder = new LogRecorder(store); recorder.start();
        assertEquals(LogRecorder.Result.RECORDED, recorder.record(LogLevel.ERROR, "host", MESSAGE, example()));
        LogEvent event = latest(store); LogDetailsContent payload = store.details(event.details.blobId).content;
        String expected = new String(Files.readAllBytes(Paths.get(System.getProperty("osservio.fixtures"), "structured-log.json")), StandardCharsets.UTF_8).trim();
        assertEquals(expected, payload.text); assertEquals("Request failed", event.message);
        assertEquals(18, store.snapshot().retainedTextBytes); assertEquals(payload.byteCount, store.snapshot().retainedDetailsBytes);
        IncidentLogs metadata = IncidentLogs.capture(store.snapshot(), 10);
        assertEquals(IncidentPayload.State.NOT_SELECTED, metadata.payloads.get(payload.id).state);
        assertEquals(IncidentPayload.State.BUDGET_EXCEEDED, metadata.includingDetails(store, 1).payloads.get(payload.id).state);
        IncidentLogs frozen = metadata.includingDetails(store, 65536); store.clear();
        assertEquals(expected, frozen.payloads.get(payload.id).text); assertEquals(10, frozen.observedAtMillis);
        assertEquals(IncidentPayload.State.UNAVAILABLE, metadata.includingDetails(store, 65536).payloads.get(payload.id).state);
        assertEquals(0, store.snapshot().retainedDetailsBytes);
    }
    @Test public void boundsDoNotEraseSummary() {
        MemoryLogStore store = new MemoryLogStore(); LogRecorder recorder = new LogRecorder(store, new CaptureController(), 4096, 200, 20, 256); recorder.start();
        LogValue deep = LogValue.nullValue(); for (int i = 0; i < 10; i++) deep = LogValue.array(deep);
        LogValue[] items = new LogValue[64]; Arrays.fill(items, LogValue.nullValue()); LogValue many = LogValue.array(items);
        for (LogDetails details : new LogDetails[] {values(LogValue.string(String.join("", Collections.nCopies(1000000, "x")))), values(deep), values(many, many, many, many, many)}) {
            assertEquals(LogRecorder.Result.RECORDED, recorder.record(LogLevel.INFO, "host", MESSAGE, details));
            assertEquals(LogDetailsReference.State.LIMITED, latest(store).details.state); assertEquals("Request failed", latest(store).message);
        }
        for (LogValue invalid : new LogValue[] {LogValue.number(Double.NaN), LogValue.string("\uD800")}) {
            assertEquals(LogRecorder.Result.RECORDED, recorder.record(LogLevel.INFO, "host", MESSAGE, values(invalid)));
            assertEquals(LogDetailsReference.State.INVALID, latest(store).details.state);
        }
        recorder.record(LogLevel.INFO, "host", MESSAGE, LogDetails.privateValues());
        assertEquals(LogDetailsReference.State.OMITTED, latest(store).details.state); assertEquals(0, store.snapshot().retainedDetailsBytes);
    }
    @Test public void payloadBudgetAndOrphanRemovalAreIndependent() {
        MemoryLogStore store = new MemoryLogStore(2, 524288, 0, 30000, LogClock.SYSTEM, 80); LogRecorder recorder = new LogRecorder(store); recorder.start();
        for (int i = 0; i < 2; i++) recorder.record(LogLevel.INFO, "host", MESSAGE, values(LogValue.string("0123456789")));
        LogSnapshot source = store.snapshot();
        assertEquals(LogDetailsHistory.Lookup.State.UNAVAILABLE, store.details(source.events.get(0).details.blobId).state);
        assertEquals(1, source.evictedDetails); String lastId = source.events.get(1).details.blobId;
        for (int i = 0; i < 2; i++) recorder.record(LogLevel.INFO, "host", MESSAGE);
        assertEquals(LogDetailsHistory.Lookup.State.UNAVAILABLE, store.details(lastId).state);
        assertEquals(0, store.snapshot().retainedDetailsBytes); assertEquals(2, store.snapshot().evictedDetails);
    }
    @Test public void unsupportedSinkAndAdmissionRemainExplicit() {
        LogEvent[] recorded = new LogEvent[1];
        LogRecorder recorder = new LogRecorder(event -> { recorded[0] = event; return LogSink.WriteResult.STORED; }, new CaptureController(), 4096, 1, 0);
        assertEquals(LogRecorder.Result.INACTIVE, recorder.record(LogLevel.INFO, "host", MESSAGE, example())); recorder.start();
        assertEquals(LogRecorder.Result.RECORDED, recorder.record(LogLevel.INFO, "host", MESSAGE, example()));
        assertEquals(LogDetailsReference.State.UNSUPPORTED, recorded[0].details.state);
        assertEquals(LogRecorder.Result.RATE_LIMITED, recorder.record(LogLevel.INFO, "host", MESSAGE, example()));
    }
    @Test public void invalidPayloadRelationsAreRejected() {
        MemoryLogStore store = new MemoryLogStore(); LogRecorder recorder = new LogRecorder(store); recorder.start();
        recorder.record(LogLevel.INFO, "host", MESSAGE, values()); LogEvent event = latest(store);
        assertThrows(IllegalArgumentException.class, () -> IncidentLogs.restore(Collections.singletonList(event), 0, 1, 0));
        IncidentPayload wrong = IncidentPayload.restore(event.details.blobId, IncidentPayload.State.AVAILABLE, "x");
        assertThrows(IllegalArgumentException.class, () -> IncidentLogs.restore(Collections.singletonList(event), 0, 1, 0, Collections.singletonMap(wrong.id, wrong), 0));
        assertThrows(IllegalArgumentException.class, () -> store.append(event, LogDetailsContent.restore(wrong.id, "x")));
    }
}
