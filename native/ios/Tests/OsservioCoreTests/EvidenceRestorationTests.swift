import Foundation
import OsservioCore
import OsservioStore
import XCTest

final class EvidenceRestorationTests: XCTestCase {
    private static let id = "12345678-1234-1234-1234-123456789abc"
    private static let other = "abcdefab-1234-1234-1234-123456789abc"

    func testSharedRestorationBoundaries() throws {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let fixture = try String(contentsOf: root.appendingPathComponent("testing/evidence-restoration.tsv"), encoding: .utf8)
        for line in fixture.split(separator: "\n") {
            let row = line.split(separator: "\t").map(String.init)
            XCTAssertEqual(row.count, 5)
            do {
                switch row[1] {
                case "log": _ = try Self.log(field: row[2], value: row[3])
                case "http": _ = try Self.http(field: row[2], value: row[3])
                case "websocket": _ = try Self.socket(field: row[2], value: row[3])
                default: XCTFail("Unknown fixture family: " + row[1]); continue
                }
                XCTAssertEqual(row[4], "true", row[0])
            } catch { XCTAssertEqual(row[4], "false", row[0] + ": " + String(describing: error)) }
        }
    }
    func testPrivateLogsAndExactIdentitySurviveRestoration() throws {
        let event = try LogEvent.restore(sessionID: Self.id, sequence: Int64.max, timestampMillis: Int64.min,
            level: .error, category: "test", message: "[private]", isPrivate: true, isTruncated: false)
        XCTAssertEqual(event.sequence, Int64.max); XCTAssertEqual(event.timestampMillis, Int64.min)
        XCTAssertEqual(event.sessionID, Self.id); XCTAssertEqual(event.message, "[private]"); XCTAssertTrue(event.isPrivate)
        XCTAssertThrowsError(try LogEvent.restore(sessionID: Self.id, sequence: 1, timestampMillis: 0,
            level: .error, category: "test", message: "[private]", isPrivate: true, isTruncated: true))
    }
    func testBodyIdentityBoundsAndReferencesCannotInventContent() throws {
        let text = String(repeating: "😀", count: 16_384)
        let body = try HTTPBody.restore(id: Self.id + ".response", text: text)
        XCTAssertEqual(body.byteCount, 65_536); XCTAssertEqual(body.text, text)
        XCTAssertThrowsError(try HTTPBody.restore(id: Self.id + ".response", text: text + "x"))
        XCTAssertThrowsError(try HTTPBody.restore(id: Self.id + "..request", text: ""))
        XCTAssertThrowsError(try HTTPBodyReference.restore(state: .omitted, blobID: body.id, observedBytes: 65_536))
        XCTAssertThrowsError(try HTTPBodyReference.restore(state: .available, blobID: nil, observedBytes: 1))
        XCTAssertThrowsError(try HTTPBodyReference.restore(state: .available, blobID: body.id, observedBytes: 65_537))
        XCTAssertThrowsError(try HTTPBodyReference.restore(state: .pending, blobID: nil, observedBytes: 1))
        XCTAssertThrowsError(try HTTPBodyReference.restore(state: .truncated, blobID: body.id, observedBytes: 0))
        let payload = try WebSocketPayload.restore(id: Self.id, text: text)
        XCTAssertEqual(payload.byteCount, 65_536)
        XCTAssertThrowsError(try WebSocketPayload.restore(id: Self.id, text: text + "x"))
        XCTAssertThrowsError(try WebSocketPayloadReference.restore(state: .binary, blobID: Self.id, byteCount: 1))
        XCTAssertThrowsError(try WebSocketPayloadReference.restore(state: .truncated, blobID: Self.id, byteCount: 9))
        XCTAssertThrowsError(try WebSocketPayloadReference.restore(state: .available, blobID: Self.id, byteCount: nil))
        XCTAssertThrowsError(try WebSocketPayloadReference.restore(state: .omitted, blobID: nil, byteCount: 9))
        let empty = try WebSocketPayloadReference.restore(state: .available, blobID: Self.id, byteCount: 0)
        XCTAssertEqual(empty.byteCount, 0)
    }

