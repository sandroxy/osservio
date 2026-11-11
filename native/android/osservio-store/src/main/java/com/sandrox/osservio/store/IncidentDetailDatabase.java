package com.sandrox.osservio.store;

import static com.sandrox.osservio.store.EvidenceSQLite.flag;
import static com.sandrox.osservio.store.EvidenceSQLite.integer;
import static com.sandrox.osservio.store.EvidenceSQLite.text;
import static com.sandrox.osservio.store.EvidenceSQLite.textColumn;
import static com.sandrox.osservio.store.IncidentNames.name;
import static com.sandrox.osservio.store.IncidentNames.parse;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.sandrox.osservio.core.HTTPEvent;
import com.sandrox.osservio.core.HTTPHeader;
import com.sandrox.osservio.core.IncidentHTTP;
import com.sandrox.osservio.core.IncidentLogs;
import com.sandrox.osservio.core.IncidentPayload;
import com.sandrox.osservio.core.IncidentSnapshot;
import com.sandrox.osservio.core.IncidentWebSockets;
import com.sandrox.osservio.core.LogEvent;
import com.sandrox.osservio.core.LogLevel;
import com.sandrox.osservio.core.WebSocketEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Normalized attachments on the incident store's existing serial connection and transaction. */
final class IncidentDetailDatabase {
    enum Kind { LOGS, HTTP, WEBSOCKET }
    static final class Window {
        final long observedAt, evicted;
        final Long evictedPayloads;
        final int count, sourceCount, bytes, payloadBytes;
        Window(long observedAt, int count, int sourceCount, long evicted, Long evictedPayloads, int bytes, int payloadBytes) {
            this.observedAt = observedAt; this.count = count; this.sourceCount = sourceCount; this.evicted = evicted;
            this.evictedPayloads = evictedPayloads; this.bytes = bytes; this.payloadBytes = payloadBytes;
        }
    }
    static final class Detail {
        final IncidentLogs logs;
        final IncidentHTTP http;
        final IncidentWebSockets webSockets;
        Detail(IncidentLogs logs, IncidentHTTP http, IncidentWebSockets webSockets) { this.logs = logs; this.http = http; this.webSockets = webSockets; }
    }
    private static final class Rows { int headers, payloads; }
    private final SQLiteDatabase database;
    IncidentDetailDatabase(SQLiteDatabase database) { this.database = database; }

