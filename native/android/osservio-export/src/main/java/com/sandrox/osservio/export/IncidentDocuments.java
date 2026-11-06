package com.sandrox.osservio.export;

import com.sandrox.osservio.core.BreadcrumbEvent;
import com.sandrox.osservio.core.EnvironmentSnapshot;
import com.sandrox.osservio.core.HTTPEvent;
import com.sandrox.osservio.core.HTTPHeader;
import com.sandrox.osservio.core.IncidentHTTP;
import com.sandrox.osservio.core.IncidentLogs;
import com.sandrox.osservio.core.IncidentPayload;
import com.sandrox.osservio.core.IncidentSnapshot;
import com.sandrox.osservio.core.IncidentWebSockets;
import com.sandrox.osservio.core.LogEvent;
import com.sandrox.osservio.core.WebSocketEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.json.JSONObject;

/** Portable documents from one sanitized immutable snapshot. No capture, history lookup, or I/O.
 * Encode away from capture and UI paths. Large input or output is rejected, never truncated. */
public final class IncidentDocuments {
    static final class LimitExceeded extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        LimitExceeded() { super("Snapshot exceeds the export limit"); }
    }
    public static final int MAXIMUM_TEXT_BYTES = 2097152;
    public static final int MAXIMUM_DOCUMENT_BYTES = 16777216;
    public final String metadataJSON;
    public final String payloadsJSON;

    private IncidentDocuments(String metadataJSON, String payloadsJSON) {
        this.metadataJSON = metadataJSON; this.payloadsJSON = payloadsJSON;
    }
    public static IncidentDocuments encode(IncidentSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.textByteCount > MAXIMUM_TEXT_BYTES) throw new LimitExceeded();
        Map<String, Object> incident = object("id", snapshot.id, "timestampMillis", Long.toString(snapshot.timestampMillis),
            "note", snapshot.note == null ? null : object("text", snapshot.note.text, "isPrivate", snapshot.note.isPrivate, "isTruncated", snapshot.note.isTruncated),
            "environment", snapshot.environment == null ? null : environment(snapshot.environment),
            "breadcrumbs", snapshot.breadcrumbs == null ? null : breadcrumbs(snapshot.breadcrumbs),
            "logs", snapshot.logs == null ? null : logs(snapshot.logs), "http", snapshot.http == null ? null : http(snapshot.http),
            "webSockets", snapshot.webSockets == null ? null : webSockets(snapshot.webSockets));
        String metadata = new JSONObject(object("format", "osservio.incident", "revision", 1, "payloadFile", "payloads.json", "incident", incident)).toString();
        String payloads = new JSONObject(object("format", "osservio.incident.payloads", "revision", 1, "incidentID", snapshot.id,
            "http", content(snapshot.http == null ? null : snapshot.http.payloads),
            "webSockets", content(snapshot.webSockets == null ? null : snapshot.webSockets.payloads))).toString();
        if (metadata == null || payloads == null) throw new IllegalArgumentException("Snapshot cannot be represented as JSON");
        if (metadata.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_DOCUMENT_BYTES - payloads.getBytes(StandardCharsets.UTF_8).length)
            throw new LimitExceeded();
        return new IncidentDocuments(metadata, payloads);
    }
    // Only this encoder supplies keys and values. No arbitrary application objects are traversed.
    private static Map<String, Object> object(Object... fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < fields.length; index += 2) result.put((String) fields[index], fields[index + 1] == null ? JSONObject.NULL : fields[index + 1]);
        return result;
    }
    private static String decimal(Long value) { return value == null ? null : value.toString(); }
    private static String code(Enum<?> value) {
        if (value == null) return null;
        if (value == EnvironmentSnapshot.Key.PROCESS_ID) return "processID";
        if (value == HTTPEvent.Source.OKHTTP || value == WebSocketEvent.Source.OKHTTP) return "okHttp";
        String[] words = value.name().toLowerCase(Locale.ROOT).split("_"); StringBuilder result = new StringBuilder(words[0]);
        for (int index = 1; index < words.length; index++) result.append(Character.toUpperCase(words[index].charAt(0))).append(words[index].substring(1));
        return result.toString();
    }
    private static Map<String, Object> content(Map<String, IncidentPayload> payloads) {
        Map<String, Object> result = new TreeMap<>();
        if (payloads != null) for (IncidentPayload value : payloads.values()) if (value.text != null) result.put(value.id, value.text);
        return result;
    }
    private static Map<String, Object> selection(Map<String, IncidentPayload> payloads) {
        Map<String, Object> result = new TreeMap<>();
        for (IncidentPayload value : payloads.values()) result.put(value.id, object("state", code(value.state), "textByteCount", value.textByteCount));
        return result;
    }
    private static List<Object> headers(List<HTTPHeader> values) {
        List<Object> result = new ArrayList<>(); for (HTTPHeader value : values) result.add(object("name", value.name, "value", value.value)); return result;
    }
    private static Map<String, Object> environment(EnvironmentSnapshot value) {
        Map<String, Object> facts = new TreeMap<>();
        for (Map.Entry<EnvironmentSnapshot.Key, EnvironmentSnapshot.Value> entry : value.values.entrySet()) {
            EnvironmentSnapshot.Fact fact = entry.getValue().fact; Object content;
            switch (fact.type) {
                case TEXT: content = fact.text; break;
                case NUMBER: content = decimal(fact.number); break;
                case FLAG: content = fact.flag; break;
                case UNAVAILABLE: case UNSUPPORTED: content = null; break;
                default: throw new IllegalArgumentException("Unknown environment fact");
            }
            facts.put(code(entry.getKey()), object("type", code(fact.type), "value", content, "isTruncated", entry.getValue().isTruncated));
        }
        return object("platform", code(value.platform), "timestampMillis", Long.toString(value.timestampMillis), "values", facts);
    }
    private static Map<String, Object> breadcrumbs(IncidentSnapshot.Breadcrumbs value) {
        List<Object> events = new ArrayList<>();
        for (BreadcrumbEvent event : value.events) events.add(object("id", event.id, "sessionID", event.sessionId, "sequence", Long.toString(event.sequence),
            "timestampMillis", Long.toString(event.timestampMillis), "sessionElapsedMillis", decimal(event.sessionElapsedMillis), "kind", code(event.kind),
            "label", event.label, "message", event.message, "isPrivate", event.isPrivate, "isTruncated", event.isTruncated,
            "value", decimal(event.value), "retention", code(event.retention)));
        return object("sourceEventCount", value.sourceEventCount, "omittedEventCount", value.omittedEventCount,
            "sourceEvictedCount", Long.toString(value.sourceEvictedCount), "retainedTextBytes", value.retainedTextBytes, "events", events);
    }
    private static Map<String, Object> logs(IncidentLogs value) {
        List<Object> events = new ArrayList<>();
        for (LogEvent event : value.events) events.add(object("sessionID", event.sessionId, "sequence", Long.toString(event.sequence),
            "timestampMillis", Long.toString(event.timestampMillis), "level", code(event.level), "category", event.category,
            "message", event.message, "isPrivate", event.isPrivate, "isTruncated", event.isTruncated));
        return object("observedAtMillis", Long.toString(value.observedAtMillis), "sourceEventCount", value.sourceEventCount, "omittedEventCount", value.omittedEventCount,
            "sourceEvictedCount", Long.toString(value.sourceEvictedCount), "retainedTextBytes", value.retainedTextBytes, "events", events);
    }
    private static Map<String, Object> http(IncidentHTTP value) {
        List<Object> events = new ArrayList<>();
        for (HTTPEvent event : value.events) events.add(object("id", event.id, "sessionID", event.sessionId, "sequence", Long.toString(event.sequence),
            "revision", Long.toString(event.revision), "timestampMillis", Long.toString(event.timestampMillis), "method", event.method,
            "source", code(event.source), "url", event.url, "responseURL", event.responseURL, "requestHeaders", headers(event.requestHeaders),
            "responseHeaders", headers(event.responseHeaders), "headersLimited", event.headersLimited, "statusCode", event.statusCode,
            "state", code(event.state), "failure", code(event.failure), "durationMillis", decimal(event.durationMillis),
            "requestBody", body(event.requestBody), "responseBody", body(event.responseBody)));
        return object("observedAtMillis", Long.toString(value.observedAtMillis), "sourceEventCount", value.sourceEventCount, "omittedEventCount", value.omittedEventCount,
            "sourceEvictedEvents", Long.toString(value.sourceEvictedEvents), "sourceEvictedBodies", Long.toString(value.sourceEvictedBodies),
            "retainedTextBytes", value.retainedTextBytes, "retainedPayloadBytes", value.retainedPayloadBytes, "payloads", selection(value.payloads), "events", events);
    }
    private static Map<String, Object> body(HTTPEvent.BodyReference value) {
        return object("state", code(value.state), "blobID", value.blobId, "observedBytes", Long.toString(value.observedBytes));
    }
    private static Map<String, Object> webSockets(IncidentWebSockets value) {
        List<Object> events = new ArrayList<>();
        for (WebSocketEvent event : value.events) events.add(object("id", event.id, "connectionID", event.connectionId, "sessionID", event.sessionId,
            "sequence", Long.toString(event.sequence), "timestampMillis", Long.toString(event.timestampMillis), "url", event.url, "source", code(event.source),
            "kind", code(event.kind), "headers", headers(event.headers), "headersLimited", event.headersLimited, "statusCode", event.statusCode,
            "closeCode", event.closeCode, "failure", code(event.failure), "direction", code(event.direction), "outcome", code(event.outcome),
            "relatedMessageID", event.relatedMessageId, "payload", event.payload == null ? null : object("state", code(event.payload.state),
                "blobID", event.payload.blobId, "byteCount", decimal(event.payload.byteCount))));
        return object("observedAtMillis", Long.toString(value.observedAtMillis), "sourceEventCount", value.sourceEventCount, "omittedEventCount", value.omittedEventCount,
            "sourceEvictedEvents", Long.toString(value.sourceEvictedEvents), "sourceEvictedPayloads", Long.toString(value.sourceEvictedPayloads),
            "retainedTextBytes", value.retainedTextBytes, "retainedPayloadBytes", value.retainedPayloadBytes, "payloads", selection(value.payloads), "events", events);
    }
}
