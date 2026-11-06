import Foundation
import OsservioCore

/// Normalized detail attachments on the incident store's existing serial connection and transaction.
final class IncidentDetailDatabase {
    enum Kind: String { case logs, http, websocket }
    struct Window {
        let observedAt: Int64
        let count: Int
        let sourceCount: Int
        let evicted: Int64
        let evictedPayloads: Int64?
        let bytes: Int
        let payloadBytes: Int
    }
    struct Detail {
        let logs: IncidentLogs?
        let http: IncidentHTTP?
        let webSockets: IncidentWebSockets?
    }
    private let connection: SQLiteConnection
    init(connection: SQLiteConnection) { self.connection = connection }

    static let schema = [
        """
        CREATE TABLE incident_windows (incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
            kind TEXT NOT NULL, observed_at INTEGER NOT NULL, event_count INTEGER NOT NULL, source_count INTEGER NOT NULL,
            source_evicted INTEGER NOT NULL, payload_evicted INTEGER, text_bytes INTEGER NOT NULL, payload_bytes INTEGER NOT NULL,
            PRIMARY KEY(incident_id, kind))
        """,
        """
        CREATE TABLE incident_logs (incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
            position INTEGER NOT NULL, session_id TEXT NOT NULL COLLATE NOCASE, sequence INTEGER NOT NULL,
            timestamp INTEGER NOT NULL, level TEXT NOT NULL, category TEXT NOT NULL, message TEXT NOT NULL,
            private INTEGER NOT NULL, truncated INTEGER NOT NULL, PRIMARY KEY(incident_id, position), UNIQUE(incident_id, session_id, sequence))
        """,
        """
        CREATE TABLE incident_http (incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
            position INTEGER NOT NULL, id TEXT NOT NULL COLLATE NOCASE, session_id TEXT NOT NULL, sequence INTEGER NOT NULL,
            revision INTEGER NOT NULL, timestamp INTEGER NOT NULL, method TEXT NOT NULL, source TEXT NOT NULL,
            url TEXT NOT NULL, response_url TEXT, headers_limited INTEGER NOT NULL, status_code INTEGER, state TEXT NOT NULL,
            failure TEXT, duration TEXT, request_state TEXT NOT NULL, request_blob TEXT, request_bytes INTEGER NOT NULL,
            response_state TEXT NOT NULL, response_blob TEXT, response_bytes INTEGER NOT NULL,
            PRIMARY KEY(incident_id, position), UNIQUE(incident_id, id))
        """,
        """
        CREATE TABLE incident_websocket (incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
            position INTEGER NOT NULL, id TEXT NOT NULL COLLATE NOCASE, connection_id TEXT NOT NULL, session_id TEXT NOT NULL,
            sequence INTEGER NOT NULL, timestamp INTEGER NOT NULL, url TEXT NOT NULL, source TEXT NOT NULL, kind TEXT NOT NULL,
            headers_limited INTEGER NOT NULL, status_code INTEGER, close_code INTEGER, failure TEXT, direction TEXT,
            outcome TEXT, related_message TEXT, payload_state TEXT, payload_blob TEXT, payload_count INTEGER,
            PRIMARY KEY(incident_id, position), UNIQUE(incident_id, id))
        """,
        """
        CREATE TABLE incident_headers (incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
            family TEXT NOT NULL, event_position INTEGER NOT NULL, scope TEXT NOT NULL, position INTEGER NOT NULL,
            name TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(incident_id, family, event_position, scope, position))
        """,
        """
        CREATE TABLE incident_payloads (incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
            family TEXT NOT NULL, id TEXT NOT NULL COLLATE NOCASE, state TEXT NOT NULL, text TEXT,
            PRIMARY KEY(incident_id, family, id))
        """
    ]
    func save(_ snapshot: IncidentSnapshot) throws {
        let id = snapshot.id
        if let logs = snapshot.logs {
            try saveWindow(id, .logs, time: logs.observedAtMillis, count: logs.events.count, source: logs.sourceEventCount,
                evicted: logs.sourceEvictedCount, payloadEvicted: nil, bytes: logs.retainedTextBytes, payloadBytes: 0)
            for (position, event) in logs.events.enumerated() {
                try connection.execute("""
                    INSERT INTO incident_logs(incident_id, position, session_id, sequence, timestamp, level, category, message, private, truncated)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, [.text(id), .integer(Int64(position)), .text(event.sessionID), .integer(event.sequence), .integer(event.timestampMillis),
                             .text(event.level.rawValue), .text(event.category), .text(event.message), .integer(event.isPrivate ? 1 : 0), .integer(event.isTruncated ? 1 : 0)])
            }
        }
        if let http = snapshot.http {
            try saveWindow(id, .http, time: http.observedAtMillis, count: http.events.count, source: http.sourceEventCount,
                evicted: http.sourceEvictedEvents, payloadEvicted: http.sourceEvictedBodies, bytes: http.retainedTextBytes, payloadBytes: http.retainedPayloadBytes)
            for (position, event) in http.events.enumerated() {
                try connection.execute("""
                    INSERT INTO incident_http(incident_id, position, id, session_id, sequence, revision, timestamp, method, source,
                        url, response_url, headers_limited, status_code, state, failure, duration, request_state, request_blob,
                        request_bytes, response_state, response_blob, response_bytes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, [.text(id), .integer(Int64(position)), .text(event.id), .text(event.sessionID), .integer(event.sequence), .integer(event.revision),
                             .integer(event.timestampMillis), .text(event.method), .text(event.source.rawValue), .text(event.url), optional(event.responseURL),
                             .integer(event.headersLimited ? 1 : 0), event.statusCode.map { .integer(Int64($0)) } ?? .null, .text(event.state.rawValue),
                             optional(event.failure?.rawValue), optional(event.durationMillis.map(String.init)), .text(event.requestBody.state.rawValue),
                             optional(event.requestBody.blobID), .integer(event.requestBody.observedBytes), .text(event.responseBody.state.rawValue),
                             optional(event.responseBody.blobID), .integer(event.responseBody.observedBytes)])
                try saveHeaders(id, .http, position, "request", event.requestHeaders)
                try saveHeaders(id, .http, position, "response", event.responseHeaders)
            }
            try savePayloads(id, .http, http.payloads)
        }
        if let sockets = snapshot.webSockets {
            try saveWindow(id, .websocket, time: sockets.observedAtMillis, count: sockets.events.count, source: sockets.sourceEventCount,
                evicted: sockets.sourceEvictedEvents, payloadEvicted: sockets.sourceEvictedPayloads, bytes: sockets.retainedTextBytes, payloadBytes: sockets.retainedPayloadBytes)
            for (position, event) in sockets.events.enumerated() {
                try connection.execute("""
                    INSERT INTO incident_websocket(incident_id, position, id, connection_id, session_id, sequence, timestamp, url, source,
                        kind, headers_limited, status_code, close_code, failure, direction, outcome, related_message, payload_state, payload_blob, payload_count)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, [.text(id), .integer(Int64(position)), .text(event.id), .text(event.connectionID), .text(event.sessionID), .integer(event.sequence),
                             .integer(event.timestampMillis), .text(event.url), .text(event.source.rawValue), .text(event.kind.rawValue), .integer(event.headersLimited ? 1 : 0),
                             event.statusCode.map { .integer(Int64($0)) } ?? .null, event.closeCode.map { .integer(Int64($0)) } ?? .null,
                             optional(event.failure?.rawValue), optional(event.direction?.rawValue), optional(event.outcome?.rawValue), optional(event.relatedMessageID),
                             optional(event.payload?.state.rawValue), optional(event.payload?.blobID), event.payload?.byteCount.map(SQLiteValue.integer) ?? .null])
                try saveHeaders(id, .websocket, position, "event", event.headers)
            }
            try savePayloads(id, .websocket, sockets.payloads)
        }
    }
    private func optional(_ value: String?) -> SQLiteValue { value.map(SQLiteValue.text) ?? .null }
    private func saveWindow(_ id: String, _ kind: Kind, time: Int64, count: Int, source: Int, evicted: Int64, payloadEvicted: Int64?, bytes: Int, payloadBytes: Int) throws {
        try connection.execute("""
            INSERT INTO incident_windows(incident_id, kind, observed_at, event_count, source_count, source_evicted, payload_evicted, text_bytes, payload_bytes)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, [.text(id), .text(kind.rawValue), .integer(time), .integer(Int64(count)), .integer(Int64(source)), .integer(evicted),
                     payloadEvicted.map(SQLiteValue.integer) ?? .null, .integer(Int64(bytes)), .integer(Int64(payloadBytes))])
    }
    private func saveHeaders(_ id: String, _ kind: Kind, _ eventPosition: Int, _ scope: String, _ headers: [HTTPHeader]) throws {
        for (position, header) in headers.enumerated() {
            try connection.execute("INSERT INTO incident_headers(incident_id, family, event_position, scope, position, name, value) VALUES (?, ?, ?, ?, ?, ?, ?)",
                [.text(id), .text(kind.rawValue), .integer(Int64(eventPosition)), .text(scope), .integer(Int64(position)), .text(header.name), .text(header.value)])
        }
    }
    private func savePayloads(_ id: String, _ kind: Kind, _ payloads: [String: IncidentPayload]) throws {
        for payload in payloads.values {
            try connection.execute("INSERT INTO incident_payloads(incident_id, family, id, state, text) VALUES (?, ?, ?, ?, ?)",
                [.text(id), .text(kind.rawValue), .text(payload.id), .text(payload.state.rawValue), optional(payload.text)])
        }
    }
    func windows(id: String) throws -> [Kind: Window] {
        try connection.statement("SELECT kind, observed_at, event_count, source_count, source_evicted, payload_evicted, text_bytes, payload_bytes FROM incident_windows WHERE incident_id=? LIMIT 4", [.text(id)]) { row in
            var windows: [Kind: Window] = [:]
            while try row.step() {
                guard let kind = Kind(rawValue: try row.text(0, byteLimit: 16)), windows[kind] == nil else { throw PersistenceError.invalidRecord }
                let count = try row.integer(2), source = try row.integer(3), evicted = try row.integer(4)
                let payloadEvicted = try row.isNull(5) ? nil : row.integer(5), bytes = try row.integer(6), payloadBytes = try row.integer(7)
                guard (0...2_000).contains(count), source >= count, source <= Int32.max, evicted >= 0,
                      (0...1_048_576).contains(bytes), (0...262_144).contains(payloadBytes),
                      kind == .logs ? (payloadEvicted == nil && payloadBytes == 0) : (payloadEvicted != nil && payloadEvicted! >= 0) else { throw PersistenceError.invalidRecord }
                windows[kind] = try Window(observedAt: row.integer(1), count: Int(count), sourceCount: Int(source), evicted: evicted,
                    evictedPayloads: payloadEvicted, bytes: Int(bytes), payloadBytes: Int(payloadBytes))
            }
            return windows
        }
    }
    func read(id: String, windows: [Kind: Window]) throws -> Detail {
        var headerRows = 0, payloadRows = 0
        let logs = try readLogs(id, windows[.logs])
        let http = try readHTTP(id, windows[.http], headerRows: &headerRows, payloadRows: &payloadRows)
        let sockets = try readWebSockets(id, windows[.websocket], headerRows: &headerRows, payloadRows: &payloadRows)
        // Reject rows with unknown families or references to events that are not in the declared windows.
        for (table, expected) in [("incident_headers", headerRows), ("incident_payloads", payloadRows)] {
            try connection.statement("SELECT count(*) FROM \(table) WHERE incident_id=?", [.text(id)]) { row in
                guard try row.step(), try row.integer(0) == expected else { throw PersistenceError.invalidRecord }
            }
        }
        return Detail(logs: logs, http: http, webSockets: sockets)
    }
    private func readLogs(_ id: String, _ window: Window?) throws -> IncidentLogs? {
        var events: [LogEvent] = [], bytes = 0
        try connection.statement("SELECT position, session_id, sequence, timestamp, level, category, message, private, truncated FROM incident_logs WHERE incident_id=? ORDER BY position LIMIT 2001", [.text(id)]) { row in
            while try row.step() {
                guard let window, events.count < window.count, try row.integer(0) == events.count,
                      let level = LogLevel(rawValue: try row.text(4, byteLimit: 16)) else { throw PersistenceError.invalidRecord }
                let event = try LogEvent.restore(sessionID: row.text(1, byteLimit: 36), sequence: row.integer(2), timestampMillis: row.integer(3),
                    level: level, category: row.text(5, byteLimit: 64), message: row.text(6, byteLimit: 65_536), isPrivate: row.flag(7), isTruncated: row.flag(8))
                guard event.textByteCount <= window.bytes - bytes else { throw PersistenceError.invalidRecord }
                bytes += event.textByteCount; events.append(event)
            }
        }
        guard let window else { return nil }
        guard events.count == window.count, bytes == window.bytes else { throw PersistenceError.invalidRecord }
        return try IncidentLogs.restore(events: events, observedAtMillis: window.observedAt, sourceEventCount: window.sourceCount, sourceEvictedCount: window.evicted)
    }
    private func readHTTP(_ id: String, _ window: Window?, headerRows: inout Int, payloadRows: inout Int) throws -> IncidentHTTP? {
        var events: [HTTPEvent] = [], bytes = 0
        try connection.statement("""
            SELECT position, id, session_id, sequence, revision, timestamp, method, source, url, response_url,
                headers_limited, status_code, state, failure, duration, request_state, request_blob, request_bytes,
                response_state, response_blob, response_bytes FROM incident_http WHERE incident_id=? ORDER BY position LIMIT 2001
            """, [.text(id)]) { row in
            while try row.step() {
                guard let window, events.count < window.count, try row.integer(0) == events.count else { throw PersistenceError.invalidRecord }
                let headers = try readHeaders(id, .http, position: events.count, scopes: ["request", "response"], total: &headerRows)
                let request = try HTTPBodyReference.restore(state: required(row, 15), blobID: text(row, 16, 45), observedBytes: row.integer(17))
                let response = try HTTPBodyReference.restore(state: required(row, 18), blobID: text(row, 19, 45), observedBytes: row.integer(20))
                let event = try HTTPEvent.restore(id: row.text(1, byteLimit: 36), sessionID: row.text(2, byteLimit: 36), sequence: row.integer(3),
                    revision: row.integer(4), timestampMillis: row.integer(5), method: row.text(6, byteLimit: 16), source: required(row, 7),
                    url: row.text(8, byteLimit: 4_128), responseURL: text(row, 9, 4_128), requestHeaders: headers["request"] ?? [], responseHeaders: headers["response"] ?? [],
                    headersLimited: row.flag(10), statusCode: number(row, 11), state: required(row, 12), failure: enumeration(row, 13), durationMillis: duration(row, 14),
                    requestBody: request, responseBody: response)
                guard event.textByteCount <= window.bytes - bytes else { throw PersistenceError.invalidRecord }
                bytes += event.textByteCount; events.append(event)
            }
        }
        let payloads = try readPayloads(id, .http, maximumCount: (window?.count ?? 0) * 2, byteLimit: window?.payloadBytes ?? 0)
        payloadRows += payloads.count
        guard let window else { return nil }
        guard events.count == window.count, bytes == window.bytes, let evictedPayloads = window.evictedPayloads else { throw PersistenceError.invalidRecord }
        return try IncidentHTTP.restore(events: events, payloads: payloads, observedAtMillis: window.observedAt,
            sourceEventCount: window.sourceCount, sourceEvictedEvents: window.evicted, sourceEvictedBodies: evictedPayloads)
    }
    private func readWebSockets(_ id: String, _ window: Window?, headerRows: inout Int, payloadRows: inout Int) throws -> IncidentWebSockets? {
        var events: [WebSocketEvent] = [], bytes = 0
        try connection.statement("""
            SELECT position, id, connection_id, session_id, sequence, timestamp, url, source, kind, headers_limited,
                status_code, close_code, failure, direction, outcome, related_message, payload_state, payload_blob, payload_count
            FROM incident_websocket WHERE incident_id=? ORDER BY position LIMIT 2001
            """, [.text(id)]) { row in
            while try row.step() {
                guard let window, events.count < window.count, try row.integer(0) == events.count else { throw PersistenceError.invalidRecord }
                let headers = try readHeaders(id, .websocket, position: events.count, scopes: ["event"], total: &headerRows)
                let payload: WebSocketPayloadReference?
                if row.isNull(16) { guard row.isNull(17), row.isNull(18) else { throw PersistenceError.invalidRecord }; payload = nil }
                else { payload = try .restore(state: required(row, 16), blobID: text(row, 17, 36), byteCount: row.isNull(18) ? nil : row.integer(18)) }
                let event = try WebSocketEvent.restore(id: row.text(1, byteLimit: 36), connectionID: row.text(2, byteLimit: 36), sessionID: row.text(3, byteLimit: 36),
                    sequence: row.integer(4), timestampMillis: row.integer(5), url: row.text(6, byteLimit: 4_128), source: required(row, 7), kind: required(row, 8),
                    headers: headers["event"] ?? [], headersLimited: row.flag(9), statusCode: number(row, 10), closeCode: number(row, 11),
                    failure: enumeration(row, 12), direction: enumeration(row, 13), outcome: enumeration(row, 14), relatedMessageID: text(row, 15, 36), payload: payload)
                guard event.textByteCount <= window.bytes - bytes else { throw PersistenceError.invalidRecord }
                bytes += event.textByteCount; events.append(event)
            }
        }
        let payloads = try readPayloads(id, .websocket, maximumCount: window?.count ?? 0, byteLimit: window?.payloadBytes ?? 0)
        payloadRows += payloads.count
        guard let window else { return nil }
        guard events.count == window.count, bytes == window.bytes, let evictedPayloads = window.evictedPayloads else { throw PersistenceError.invalidRecord }
        return try IncidentWebSockets.restore(events: events, payloads: payloads, observedAtMillis: window.observedAt,
            sourceEventCount: window.sourceCount, sourceEvictedEvents: window.evicted, sourceEvictedPayloads: evictedPayloads)
    }
    private func readHeaders(_ id: String, _ kind: Kind, position: Int, scopes: Set<String>, total: inout Int) throws -> [String: [HTTPHeader]] {
        try connection.statement("SELECT scope, position, name, value FROM incident_headers WHERE incident_id=? AND family=? AND event_position=? ORDER BY scope, position LIMIT 65",
            [.text(id), .text(kind.rawValue), .integer(Int64(position))]) { row in
                var headers: [String: [HTTPHeader]] = [:]
                while try row.step() {
                    let scope = try row.text(0, byteLimit: 16)
                    let count = headers[scope]?.count ?? 0
                    guard scopes.contains(scope), count < 32, try row.integer(1) == count else { throw PersistenceError.invalidRecord }
                    headers[scope, default: []].append(try HTTPHeader(name: row.text(2, byteLimit: 64), value: row.text(3, byteLimit: 512)))
                    total += 1
                }
                return headers
            }
    }
    private func readPayloads(_ id: String, _ kind: Kind, maximumCount: Int, byteLimit: Int) throws -> [String: IncidentPayload] {
        try connection.statement("SELECT id, state, text FROM incident_payloads WHERE incident_id=? AND family=? LIMIT ?",
            [.text(id), .text(kind.rawValue), .integer(Int64(maximumCount + 1))]) { row in
                var payloads: [String: IncidentPayload] = [:], bytes = 0
                while try row.step() {
                    guard payloads.count < maximumCount else { throw PersistenceError.invalidRecord }
                    let payload = try IncidentPayload.restore(id: row.text(0, byteLimit: 45), state: required(row, 1), text: text(row, 2, min(65_536, byteLimit - bytes)))
                    guard payloads.updateValue(payload, forKey: payload.id) == nil else { throw PersistenceError.invalidRecord }
                    bytes += payload.textByteCount
                }
                guard bytes == byteLimit else { throw PersistenceError.invalidRecord }
                return payloads
            }
    }
    private func text(_ row: SQLiteStatement, _ column: Int32, _ limit: Int) throws -> String? { try row.isNull(column) ? nil : row.text(column, byteLimit: limit) }
    private func number(_ row: SQLiteStatement, _ column: Int32) throws -> Int? {
        guard !row.isNull(column) else { return nil }
        guard let value = Int(exactly: try row.integer(column)) else { throw PersistenceError.invalidRecord }; return value
    }
    private func required<T: RawRepresentable>(_ row: SQLiteStatement, _ column: Int32) throws -> T where T.RawValue == String {
        guard let value = T(rawValue: try row.text(column, byteLimit: 32)) else { throw PersistenceError.invalidRecord }; return value
    }
    private func enumeration<T: RawRepresentable>(_ row: SQLiteStatement, _ column: Int32) throws -> T? where T.RawValue == String { try row.isNull(column) ? nil : required(row, column) }
    private func duration(_ row: SQLiteStatement, _ column: Int32) throws -> UInt64? {
        guard let value = try text(row, column, 20) else { return nil }
        guard let parsed = UInt64(value), String(parsed) == value else { throw PersistenceError.invalidRecord }; return parsed
    }
}