    func testRecordedHTTPStatesRestoreWithoutInventingTransportResults() throws {
        for terminal: HTTPState in [.completed, .failed, .cancelled, .closedEarly, .captureEnded] {
            for received in [false, true] where received || terminal != .completed {
                let capture = CaptureController(), history = try MemoryHTTPStore()
                try capture.startDetailed()
                let recorder = try HTTPRecorder(sink: history, captureController: capture, privacy: HTTPPrivacyPolicy(bodyPolicy: .publicUTF8))
                let token = try XCTUnwrap(recorder.begin(method: "GET", url: "https://example.com").token)
                try Self.check(XCTUnwrap(history.snapshot()?.events.first))
                if received {
                    XCTAssertEqual(recorder.receiveResponse(token, statusCode: 503, headers: [HTTPHeader(name: "Content-Type", value: "text/plain")]), .recorded)
                    try Self.check(XCTUnwrap(history.snapshot()?.events.first))
                }
                if terminal == .captureEnded { XCTAssertEqual(recorder.stopObserving(token), .recorded) }
                else { XCTAssertEqual(recorder.finish(token, state: terminal, failure: terminal == .failed ? .connection : nil), .recorded) }
                let event = try XCTUnwrap(history.snapshot()?.events.first)
                try Self.check(event); XCTAssertEqual(event.state, terminal)
                XCTAssertEqual(event.statusCode, received ? 503 : nil)
            }
        }
    }

