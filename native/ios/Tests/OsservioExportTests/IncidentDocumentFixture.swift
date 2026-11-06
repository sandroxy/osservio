import Foundation
import OsservioCore

enum IncidentDocumentFixture {
    static let id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    static let session = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    static let httpID = "dddddddd-dddd-dddd-dddd-dddddddddddd"
    static func make(_ name: String) throws -> IncidentSnapshot {
        if name == "absent" { return try .restore(id: id, timestampMillis: .min, note: nil, environment: nil, breadcrumbs: nil) }
        if name == "empty" { return try .restore(id: id, timestampMillis: .min, note: nil, environment: nil, breadcrumbs: nil,
            logs: .restore(events: [], observedAtMillis: 0, sourceEventCount: 4, sourceEvictedCount: 2)) }
        precondition(name == "complete")
        var facts = Dictionary(uniqueKeysWithValues: EnvironmentKey.allCases.map { ($0, EnvironmentFact.unavailable) })
        facts[.appIdentifier] = .text("com.example.fixture"); facts[.appVersion] = .text("测试1")
        facts[.physicalMemoryBytes] = .number(9_007_199_254_740_993); facts[.processID] = .number(7)
        facts[.lowPowerMode] = .flag(false); facts[.thermalState] = .unsupported
        let environment = try EnvironmentSnapshot.restore(platform: .android, publicFacts: facts, truncatedKeys: [.appVersion], timestampMillis: 123)
        let crumb = try BreadcrumbEvent.restore(id: "cccccccc-cccc-cccc-cccc-cccccccccccc", sessionID: session, sequence: 9_007_199_254_740_993,
            timestampMillis: .min, sessionElapsedMillis: UInt64(Int64.max), kind: .hostMarker, label: "fixture.private", message: "[private]",
            isPrivate: true, isTruncated: false, value: .min, retention: .restartRelevant)
        let log = try LogEvent.restore(sessionID: session, sequence: 9_007_199_254_740_993, timestampMillis: .max, level: .error,
            category: "fixture", message: "Quote\"\\\n汉😀\0", isPrivate: false, isTruncated: true)
        let privateLog = try LogEvent.restore(sessionID: session, sequence: 9_007_199_254_740_994, timestampMillis: .max, level: .warning,
            category: "fixture", message: "[private]", isPrivate: true, isTruncated: false)
        let url = "https://example.com/frozen?[redacted]"
        let http = try HTTPEvent.restore(id: httpID, sessionID: session, sequence: 9_007_199_254_740_993, revision: 2, timestampMillis: 123,
            method: "POST", source: .okHttp, url: url, responseURL: url, requestHeaders: [HTTPHeader(name: "authorization", value: "[redacted]")],
            responseHeaders: [HTTPHeader(name: "content-type", value: "text/plain")], headersLimited: true, statusCode: 200, state: .completed,
            failure: nil, durationMillis: UInt64(Int64.max), requestBody: .restore(state: .available, blobID: httpID + ".request", observedBytes: 0),
            responseBody: .restore(state: .available, blobID: httpID + ".response", observedBytes: 3))
        let bodies: [String: IncidentPayload] = [httpID + ".request": try .restore(id: httpID + ".request", state: .available, text: ""),
            httpID + ".response": try .restore(id: httpID + ".response", state: .available, text: "汉")]
        var events: [WebSocketEvent] = [], payloads: [String: IncidentPayload] = [:]
        let states: [IncidentPayloadState] = [.notSelected, .unavailable, .busy, .budgetExceeded, .available, .available]
        for (offset, state) in states.enumerated() {
            let id = "eeeeeeee-eeee-eeee-eeee-00000000000\(offset + 1)"
            let size: Int64 = offset == 4 ? 0 : offset == 5 ? 4 : 3
            events.append(try WebSocketEvent.restore(id: id, connectionID: session, sessionID: session, sequence: Int64(offset + 1), timestampMillis: 124,
                url: "wss://example.com/socket?[redacted]", source: .urlSession, kind: .message, headers: [], headersLimited: false,
                statusCode: nil, closeCode: nil, failure: nil, direction: .outgoing, outcome: .submitted, relatedMessageID: nil,
                payload: .restore(state: .available, blobID: id, byteCount: size)))
            payloads[id] = try .restore(id: id, state: state, text: state == .available ? (offset == 4 ? "" : "😀") : nil)
        }
        events.append(try WebSocketEvent.restore(id: "eeeeeeee-eeee-eeee-eeee-000000000007", connectionID: session, sessionID: session, sequence: 7,
            timestampMillis: 124, url: "wss://example.com/socket?[redacted]", source: .urlSession, kind: .messageResult, headers: [], headersLimited: false,
            statusCode: nil, closeCode: nil, failure: nil, direction: .outgoing, outcome: .sent, relatedMessageID: events.last?.id, payload: nil))
        return try IncidentSnapshot.restore(id: id, timestampMillis: .min, note: .capture(.privateText("synthetic-secret")), environment: environment,
            breadcrumbs: .restore(events: [crumb], sourceEventCount: 3, sourceEvictedCount: .max),
            logs: .restore(events: [log, privateLog], observedAtMillis: 456, sourceEventCount: 3, sourceEvictedCount: 9),
            http: .restore(events: [http], payloads: bodies, observedAtMillis: 457, sourceEventCount: 3, sourceEvictedEvents: 5, sourceEvictedBodies: 7),
            webSockets: .restore(events: events, payloads: payloads, observedAtMillis: 458, sourceEventCount: 9, sourceEvictedEvents: 5, sourceEvictedPayloads: 7))
    }
}
