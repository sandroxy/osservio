package com.sandrox.osservio.export;

import com.sandrox.osservio.core.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class IncidentDocumentFixture {
    static final String ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    static final String SESSION = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String HTTP_ID = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private IncidentDocumentFixture() { }
    static IncidentSnapshot make(String name) {
        if (name.equals("absent")) return IncidentSnapshot.restore(ID, Long.MIN_VALUE, null, null, null);
        if (name.equals("empty")) return IncidentSnapshot.restore(ID, Long.MIN_VALUE, null, null, null,
            IncidentLogs.restore(Collections.emptyList(), 0, 4, 2), null, null);
        if (name.startsWith("diagnostic-")) {
            SystemDiagnostic evidence;
            switch (name) {
                case "diagnostic-signal":
                    Map<SystemDiagnostic.MetadataKey, SystemDiagnostic.Text> signalMetadata = new EnumMap<>(SystemDiagnostic.MetadataKey.class);
                    signalMetadata.put(SystemDiagnostic.MetadataKey.APP_VERSION, SystemDiagnostic.Text.capturePublic("😀"));
                    signalMetadata.put(SystemDiagnostic.MetadataKey.APP_BUILD, SystemDiagnostic.Text.capturePublic("42"));
                    evidence = SystemDiagnostic.appleSignal(AppleSignalCrash.restore("12345678-1234-1234-1234-abcdef123456", 1700000000000L,
                        AppleSignalCrash.Signal.ABORT, "#0", "18446744073709551615", 4294967295L, Arrays.asList("0", "18446744073709551615"),
                        Arrays.asList(AppleSignalCrash.Image.restore("4096", "4096", "12345678123412341234abcdef123456", "16777228", "18446744073709551615"),
                            AppleSignalCrash.Image.restore("18446744073709551615", "0", null, null, null)), signalMetadata, true)); break;
                case "diagnostic-android":
                    evidence = SystemDiagnostic.androidExit(9007199254740993L, 5, Integer.MIN_VALUE,
                        Integer.MAX_VALUE, 100, Long.MAX_VALUE, null); break;
                case "diagnostic-crash":
                    Map<SystemDiagnostic.MetadataKey, SystemDiagnostic.Text> metadata = new EnumMap<>(SystemDiagnostic.MetadataKey.class);
                    metadata.put(SystemDiagnostic.MetadataKey.APP_VERSION, SystemDiagnostic.Text.restore("测试1", true));
                    metadata.put(SystemDiagnostic.MetadataKey.APP_BUILD, SystemDiagnostic.Text.capturePublic("42"));
                    metadata.put(SystemDiagnostic.MetadataKey.OS_VERSION, SystemDiagnostic.Text.capturePublic("26.5"));
                    metadata.put(SystemDiagnostic.MetadataKey.DEVICE_MODEL, SystemDiagnostic.Text.capturePublic("iPhone17,1"));
                    evidence = SystemDiagnostic.metricKit(Long.MIN_VALUE, 9007199254740993L, SystemDiagnostic.Kind.CRASH,
                        metadata, 11, Long.MAX_VALUE, "18446744073709551615", null); break;
                case "diagnostic-hang":
                    evidence = SystemDiagnostic.metricKit(-1, 0, SystemDiagnostic.Kind.HANG, Collections.emptyMap(), null, null, null, 0L); break;
                default: throw new IllegalArgumentException("Unknown fixture");
            }
            return IncidentSnapshot.restore(ID, Long.MAX_VALUE, null, null, null, null, null, null, evidence);
        }
        if (!name.equals("complete")) throw new IllegalArgumentException("Unknown fixture");
        Map<EnvironmentSnapshot.Key, EnvironmentSnapshot.Fact> facts = new EnumMap<>(EnvironmentSnapshot.Key.class);
        for (EnvironmentSnapshot.Key key : EnvironmentSnapshot.Key.values()) facts.put(key, EnvironmentSnapshot.Fact.UNAVAILABLE);
        facts.put(EnvironmentSnapshot.Key.APP_IDENTIFIER, EnvironmentSnapshot.Fact.text("com.example.fixture"));
        facts.put(EnvironmentSnapshot.Key.APP_VERSION, EnvironmentSnapshot.Fact.text("测试1"));
        facts.put(EnvironmentSnapshot.Key.PHYSICAL_MEMORY_BYTES, EnvironmentSnapshot.Fact.number(9007199254740993L));
        facts.put(EnvironmentSnapshot.Key.PROCESS_ID, EnvironmentSnapshot.Fact.number(7));
        facts.put(EnvironmentSnapshot.Key.LOW_POWER_MODE, EnvironmentSnapshot.Fact.flag(false));
        facts.put(EnvironmentSnapshot.Key.THERMAL_STATE, EnvironmentSnapshot.Fact.UNSUPPORTED);
        EnvironmentSnapshot environment = EnvironmentSnapshot.restore(EnvironmentSnapshot.Platform.ANDROID, facts, Collections.singleton(EnvironmentSnapshot.Key.APP_VERSION), 123);
        BreadcrumbEvent crumb = BreadcrumbEvent.restore("cccccccc-cccc-cccc-cccc-cccccccccccc", SESSION, 9007199254740993L, Long.MIN_VALUE,
            Long.MAX_VALUE, BreadcrumbEvent.Kind.HOST_MARKER, "fixture.private", "[private]", true, false, Long.MIN_VALUE, BreadcrumbEvent.Retention.RESTART_RELEVANT);
        LogEvent log = LogEvent.restore(SESSION, 9007199254740993L, Long.MAX_VALUE, LogLevel.ERROR, "fixture", "Quote\"\\\n汉😀\0", false, true);
        LogEvent privateLog = LogEvent.restore(SESSION, 9007199254740994L, Long.MAX_VALUE, LogLevel.WARNING, "fixture", "[private]", true, false);
        String url = "https://example.com/frozen?[redacted]";
        HTTPEvent http = HTTPEvent.restore(HTTP_ID, SESSION, 9007199254740993L, 2, 123, "POST", HTTPEvent.Source.OKHTTP, url, url,
            Collections.singletonList(new HTTPHeader("authorization", "[redacted]")), Collections.singletonList(new HTTPHeader("content-type", "text/plain")),
            true, 200, HTTPEvent.State.COMPLETED, null, Long.MAX_VALUE,
            HTTPEvent.BodyReference.restore(HTTPEvent.BodyState.AVAILABLE, HTTP_ID + ".request", 0),
            HTTPEvent.BodyReference.restore(HTTPEvent.BodyState.AVAILABLE, HTTP_ID + ".response", 3));
        Map<String, IncidentPayload> bodies = new LinkedHashMap<>();
        bodies.put(HTTP_ID + ".request", IncidentPayload.restore(HTTP_ID + ".request", IncidentPayload.State.AVAILABLE, ""));
        bodies.put(HTTP_ID + ".response", IncidentPayload.restore(HTTP_ID + ".response", IncidentPayload.State.AVAILABLE, "汉"));
        List<WebSocketEvent> events = new ArrayList<>(); Map<String, IncidentPayload> payloads = new LinkedHashMap<>();
        IncidentPayload.State[] states = {IncidentPayload.State.NOT_SELECTED, IncidentPayload.State.UNAVAILABLE, IncidentPayload.State.BUSY,
            IncidentPayload.State.BUDGET_EXCEEDED, IncidentPayload.State.AVAILABLE, IncidentPayload.State.AVAILABLE};
        for (int offset = 0; offset < states.length; offset++) {
            String id = "eeeeeeee-eeee-eeee-eeee-00000000000" + (offset + 1); long size = offset == 4 ? 0 : offset == 5 ? 4 : 3;
            events.add(WebSocketEvent.restore(id, SESSION, SESSION, offset + 1L, 124, "wss://example.com/socket?[redacted]", WebSocketEvent.Source.URL_SESSION,
                WebSocketEvent.Kind.MESSAGE, Collections.emptyList(), false, null, null, null, WebSocketEvent.Direction.OUTGOING, WebSocketEvent.Outcome.SUBMITTED,
                null, WebSocketEvent.PayloadReference.restore(WebSocketEvent.PayloadState.AVAILABLE, id, size)));
            payloads.put(id, IncidentPayload.restore(id, states[offset], states[offset] == IncidentPayload.State.AVAILABLE ? (offset == 4 ? "" : "😀") : null));
        }
        events.add(WebSocketEvent.restore("eeeeeeee-eeee-eeee-eeee-000000000007", SESSION, SESSION, 7, 124, "wss://example.com/socket?[redacted]",
            WebSocketEvent.Source.URL_SESSION, WebSocketEvent.Kind.MESSAGE_RESULT, Collections.emptyList(), false, null, null, null,
            WebSocketEvent.Direction.OUTGOING, WebSocketEvent.Outcome.SENT, events.get(events.size() - 1).id, null));
        return IncidentSnapshot.restore(ID, Long.MIN_VALUE, IncidentSnapshot.Note.capture(LogMessage.privateText("synthetic-secret")), environment,
            IncidentSnapshot.Breadcrumbs.restore(Collections.singletonList(crumb), 3, Long.MAX_VALUE),
            IncidentLogs.restore(Arrays.asList(log, privateLog), 456, 3, 9), IncidentHTTP.restore(Collections.singletonList(http), bodies, 457, 3, 5, 7),
            IncidentWebSockets.restore(events, payloads, 458, 9, 5, 7));
    }
}
