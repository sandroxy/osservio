import Foundation
import OsservioCore

enum IncidentDetailFixture {
    static func id(_ value: Int) -> String { String(format: "00000000-0000-0000-0000-%012d", value) }
    static let body = "public\u{0}例😀"
    static func snapshot() throws -> IncidentSnapshot {
        let logs = try IncidentLogs.restore(events: [
            LogEvent.restore(sessionID: id(1), sequence: Int64.max - 1, timestampMillis: Int64.min, level: .error,
                category: "fixture", message: "[private]", isPrivate: true, isTruncated: false),
            LogEvent.restore(sessionID: id(1), sequence: Int64.max, timestampMillis: 123, level: .warning,
                category: "fixture", message: body, isPrivate: false, isTruncated: true)
        ], observedAtMillis: -1, sourceEventCount: 5, sourceEvictedCount: 9)
        var events: [HTTPEvent] = [], payloads: [String: IncidentPayload] = [:]
        for (position, state) in [IncidentPayloadState.available, .notSelected, .unavailable, .busy, .budgetExceeded].enumerated() {
            let eventID = id(10 + position), requestID = eventID + ".request", responseID = eventID + ".response"
            let first = position == 0
            events.append(try HTTPEvent.restore(id: eventID, sessionID: id(1), sequence: Int64(position + 1), revision: 2,
                timestampMillis: Int64.min + Int64(position), method: "POST", source: first ? .okHttp : .urlSession,
                url: "https://example.com/path?[redacted]", responseURL: "https://example.com/result",
                requestHeaders: first ? [HTTPHeader(name: "authorization", value: "[redacted]"), HTTPHeader(name: "x-fixture", value: "例\u{0}")] : [],
                responseHeaders: first ? [HTTPHeader(name: "content-type", value: "text/plain")] : [], headersLimited: first,
                statusCode: 201, state: .completed, failure: nil, durationMillis: UInt64.max,
                requestBody: .restore(state: first ? .available : .omitted, blobID: first ? requestID : nil, observedBytes: 0),
                responseBody: .restore(state: .available, blobID: responseID, observedBytes: first ? Int64(body.utf8.count) : 1)))
            if first { payloads[requestID] = try .restore(id: requestID, state: .available, text: "") }
            payloads[responseID] = try .restore(id: responseID, state: state, text: first ? body : nil)
        }
        let http = try IncidentHTTP.restore(events: events, payloads: payloads, observedAtMillis: -2,
            sourceEventCount: 7, sourceEvictedEvents: 11, sourceEvictedBodies: 13)
        let sockets = try IncidentWebSockets.restore(events: [
            WebSocketEvent.restore(id: id(20), connectionID: id(2), sessionID: id(1), sequence: 20, timestampMillis: 20,
                url: "wss://example.com", source: .urlSession, kind: .opened, headers: [HTTPHeader(name: "authorization", value: "[redacted]")],
                headersLimited: true, statusCode: 101, closeCode: nil, failure: nil, direction: nil, outcome: nil, relatedMessageID: nil, payload: nil),
            WebSocketEvent.restore(id: id(21), connectionID: id(2), sessionID: id(1), sequence: 21, timestampMillis: 21,
                url: "wss://example.com", source: .urlSession, kind: .message, headers: [], headersLimited: false, statusCode: nil, closeCode: nil,
                failure: nil, direction: .outgoing, outcome: .submitted, relatedMessageID: nil, payload: .restore(state: .available, blobID: id(21), byteCount: 0)),
            WebSocketEvent.restore(id: id(22), connectionID: id(2), sessionID: id(1), sequence: 22, timestampMillis: 22,
                url: "wss://example.com", source: .urlSession, kind: .messageResult, headers: [], headersLimited: false, statusCode: nil, closeCode: nil,
                failure: nil, direction: .outgoing, outcome: .sent, relatedMessageID: id(21), payload: nil),
            WebSocketEvent.restore(id: id(23), connectionID: id(2), sessionID: id(1), sequence: 23, timestampMillis: 23,
                url: "wss://example.com", source: .okHttp, kind: .message, headers: [], headersLimited: false, statusCode: nil, closeCode: nil,
                failure: nil, direction: .incoming, outcome: .received, relatedMessageID: nil, payload: .restore(state: .binary, blobID: nil, byteCount: Int64.max)),
            WebSocketEvent.restore(id: id(24), connectionID: id(2), sessionID: id(1), sequence: 24, timestampMillis: 24,
                url: "wss://example.com", source: .host, kind: .message, headers: [], headersLimited: false, statusCode: nil, closeCode: nil,
                failure: nil, direction: .incoming, outcome: .received, relatedMessageID: nil, payload: .restore(state: .truncated, blobID: id(24), byteCount: nil))
        ], payloads: [id(21): .restore(id: id(21), state: .available, text: ""), id(24): .restore(id: id(24), state: .available, text: "例")],
            observedAtMillis: -3, sourceEventCount: 8, sourceEvictedEvents: 17, sourceEvictedPayloads: 19)
        return try IncidentSnapshot.capture(note: .privateText("never written"), logs: logs, http: http, webSockets: sockets)
    }
    static func shared(_ name: String) throws -> String {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        return try String(contentsOf: root.appendingPathComponent("testing/" + name), encoding: .utf8)
    }
}
