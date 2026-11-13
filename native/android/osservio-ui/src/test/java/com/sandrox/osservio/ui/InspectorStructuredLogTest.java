package com.sandrox.osservio.ui;

import com.sandrox.osservio.core.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class InspectorStructuredLogTest {
    private static final String ID = "11111111-1111-1111-1111-111111111111";
    private static final String TEXT = "{\"arguments\":[9223372036854775807],\"attributes\":{\"requestId\":\"lookup-42\"}}";
    @Test public void liveAndSavedSearchKeepPrecisionAndDoNotAssumeMissingContentIsExcluded() {
        LogEvent event = LogEvent.restore(ID, 1, 1, LogLevel.ERROR, "fixture", "Request failed", false, false,
            LogDetailsReference.restore(LogDetailsReference.State.AVAILABLE, ID, TEXT.getBytes(StandardCharsets.UTF_8).length));
        History history = new History(); List<InspectorRecordSearch.Record> records = Collections.singletonList(new InspectorRecordSearch.Record(event));
        InspectorQuery query = new InspectorQuery(); query.text = "fixture";
        assertEquals(1, InspectorRecordSearch.run(records, query, 1, null, null, null, history, InspectorRecordSearch.MAX_UNITS, () -> false).keys.size());
        assertEquals(0, history.reads); query.text = "lookup-42";
        InspectorRecordSearch.Result result = InspectorRecordSearch.run(records, query, 1, null, null, null, history, InspectorRecordSearch.MAX_UNITS, () -> false);
        assertEquals(1, result.keys.size()); assertEquals("logDetails", result.previewSources.get(records.get(0).key()));
        query.text = ""; query.fieldPath = "/arguments/0"; query.fieldValue = "9223372036854775807";
        assertEquals(1, InspectorRecordSearch.run(records, query, 1, null, null, null, history, InspectorRecordSearch.MAX_UNITS, () -> false).keys.size());
        IncidentPayload saved = IncidentPayload.restore(ID, IncidentPayload.State.AVAILABLE, TEXT); history.clear();
        assertEquals(1, InspectorRecordSearch.run(records, query, 1, null, null, Collections.singletonMap(ID, saved), InspectorRecordSearch.MAX_UNITS, () -> false).keys.size());
        query.fieldPath = ""; query.text = "fixture"; query.excludedText = "lookup-42";
        result = InspectorRecordSearch.run(records, query, 1, null, null, null, history, InspectorRecordSearch.MAX_UNITS, () -> false);
        assertTrue(result.keys.isEmpty()); assertTrue(result.unavailable > 0);
    }
    private static final class History implements LogDetailsHistory {
        LogDetailsContent value = LogDetailsContent.restore(ID, TEXT); int reads;
        @Override public Lookup details(String id) { reads++; return value == null ? Lookup.unavailable() : Lookup.available(value); }
        @Override public LogSnapshot snapshot() { return null; }
        @Override public void clear() { value = null; }
    }
}