    static final String[] CONTENT_POLICY_SCHEMA = {
        "ALTER TABLE incident_http ADD COLUMN request_processed INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE incident_http ADD COLUMN response_processed INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE incident_websocket ADD COLUMN payload_processed INTEGER NOT NULL DEFAULT 0"
    };
    static final String[] SCHEMA = {
        "CREATE TABLE incident_windows (incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE, "
            + "kind TEXT NOT NULL, observed_at INTEGER NOT NULL, event_count INTEGER NOT NULL, source_count INTEGER NOT NULL, "
            + "source_evicted INTEGER NOT NULL, payload_evicted INTEGER, text_bytes INTEGER NOT NULL, payload_bytes INTEGER NOT NULL, PRIMARY KEY(incident_id, kind))",
        "CREATE TABLE incident_logs (incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE, "
            + "position INTEGER NOT NULL, session_id TEXT NOT NULL COLLATE NOCASE, sequence INTEGER NOT NULL, timestamp INTEGER NOT NULL, "
            + "level TEXT NOT NULL, category TEXT NOT NULL, message TEXT NOT NULL, private INTEGER NOT NULL, truncated INTEGER NOT NULL, "
            + "PRIMARY KEY(incident_id, position), UNIQUE(incident_id, session_id, sequence))",
        "CREATE TABLE incident_http (incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE, "
            + "position INTEGER NOT NULL, id TEXT NOT NULL COLLATE NOCASE, session_id TEXT NOT NULL, sequence INTEGER NOT NULL, "
            + "revision INTEGER NOT NULL, timestamp INTEGER NOT NULL, method TEXT NOT NULL, source TEXT NOT NULL, "
            + "url TEXT NOT NULL, response_url TEXT, headers_limited INTEGER NOT NULL, status_code INTEGER, state TEXT NOT NULL, "
            + "failure TEXT, duration TEXT, request_state TEXT NOT NULL, request_blob TEXT, request_bytes INTEGER NOT NULL, "
            + "response_state TEXT NOT NULL, response_blob TEXT, response_bytes INTEGER NOT NULL, PRIMARY KEY(incident_id, position), UNIQUE(incident_id, id))",
        "CREATE TABLE incident_websocket (incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE, "
            + "position INTEGER NOT NULL, id TEXT NOT NULL COLLATE NOCASE, connection_id TEXT NOT NULL, session_id TEXT NOT NULL, "
            + "sequence INTEGER NOT NULL, timestamp INTEGER NOT NULL, url TEXT NOT NULL, source TEXT NOT NULL, kind TEXT NOT NULL, "
            + "headers_limited INTEGER NOT NULL, status_code INTEGER, close_code INTEGER, failure TEXT, direction TEXT, outcome TEXT, "
            + "related_message TEXT, payload_state TEXT, payload_blob TEXT, payload_count INTEGER, PRIMARY KEY(incident_id, position), UNIQUE(incident_id, id))",
        "CREATE TABLE incident_headers (incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE, "
            + "family TEXT NOT NULL, event_position INTEGER NOT NULL, scope TEXT NOT NULL, position INTEGER NOT NULL, name TEXT NOT NULL, value TEXT NOT NULL, "
            + "PRIMARY KEY(incident_id, family, event_position, scope, position))",
        "CREATE TABLE incident_payloads (incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE, "
            + "family TEXT NOT NULL, id TEXT NOT NULL COLLATE NOCASE, state TEXT NOT NULL, text TEXT, PRIMARY KEY(incident_id, family, id))"
    };
    void save(IncidentSnapshot snapshot) {
        String id = snapshot.id;
        if (snapshot.logs != null) {
            IncidentLogs logs = snapshot.logs;
            saveWindow(id, Kind.LOGS, logs.observedAtMillis, logs.events.size(), logs.sourceEventCount, logs.sourceEvictedCount, null, logs.retainedTextBytes, 0);
            for (int position = 0; position < logs.events.size(); position++) {
                LogEvent event = logs.events.get(position);
                database.execSQL("INSERT INTO incident_logs(incident_id, position, session_id, sequence, timestamp, level, category, message, private, truncated) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", new Object[] {id, position, event.sessionId, event.sequence, event.timestampMillis,
                        name(event.level), event.category, event.message, event.isPrivate ? 1 : 0, event.isTruncated ? 1 : 0});
            }
        }
        if (snapshot.http != null) {
            IncidentHTTP http = snapshot.http;
            saveWindow(id, Kind.HTTP, http.observedAtMillis, http.events.size(), http.sourceEventCount, http.sourceEvictedEvents, http.sourceEvictedBodies,
                http.retainedTextBytes, http.retainedPayloadBytes);
            for (int position = 0; position < http.events.size(); position++) {
                HTTPEvent event = http.events.get(position);
                database.execSQL("INSERT INTO incident_http(incident_id, position, id, session_id, sequence, revision, timestamp, method, source, "
                    + "url, response_url, headers_limited, status_code, state, failure, duration, request_state, request_blob, request_bytes, "
                    + "response_state, response_blob, response_bytes, request_processed, response_processed) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new Object[] {id, position, event.id, event.sessionId, event.sequence, event.revision, event.timestampMillis, event.method,
                        name(event.source), event.url, event.responseURL, event.headersLimited ? 1 : 0, event.statusCode, name(event.state), name(event.failure),
                        event.durationMillis == null ? null : event.durationMillis.toString(), name(event.requestBody.state), event.requestBody.blobId,
                        event.requestBody.observedBytes, name(event.responseBody.state), event.responseBody.blobId, event.responseBody.observedBytes, event.requestBody.isProcessed ? 1 : 0, event.responseBody.isProcessed ? 1 : 0});
                saveHeaders(id, Kind.HTTP, position, "request", event.requestHeaders);
                saveHeaders(id, Kind.HTTP, position, "response", event.responseHeaders);
            }
            savePayloads(id, Kind.HTTP, http.payloads);
        }
        if (snapshot.webSockets != null) {
            IncidentWebSockets sockets = snapshot.webSockets;
            saveWindow(id, Kind.WEBSOCKET, sockets.observedAtMillis, sockets.events.size(), sockets.sourceEventCount, sockets.sourceEvictedEvents,
                sockets.sourceEvictedPayloads, sockets.retainedTextBytes, sockets.retainedPayloadBytes);
            for (int position = 0; position < sockets.events.size(); position++) {
                WebSocketEvent event = sockets.events.get(position);
                database.execSQL("INSERT INTO incident_websocket(incident_id, position, id, connection_id, session_id, sequence, timestamp, url, source, "
                    + "kind, headers_limited, status_code, close_code, failure, direction, outcome, related_message, payload_state, payload_blob, payload_count, payload_processed) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", new Object[] {id, position, event.id, event.connectionId, event.sessionId,
                        event.sequence, event.timestampMillis, event.url, name(event.source), name(event.kind), event.headersLimited ? 1 : 0, event.statusCode,
                        event.closeCode, name(event.failure), name(event.direction), name(event.outcome), event.relatedMessageId,
                        event.payload == null ? null : name(event.payload.state), event.payload == null ? null : event.payload.blobId,
                        event.payload == null ? null : event.payload.byteCount, event.payload != null && event.payload.isProcessed ? 1 : 0});
                saveHeaders(id, Kind.WEBSOCKET, position, "event", event.headers);
            }
            savePayloads(id, Kind.WEBSOCKET, sockets.payloads);
        }
    }
    private void saveWindow(String id, Kind kind, long time, int count, int source, long evicted, Long payloadEvicted, int bytes, int payloadBytes) {
        database.execSQL("INSERT INTO incident_windows(incident_id, kind, observed_at, event_count, source_count, source_evicted, payload_evicted, text_bytes, payload_bytes) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", new Object[] {id, name(kind), time, count, source, evicted, payloadEvicted, bytes, payloadBytes});
    }
    private void saveHeaders(String id, Kind kind, int eventPosition, String scope, List<HTTPHeader> headers) {
        for (int position = 0; position < headers.size(); position++) {
            HTTPHeader header = headers.get(position);
            database.execSQL("INSERT INTO incident_headers(incident_id, family, event_position, scope, position, name, value) VALUES (?, ?, ?, ?, ?, ?, ?)",
                new Object[] {id, name(kind), eventPosition, scope, position, header.name, header.value});
        }
    }
    private void savePayloads(String id, Kind kind, Map<String, IncidentPayload> payloads) {
        for (IncidentPayload payload : payloads.values()) {
            database.execSQL("INSERT INTO incident_payloads(incident_id, family, id, state, text) VALUES (?, ?, ?, ?, ?)",
                new Object[] {id, name(kind), payload.id, name(payload.state), payload.text});
        }
    }
    Map<Kind, Window> windows(String id) {
        Map<Kind, Window> windows = new EnumMap<>(Kind.class);
        try (Cursor row = query("SELECT " + textColumn("kind", 16) + ",observed_at,event_count,source_count,source_evicted,payload_evicted,text_bytes,payload_bytes "
            + "FROM incident_windows WHERE incident_id=? LIMIT 4", id)) {
            while (row.moveToNext()) {
                Kind kind = parse(Kind.values(), text(row, 0, 16));
                long count = integer(row, 2), source = integer(row, 3), evicted = integer(row, 4);
                Long payloadEvicted = row.isNull(5) ? null : integer(row, 5);
                long bytes = integer(row, 6), payloadBytes = integer(row, 7);
                if (windows.containsKey(kind) || count < 0 || count > 2000 || source < count || source > Integer.MAX_VALUE || evicted < 0
                    || bytes < 0 || bytes > 1048576 || payloadBytes < 0 || payloadBytes > 262144
                    || (kind == Kind.LOGS ? (payloadEvicted != null || payloadBytes != 0) : (payloadEvicted == null || payloadEvicted < 0))) throw EvidenceStorageException.invalid();
                windows.put(kind, new Window(integer(row, 1), (int) count, (int) source, evicted, payloadEvicted, (int) bytes, (int) payloadBytes));
            }
        }
        return windows;
    }
    Detail read(String id, Map<Kind, Window> windows) {
        Rows rows = new Rows();
        try {
            IncidentLogs logs = readLogs(id, windows.get(Kind.LOGS));
            IncidentHTTP http = readHTTP(id, windows.get(Kind.HTTP), rows);
            IncidentWebSockets sockets = readWebSockets(id, windows.get(Kind.WEBSOCKET), rows);
            // Unknown families and references outside the declared windows cannot become invisible evidence.
            if (EvidenceSQLite.scalar(database, "SELECT count(*) FROM incident_headers WHERE incident_id=?", id) != rows.headers
                || EvidenceSQLite.scalar(database, "SELECT count(*) FROM incident_payloads WHERE incident_id=?", id) != rows.payloads) throw EvidenceStorageException.invalid();
            return new Detail(logs, http, sockets);
        } catch (IllegalArgumentException failure) {
            EvidenceStorageException invalid = EvidenceStorageException.invalid(); invalid.initCause(failure); throw invalid;
        }
    }
    private IncidentLogs readLogs(String id, Window window) {
        List<LogEvent> events = new ArrayList<>(); int bytes = 0;
        String projection = "position," + textColumn("session_id", 36) + ",sequence,timestamp," + textColumn("level", 16) + ","
            + textColumn("category", 64) + "," + textColumn("message", 65536) + ",private,truncated";
        try (Cursor row = query("SELECT " + projection + " FROM incident_logs WHERE incident_id=? ORDER BY position LIMIT 2001", id)) {
            while (row.moveToNext()) {
                if (window == null || events.size() >= window.count || integer(row, 0) != events.size()) throw EvidenceStorageException.invalid();
                LogEvent event = LogEvent.restore(text(row, 1, 36), integer(row, 2), integer(row, 3), parse(LogLevel.values(), text(row, 4, 16)),
                    text(row, 5, 64), text(row, 6, 65536), flag(row, 7), flag(row, 8));
                if (event.textByteCount > window.bytes - bytes) throw EvidenceStorageException.invalid();
                bytes += event.textByteCount; events.add(event);
            }
        }
        if (window == null) return null;
        if (events.size() != window.count || bytes != window.bytes) throw EvidenceStorageException.invalid();
        return IncidentLogs.restore(events, window.observedAt, window.sourceCount, window.evicted);
    }
    private IncidentHTTP readHTTP(String id, Window window, Rows rows) {
        List<HTTPEvent> events = new ArrayList<>(); int bytes = 0;
        String projection = "position," + textColumn("id", 36) + "," + textColumn("session_id", 36) + ",sequence,revision,timestamp,"
            + textColumn("method", 16) + "," + textColumn("source", 32) + "," + textColumn("url", 4128) + "," + textColumn("response_url", 4128)
            + ",headers_limited,status_code," + textColumn("state", 32) + "," + textColumn("failure", 32) + "," + textColumn("duration", 20) + ","
            + textColumn("request_state", 32) + "," + textColumn("request_blob", 45) + ",request_bytes," + textColumn("response_state", 32)
            + "," + textColumn("response_blob", 45) + ",response_bytes,request_processed,response_processed";
        try (Cursor row = query("SELECT " + projection + " FROM incident_http WHERE incident_id=? ORDER BY position LIMIT 2001", id)) {
            while (row.moveToNext()) {
                if (window == null || events.size() >= window.count || integer(row, 0) != events.size()) throw EvidenceStorageException.invalid();
                Map<String, List<HTTPHeader>> headers = readHeaders(id, Kind.HTTP, events.size(), rows, "request", "response");
                HTTPEvent.BodyReference request = HTTPEvent.BodyReference.restore(parse(HTTPEvent.BodyState.values(), text(row, 15, 32)), optionalText(row, 16, 45), integer(row, 17), flag(row, 21));
                HTTPEvent.BodyReference response = HTTPEvent.BodyReference.restore(parse(HTTPEvent.BodyState.values(), text(row, 18, 32)), optionalText(row, 19, 45), integer(row, 20), flag(row, 22));
                HTTPEvent event = HTTPEvent.restore(text(row, 1, 36), text(row, 2, 36), integer(row, 3), integer(row, 4), integer(row, 5), text(row, 6, 16),
                    parse(HTTPEvent.Source.values(), text(row, 7, 32)), text(row, 8, 4128), optionalText(row, 9, 4128), headers.get("request"), headers.get("response"),
                    flag(row, 10), optionalInt(row, 11), parse(HTTPEvent.State.values(), text(row, 12, 32)), optionalEnum(row, 13, HTTPEvent.Failure.values()), duration(row, 14), request, response);
                if (event.textByteCount > window.bytes - bytes) throw EvidenceStorageException.invalid();
                bytes += event.textByteCount; events.add(event);
            }
        }
        Map<String, IncidentPayload> payloads = readPayloads(id, Kind.HTTP, window == null ? 0 : window.count * 2, window == null ? 0 : window.payloadBytes);
        rows.payloads += payloads.size();
        if (window == null) return null;
        if (events.size() != window.count || bytes != window.bytes || window.evictedPayloads == null) throw EvidenceStorageException.invalid();
        return IncidentHTTP.restore(events, payloads, window.observedAt, window.sourceCount, window.evicted, window.evictedPayloads);
    }
    private IncidentWebSockets readWebSockets(String id, Window window, Rows rows) {
        List<WebSocketEvent> events = new ArrayList<>(); int bytes = 0;
        String projection = "position," + textColumn("id", 36) + "," + textColumn("connection_id", 36) + "," + textColumn("session_id", 36) + ",sequence,timestamp,"
            + textColumn("url", 4128) + "," + textColumn("source", 32) + "," + textColumn("kind", 32) + ",headers_limited,status_code,close_code,"
            + textColumn("failure", 32) + "," + textColumn("direction", 32) + "," + textColumn("outcome", 32) + "," + textColumn("related_message", 36) + ","
            + textColumn("payload_state", 32) + "," + textColumn("payload_blob", 36) + ",payload_count,payload_processed";
        try (Cursor row = query("SELECT " + projection + " FROM incident_websocket WHERE incident_id=? ORDER BY position LIMIT 2001", id)) {
            while (row.moveToNext()) {
                if (window == null || events.size() >= window.count || integer(row, 0) != events.size()) throw EvidenceStorageException.invalid();
                Map<String, List<HTTPHeader>> headers = readHeaders(id, Kind.WEBSOCKET, events.size(), rows, "event");
                WebSocketEvent.PayloadReference payload = null;
                if (row.isNull(16)) { if (!row.isNull(17) || !row.isNull(18) || flag(row, 19)) throw EvidenceStorageException.invalid(); }
                else payload = WebSocketEvent.PayloadReference.restore(parse(WebSocketEvent.PayloadState.values(), text(row, 16, 32)), optionalText(row, 17, 36), row.isNull(18) ? null : integer(row, 18), flag(row, 19));
                WebSocketEvent event = WebSocketEvent.restore(text(row, 1, 36), text(row, 2, 36), text(row, 3, 36), integer(row, 4), integer(row, 5), text(row, 6, 4128),
                    parse(WebSocketEvent.Source.values(), text(row, 7, 32)), parse(WebSocketEvent.Kind.values(), text(row, 8, 32)), headers.get("event"), flag(row, 9),
                    optionalInt(row, 10), optionalInt(row, 11), optionalEnum(row, 12, WebSocketEvent.Failure.values()), optionalEnum(row, 13, WebSocketEvent.Direction.values()),
                    optionalEnum(row, 14, WebSocketEvent.Outcome.values()), optionalText(row, 15, 36), payload);
                if (event.textByteCount > window.bytes - bytes) throw EvidenceStorageException.invalid();
                bytes += event.textByteCount; events.add(event);
            }
        }
        Map<String, IncidentPayload> payloads = readPayloads(id, Kind.WEBSOCKET, window == null ? 0 : window.count, window == null ? 0 : window.payloadBytes);
        rows.payloads += payloads.size();
        if (window == null) return null;
        if (events.size() != window.count || bytes != window.bytes || window.evictedPayloads == null) throw EvidenceStorageException.invalid();
        return IncidentWebSockets.restore(events, payloads, window.observedAt, window.sourceCount, window.evicted, window.evictedPayloads);
    }
    private Map<String, List<HTTPHeader>> readHeaders(String id, Kind kind, int position, Rows rows, String... scopes) {
        Map<String, List<HTTPHeader>> headers = new LinkedHashMap<>();
        for (String scope : scopes) headers.put(scope, new ArrayList<>());
        String projection = textColumn("scope", 16) + ",position," + textColumn("name", 64) + "," + textColumn("value", 16384);
        try (Cursor row = query("SELECT " + projection + " FROM incident_headers WHERE incident_id=? AND family=? AND event_position=? ORDER BY scope, position LIMIT 65",
            id, name(kind), Integer.toString(position))) {
            while (row.moveToNext()) {
                List<HTTPHeader> values = headers.get(text(row, 0, 16));
                if (values == null || values.size() >= 32 || integer(row, 1) != values.size()) throw EvidenceStorageException.invalid();
                values.add(new HTTPHeader(text(row, 2, 64), text(row, 3, 16384))); rows.headers++;
            }
        }
        return headers;
    }
    private Map<String, IncidentPayload> readPayloads(String id, Kind kind, int maximumCount, int byteLimit) {
        Map<String, IncidentPayload> payloads = new LinkedHashMap<>(); int bytes = 0;
        String projection = textColumn("id", 45) + "," + textColumn("state", 32) + "," + textColumn("text", Math.min(65536, byteLimit));
        try (Cursor row = query("SELECT " + projection + " FROM incident_payloads WHERE incident_id=? AND family=? LIMIT ?", id, name(kind), Integer.toString(maximumCount + 1))) {
            while (row.moveToNext()) {
                if (payloads.size() >= maximumCount) throw EvidenceStorageException.invalid();
                IncidentPayload payload = IncidentPayload.restore(text(row, 0, 45), parse(IncidentPayload.State.values(), text(row, 1, 32)), optionalText(row, 2, Math.min(65536, byteLimit - bytes)));
                if (payloads.put(payload.id, payload) != null) throw EvidenceStorageException.invalid();
                bytes += payload.textByteCount;
            }
        }
        if (bytes != byteLimit) throw EvidenceStorageException.invalid();
        return payloads;
    }
    private static String optionalText(Cursor row, int column, int limit) { return row.isNull(column) ? null : text(row, column, limit); }
    private static Integer optionalInt(Cursor row, int column) {
        if (row.isNull(column)) return null;
        long value = integer(row, column);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw EvidenceStorageException.invalid();
        return (int) value;
    }
    private static <T extends Enum<T>> T optionalEnum(Cursor row, int column, T[] values) { return row.isNull(column) ? null : parse(values, text(row, column, 32)); }
    private static Long duration(Cursor row, int column) {
        String value = optionalText(row, column, 20);
        if (value == null) return null;
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0 || !value.equals(Long.toString(parsed))) throw EvidenceStorageException.invalid();
            return parsed;
        } catch (NumberFormatException failure) { throw EvidenceStorageException.invalid(); }
    }
    private Cursor query(String sql, String... args) { return database.rawQuery(sql, args); }
}