    static func check(_ event: HTTPEvent) throws {
        let request = try HTTPBodyReference.restore(state: event.requestBody.state, blobID: event.requestBody.blobID, observedBytes: event.requestBody.observedBytes)
        let response = try HTTPBodyReference.restore(state: event.responseBody.state, blobID: event.responseBody.blobID, observedBytes: event.responseBody.observedBytes)
        let value = try HTTPEvent.restore(id: event.id, sessionID: event.sessionID, sequence: event.sequence, revision: event.revision,
            timestampMillis: event.timestampMillis, method: event.method, source: event.source, url: event.url, responseURL: event.responseURL,
            requestHeaders: event.requestHeaders, responseHeaders: event.responseHeaders, headersLimited: event.headersLimited,
            statusCode: event.statusCode, state: event.state, failure: event.failure, durationMillis: event.durationMillis,
            requestBody: request, responseBody: response)
        XCTAssertEqual(value.id, event.id); XCTAssertEqual(value.sessionID, event.sessionID); XCTAssertEqual(value.sequence, event.sequence)
        XCTAssertEqual(value.revision, event.revision); XCTAssertEqual(value.timestampMillis, event.timestampMillis)
        XCTAssertEqual(value.requestHeaders, event.requestHeaders); XCTAssertEqual(value.responseHeaders, event.responseHeaders)
        XCTAssertEqual(value.requestBody.blobID, event.requestBody.blobID); XCTAssertEqual(value.responseBody.blobID, event.responseBody.blobID)
        XCTAssertEqual(value.textByteCount, event.textByteCount); XCTAssertEqual(value.durationMillis, event.durationMillis)
    }
    static func check(_ event: WebSocketEvent) throws {
        let payload = try event.payload.map { try WebSocketPayloadReference.restore(state: $0.state, blobID: $0.blobID, byteCount: $0.byteCount) }
        let value = try WebSocketEvent.restore(id: event.id, connectionID: event.connectionID, sessionID: event.sessionID, sequence: event.sequence,
            timestampMillis: event.timestampMillis, url: event.url, source: event.source, kind: event.kind, headers: event.headers,
            headersLimited: event.headersLimited, statusCode: event.statusCode, closeCode: event.closeCode, failure: event.failure,
            direction: event.direction, outcome: event.outcome, relatedMessageID: event.relatedMessageID, payload: payload)
        XCTAssertEqual(value.id, event.id); XCTAssertEqual(value.connectionID, event.connectionID); XCTAssertEqual(value.sequence, event.sequence)
        XCTAssertEqual(value.timestampMillis, event.timestampMillis); XCTAssertEqual(value.headers, event.headers)
        XCTAssertEqual(value.relatedMessageID, event.relatedMessageID); XCTAssertEqual(value.outcome, event.outcome)
        XCTAssertEqual(value.payload?.byteCount, event.payload?.byteCount); XCTAssertEqual(value.textByteCount, event.textByteCount)
    }
    private static func log(field: String, value: String) throws -> LogEvent {
        guard ["sequence", "sessionID", "category", "messageBytes", "private", "truncated"].contains(field) else { XCTFail("Unknown field"); throw PersistenceError.invalidRecord }
        return try LogEvent.restore(sessionID: field == "sessionID" ? value : id, sequence: field == "sequence" ? XCTUnwrap(Int64(value)) : 1,
            timestampMillis: Int64.min, level: .info, category: field == "category" ? (value == "~" ? "" : value) : "test",
            message: field == "messageBytes" ? String(repeating: "x", count: XCTUnwrap(Int(value))) : "synthetic",
            isPrivate: field == "private" && value == "true", isTruncated: field == "truncated" && value == "true")
    }
    private static func http(field: String, value: String) throws -> HTTPEvent {
        guard ["url", "authorization", "headerCount", "method", "status", "revision", "sequence", "responseURL"].contains(field) else { XCTFail("Unknown field"); throw PersistenceError.invalidRecord }
        let headers = try field == "authorization" ? [HTTPHeader(name: "authorization", value: value)]
            : field == "headerCount" ? Array(repeating: HTTPHeader(name: "x-public", value: "synthetic"), count: XCTUnwrap(Int(value))) : []
        let pending = try HTTPBodyReference.restore(state: .pending, blobID: nil, observedBytes: 0)
        return try HTTPEvent.restore(id: id, sessionID: other, sequence: field == "sequence" ? XCTUnwrap(Int64(value)) : 1,
            revision: field == "revision" ? XCTUnwrap(Int64(value)) : 0, timestampMillis: Int64.min,
            method: field == "method" ? value : "GET", source: .host, url: field == "url" ? value : "https://example.com/[redacted]",
            responseURL: field == "responseURL" ? value : nil, requestHeaders: headers, responseHeaders: [], headersLimited: false,
            statusCode: field == "status" ? XCTUnwrap(Int(value)) : nil, state: .pending, failure: nil, durationMillis: nil,
            requestBody: pending, responseBody: pending)
    }
    private static func socket(field: String, value: String) throws -> WebSocketEvent {
        guard ["sequence", "connectionID", "url", "outcome", "kind", "status", "closeCode"].contains(field) else { XCTFail("Unknown field"); throw PersistenceError.invalidRecord }
        return try WebSocketEvent.restore(id: id, connectionID: field == "connectionID" ? value : other, sessionID: other,
            sequence: field == "sequence" ? XCTUnwrap(Int64(value)) : 1, timestampMillis: Int64.min,
            url: field == "url" ? value : "wss://example.com/[redacted]", source: .host,
            kind: field == "kind" ? XCTUnwrap(WebSocketKind(rawValue: value)) : .message, headers: [], headersLimited: false,
            statusCode: field == "status" ? XCTUnwrap(Int(value)) : nil, closeCode: field == "closeCode" ? XCTUnwrap(Int(value)) : nil,
            failure: nil, direction: .outgoing, outcome: field == "outcome" ? XCTUnwrap(WebSocketMessageOutcome(rawValue: value)) : .submitted,
            relatedMessageID: nil, payload: WebSocketPayloadReference.restore(state: .omitted, blobID: nil, byteCount: nil))
    }
}
