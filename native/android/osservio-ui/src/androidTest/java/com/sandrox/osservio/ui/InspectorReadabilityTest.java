package com.sandrox.osservio.ui;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.sandrox.osservio.core.*;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class InspectorReadabilityTest {
    private static final String ID = "12345678-1234-1234-1234-123456789abc";
    @Test public void readableAddressKeepsExactSourceAndSingleStatus() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InspectorTheme.context(InstrumentationRegistry.getInstrumentation().getTargetContext());
            String url = "https://example.test:8443/%E6%B6%88%E6%81%AF?[redacted]";
            HTTPEvent event = HTTPEvent.restore(ID, ID, 1, 2, 123, "POST", HTTPEvent.Source.HOST, url, null,
                Collections.emptyList(), Collections.emptyList(), false, 503, HTTPEvent.State.COMPLETED, null, 42L,
                HTTPEvent.BodyReference.restore(HTTPEvent.BodyState.OMITTED, null, 0), HTTPEvent.BodyReference.restore(HTTPEvent.BodyState.OMITTED, null, 0));
            InspectorRecordPresentation.Row row = InspectorRecordPresentation.http(context, event, null);
            assertEquals("/消息", row.title); assertEquals("POST · 503", row.badge); assertEquals(2, row.tone);
            assertTrue(row.subtitle.contains("example.test:8443")); assertTrue(row.subtitle.contains("42 ms")); assertFalse(row.subtitle.contains("503"));
            assertEquals(url, event.url); assertTrue(HTTPPresentation.overview(context, event).contains(url));
        });
    }
    @Test public void messagePreviewCarriesConnectionWithoutRawSequence() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InspectorTheme.context(InstrumentationRegistry.getInstrumentation().getTargetContext());
            WebSocketEvent event = WebSocketEvent.restore(ID, ID, ID, 3, 123, "wss://example.test/stream?[redacted]", WebSocketEvent.Source.HOST,
                WebSocketEvent.Kind.MESSAGE, Collections.emptyList(), false, null, null, null, WebSocketEvent.Direction.INCOMING,
                WebSocketEvent.Outcome.RECEIVED, null, WebSocketEvent.PayloadReference.restore(WebSocketEvent.PayloadState.AVAILABLE, ID, 2_048L));
            InspectorRecordPresentation.Row row = InspectorRecordPresentation.socket(context, event, "message content", false);
            assertEquals("message content", row.title); assertTrue(row.subtitle.contains("2.0 KiB"));
            assertTrue(row.subtitle.contains(InspectorRecordPresentation.connection(context, ID))); assertFalse(row.subtitle.contains("#3"));
            assertTrue(InspectorRecordPresentation.context(context, event).contains(".123"));
            assertFalse(InspectorRecordPresentation.socket(context, event, "message content", true).subtitle.contains("example.test"));
            assertNotEquals(InspectorRecordPresentation.identity(ID), InspectorRecordPresentation.identity("12345678-1234-1234-1234-123456789abd"));
            InspectorRecordSearch.Record record = new InspectorRecordSearch.Record(event);
            InspectorRecordPresentation.prepareSearch(context, Collections.singletonList(record));
            assertTrue(record.contains(context.getString(R.string.osservio_ws_incoming)));
            InspectorRecordSearch.Record hidden = new InspectorRecordSearch.Record(LogEvent.restore(ID, 2, 100,
                LogLevel.INFO, "fixture", "[private]", true, false));
            InspectorRecordPresentation.prepareSearch(context, Collections.singletonList(hidden));
            assertTrue(hidden.contains(context.getString(R.string.osservio_ui_private_content)));
            assertFalse(hidden.contains("discarded secret"));
        });
    }
    @Test public void frozenMissingContentIsExplainedWithoutPretendingItIsReadable() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InspectorTheme.context(InstrumentationRegistry.getInstrumentation().getTargetContext());
            HTTPEvent.BodyReference reference = HTTPEvent.BodyReference.restore(HTTPEvent.BodyState.AVAILABLE, ID + ".response", 7);
            for (IncidentPayload.State state : new IncidentPayload.State[] {IncidentPayload.State.NOT_SELECTED, IncidentPayload.State.UNAVAILABLE, IncidentPayload.State.BUSY, IncidentPayload.State.BUDGET_EXCEEDED}) {
                IncidentPayload payload = IncidentPayload.restore(ID + ".response", state, null);
                InspectorReadContent value = HTTPPresentation.body(context, reference, Collections.singletonMap(payload.id, payload));
                assertFalse(value.available); assertFalse(value.allowsJson); assertEquals("", value.text);
                assertEquals(IncidentPayloadPresentation.content(context, payload), value.notice());
            }
        });
    }
}
