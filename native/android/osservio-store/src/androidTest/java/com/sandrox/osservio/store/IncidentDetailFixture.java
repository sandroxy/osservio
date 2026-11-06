package com.sandrox.osservio.store;

import androidx.test.platform.app.InstrumentationRegistry;
import com.sandrox.osservio.core.HTTPEvent;
import com.sandrox.osservio.core.HTTPHeader;
import com.sandrox.osservio.core.IncidentHTTP;
import com.sandrox.osservio.core.IncidentLogs;
import com.sandrox.osservio.core.IncidentPayload;
import com.sandrox.osservio.core.IncidentSnapshot;
import com.sandrox.osservio.core.IncidentWebSockets;
import com.sandrox.osservio.core.LogEvent;
import com.sandrox.osservio.core.LogLevel;
import com.sandrox.osservio.core.LogMessage;
import com.sandrox.osservio.core.WebSocketEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class IncidentDetailFixture {
    private IncidentDetailFixture() { }
    static String id(int value) { return String.format(Locale.ROOT, "00000000-0000-0000-0000-%012d", value); }
    static final String BODY = "public\u0000例😀";
    static IncidentSnapshot snapshot() {
        IncidentLogs logs = IncidentLogs.restore(Arrays.asList(
            LogEvent.restore(id(1), Long.MAX_VALUE - 1, Long.MIN_VALUE, LogLevel.ERROR, "fixture", "[private]", true, false),
            LogEvent.restore(id(1), Long.MAX_VALUE, 123, LogLevel.WARNING, "fixture", BODY, false, true)), -1, 5, 9);
        List<HTTPEvent> events = new ArrayList<>(); Map<String, IncidentPayload> payloads = new LinkedHashMap<>();
        IncidentPayload.State[] states = {IncidentPayload.State.AVAILABLE, IncidentPayload.State.NOT_SELECTED, IncidentPayload.State.UNAVAILABLE,
            IncidentPayload.State.BUSY, IncidentPayload.State.BUDGET_EXCEEDED};
        for (int position = 0; position < states.length; position++) {
            String eventId = id(10 + position), requestId = eventId + ".request", responseId = eventId + ".response"; boolean first = position == 0;
            events.add(HTTPEvent.restore(eventId, id(1), position + 1, 2, Long.MIN_VALUE + position, "POST", first ? HTTPEvent.Source.OKHTTP : HTTPEvent.Source.URL_SESSION,
                "https://example.com/path?[redacted]", "https://example.com/result",
                first ? Arrays.asList(new HTTPHeader("authorization", "[redacted]"), new HTTPHeader("x-fixture", "例\u0000")) : Collections.emptyList(),
                first ? Collections.singletonList(new HTTPHeader("content-type", "text/plain")) : Collections.emptyList(), first, 201, HTTPEvent.State.COMPLETED, null, Long.MAX_VALUE,
                HTTPEvent.BodyReference.restore(first ? HTTPEvent.BodyState.AVAILABLE : HTTPEvent.BodyState.OMITTED, first ? requestId : null, 0),
                HTTPEvent.BodyReference.restore(HTTPEvent.BodyState.AVAILABLE, responseId, first ? BODY.getBytes(StandardCharsets.UTF_8).length : 1)));
            if (first) payloads.put(requestId, IncidentPayload.restore(requestId, IncidentPayload.State.AVAILABLE, ""));
            payloads.put(responseId, IncidentPayload.restore(responseId, states[position], first ? BODY : null));
        }
        IncidentHTTP http = IncidentHTTP.restore(events, payloads, -2, 7, 11, 13);
        List<WebSocketEvent> socketEvents = Arrays.asList(
            WebSocketEvent.restore(id(20), id(2), id(1), 20, 20, "wss://example.com", WebSocketEvent.Source.URL_SESSION, WebSocketEvent.Kind.OPENED,
                Collections.singletonList(new HTTPHeader("authorization", "[redacted]")), true, 101, null, null, null, null, null, null),
            WebSocketEvent.restore(id(21), id(2), id(1), 21, 21, "wss://example.com", WebSocketEvent.Source.URL_SESSION, WebSocketEvent.Kind.MESSAGE,
                Collections.emptyList(), false, null, null, null, WebSocketEvent.Direction.OUTGOING, WebSocketEvent.Outcome.SUBMITTED, null,
                WebSocketEvent.PayloadReference.restore(WebSocketEvent.PayloadState.AVAILABLE, id(21), 0L)),
            WebSocketEvent.restore(id(22), id(2), id(1), 22, 22, "wss://example.com", WebSocketEvent.Source.URL_SESSION, WebSocketEvent.Kind.MESSAGE_RESULT,
                Collections.emptyList(), false, null, null, null, WebSocketEvent.Direction.OUTGOING, WebSocketEvent.Outcome.SENT, id(21), null),
            WebSocketEvent.restore(id(23), id(2), id(1), 23, 23, "wss://example.com", WebSocketEvent.Source.OKHTTP, WebSocketEvent.Kind.MESSAGE,
                Collections.emptyList(), false, null, null, null, WebSocketEvent.Direction.INCOMING, WebSocketEvent.Outcome.RECEIVED, null,
                WebSocketEvent.PayloadReference.restore(WebSocketEvent.PayloadState.BINARY, null, Long.MAX_VALUE)),
            WebSocketEvent.restore(id(24), id(2), id(1), 24, 24, "wss://example.com", WebSocketEvent.Source.HOST, WebSocketEvent.Kind.MESSAGE,
                Collections.emptyList(), false, null, null, null, WebSocketEvent.Direction.INCOMING, WebSocketEvent.Outcome.RECEIVED, null,
                WebSocketEvent.PayloadReference.restore(WebSocketEvent.PayloadState.TRUNCATED, id(24), null)));
        Map<String, IncidentPayload> socketPayloads = new LinkedHashMap<>();
        socketPayloads.put(id(21), IncidentPayload.restore(id(21), IncidentPayload.State.AVAILABLE, ""));
        socketPayloads.put(id(24), IncidentPayload.restore(id(24), IncidentPayload.State.AVAILABLE, "例"));
        IncidentWebSockets sockets = IncidentWebSockets.restore(socketEvents, socketPayloads, -3, 8, 17, 19);
        return IncidentSnapshot.capture(LogMessage.privateText("never written"), null, null, logs, http, sockets);
    }
    static String shared(String name) throws IOException {
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
