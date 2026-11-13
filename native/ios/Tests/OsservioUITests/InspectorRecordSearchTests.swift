import Foundation
import OsservioCore
@testable import OsservioUI
import XCTest

final class InspectorRecordSearchTests: XCTestCase {
    func testNetworkOrderingPreservesTiedSocketCaptureOrderAndDifferentTimes() throws {
        let history = History()
        let first = InspectorSearchRecord.socket(try message(1, connection: 10, text: "first", history: history))
        let second = InspectorSearchRecord.socket(try message(2, connection: 20, text: "second", history: history))
        let newest = InspectorSearchRecord.socket(try message(3, connection: 10, text: "newest", history: history, time: 101))
        XCTAssertEqual(InspectorSearchRecord.ordered([first, newest, second]).map(\.key), [newest.key, second.key, first.key])
    }
    func testShortSearchPreviewKeepsTheWholeMessage() {
        let text = "{\"type\":\"beta\",\"data\":{\"lookup\":\"lookup-42\",\"sequence\":2}}"
        XCTAssertEqual(InspectorRecordSearch.preview(text, term: "lookup-42"), text)
    }
    private func id(_ value: Int) -> String { String(format: "00000000-0000-0000-0000-%012d", value) }
    func testSharedJSONFieldsPreserveEvidence() throws {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let fixture = try String(contentsOf: root.appendingPathComponent("testing/json-field-cases.tsv"), encoding: .utf8)
        func decode(_ value: String) throws -> String {
            if value == "-" { return "" }
            return try XCTUnwrap(String(data: XCTUnwrap(Data(base64Encoded: value)), encoding: .utf8))
        }
        for line in fixture.split(separator: "\n") where !line.hasPrefix("#") {
            let parts = line.split(separator: "\t").map(String.init)
            let actual = InspectorJSONField.matches(try decode(parts[4]), pointer: try decode(parts[2]), value: try decode(parts[3]), cancelled: { false })
            XCTAssertEqual(String(describing: actual), parts[1], parts[0])
        }
    }
    func testUnifiedTypeChangesKeepCommonCriteriaAndDiscardInapplicableOnes() {
        var query = InspectorQuery(); query.text = "needle"; query.values[.host] = ["example.test"]
        query.select(.status, value: "5xx")
        XCTAssertEqual(query.values[.transport], ["http"])
        query.minimumDurationMillis = 10
        query.select(.direction, value: "incoming")
        XCTAssertEqual(query.values[.transport], ["webSocket"]); XCTAssertNil(query.values[.status]); XCTAssertEqual(query.minimumDurationMillis, 0)
        XCTAssertEqual(query.values[.host], ["example.test"]); XCTAssertEqual(query.text, "needle")
        query.select(.transport, value: nil); XCTAssertNil(query.values[.direction])
        query.resetFilters(); XCTAssertEqual(query.text, "needle"); XCTAssertEqual(query.activeCount, 0)
    }
    func testCrossConnectionBodiesAndExactFieldsWithBoundedReads() throws {
        let history = History()
        let first = try message(1, connection: 10, text: #"{"kind":"changed","id":900719925474099312345}"#, history: history)
        let second = try message(2, connection: 20, text: #"{"action":"changed","id":2}"#, history: history)
        let records = [InspectorSearchRecord.socket(first), .socket(second)]
        var query = InspectorQuery(); query.text = "changed"
        let result = try XCTUnwrap(search(records, query, history))
        XCTAssertEqual(result.keys.count, 2); XCTAssertEqual(history.reads, 2)
        query.fieldPath = "/id"; query.fieldValue = "900719925474099312345"
        XCTAssertEqual(search(records, query, history)?.keys, [records[0].key])
        query.fieldPath = ""; query.excludedText = "action"
        XCTAssertEqual(search(records, query, history)?.keys, [records[0].key])
        let limited = try XCTUnwrap(search(records, query, history, limit: 1)); XCTAssertTrue(limited.limited)
        let reads = history.reads
        XCTAssertNil(InspectorRecordSearch.run(records, query: query, now: 1_000_000, http: nil, sockets: history, cancelled: { true }))
        XCTAssertEqual(history.reads, reads)
    }
    func testMetadataNeedsNoBodyReadAndEvictionRemainsVisible() throws {
        let history = History(), event = try message(1, connection: 10, text: "present", history: History())
        let records = [InspectorSearchRecord.socket(event)]
        var query = InspectorQuery(); query.text = "example.test"
        XCTAssertEqual(search(records, query, history)?.keys.count, 1); XCTAssertEqual(history.reads, 0)
        query.text = "example.test"; query.excludedText = "secret"
        XCTAssertTrue(search(records, query, history)?.keys.isEmpty == true, "Missing content cannot prove absence")
        query.excludedText = ""; query.text = "present"
        let result = try XCTUnwrap(search(records, query, history)); XCTAssertTrue(result.keys.isEmpty); XCTAssertEqual(result.unavailable, 1)
    }
    func testRecentConnectionUsesLatestActivity() throws {
        let history = History()
        let old = try message(1, connection: 10, text: "old", history: history, time: 1)
        let recent = try message(2, connection: 10, text: "recent", history: history, time: 1_000_000)
        var query = InspectorQuery(); query.recentMinutes = 5
        XCTAssertTrue(query.matches(InspectorConnection.groups([old, recent])[0], now: 1_000_000))
    }
    func testLogFieldsTruncationAndPrivacy() throws {
        func record(_ text: String, truncated: Bool = false, privateValue: Bool = false) throws -> InspectorSearchRecord {
            .log(try .restore(sessionID: id(100), sequence: 1, timestampMillis: 100, level: .info,
                category: "transport", message: text, isPrivate: privateValue, isTruncated: truncated))
        }
        var query = InspectorQuery(); query.fieldPath = "/id"; query.fieldValue = "42"
        func run(_ record: InspectorSearchRecord) -> InspectorRecordSearch.Result? {
            InspectorRecordSearch.run([record], query: query, now: 100, http: nil, sockets: nil, cancelled: { false })
        }
        XCTAssertEqual(run(try record(#"{"id":42}"#))?.keys.count, 1)
        let partial = run(try record(#"{"id":42}"#, truncated: true))
        XCTAssertTrue(partial?.keys.isEmpty == true); XCTAssertEqual(partial?.truncated, 1)
        XCTAssertEqual(run(try record("[private]", privateValue: true))?.unavailable, 1)
        query.fieldPath = ""; query.text = "42"
        XCTAssertEqual(run(try record(#"{"id":42}"#, truncated: true))?.keys.count, 1)
    }
    func testHTTPBothBodiesAndMinimumDuration() throws {
        let history = Requests(), request = try HTTPBodyReference.restore(state: .available, blobID: id(1) + ".request", observedBytes: 13)
        let response = try HTTPBodyReference.restore(state: .available, blobID: id(1) + ".response", observedBytes: 13)
        history.bodies[id(1) + ".request"] = try .restore(id: id(1) + ".request", text: #"{"value":"a"}"#)
        history.bodies[id(1) + ".response"] = try .restore(id: id(1) + ".response", text: #"{"value":"b"}"#)
        let record = InspectorSearchRecord.http(try .restore(id: id(1), sessionID: id(100), sequence: 1, revision: 2, timestampMillis: 100,
            method: "POST", source: .host, url: "https://example.test/data", responseURL: nil, requestHeaders: [], responseHeaders: [],
            headersLimited: false, statusCode: 200, state: .completed, failure: nil, durationMillis: 20, requestBody: request, responseBody: response))
        var query = InspectorQuery(); query.fieldPath = "/value"; query.fieldValue = "b"
        func run() -> InspectorRecordSearch.Result? {
            InspectorRecordSearch.run([record], query: query, now: 100, http: history, sockets: nil, cancelled: { false })
        }
        XCTAssertEqual(run()?.keys, [record.key]); XCTAssertEqual(history.reads, 2)
        query.minimumDurationMillis = 21; XCTAssertTrue(run()?.keys.isEmpty == true); XCTAssertEqual(history.reads, 2)
        query.minimumDurationMillis = 0; query.excludedText = "a"; XCTAssertTrue(run()?.keys.isEmpty == true)
    }
    func testFrozenSearchNeverFallsBackToLiveContent() throws {
        let history = History(), event = try message(1, connection: 10, text: "live-only", history: history)
        let records = [InspectorSearchRecord.socket(event)]
        let saved = [event.id: try IncidentPayload.restore(id: event.id, state: .available, text: "saved-only")]
        var query = InspectorQuery(); query.text = "saved-only"
        let result = try XCTUnwrap(InspectorRecordSearch.run(records, query: query, now: 100, http: nil, sockets: history, savedPayloads: saved, cancelled: { false }))
        XCTAssertEqual(result.keys, [records[0].key]); XCTAssertEqual(result.previewSources[records[0].key], "ui_search_message"); XCTAssertEqual(history.reads, 0)
        query.text = "live-only"
        XCTAssertTrue(InspectorRecordSearch.run(records, query: query, now: 100, http: nil, sockets: history, savedPayloads: [:], cancelled: { false })?.keys.isEmpty == true)
        XCTAssertEqual(history.reads, 0)
    }
    func testReadableURLAndVisibleDirectionAreSearchableWithoutBodyReads() throws {
        let history = History(), event = try message(1, connection: 10, text: "body", history: history)
        var query = InspectorQuery(); query.text = InspectorLocalization.text("ws_incoming")
        XCTAssertEqual(search([.socket(event)], query, history)?.keys.count, 1); XCTAssertEqual(history.reads, 0)
        let log = try LogEvent.restore(sessionID: event.sessionID, sequence: 2, timestampMillis: 100, level: .info,
            category: "fixture", message: "[private]", isPrivate: true, isTruncated: false)
        query.text = InspectorLocalization.text("ui_private_content")
        XCTAssertEqual(search([.log(log)], query, history)?.keys.count, 1)
        query.text = "discarded secret"
        XCTAssertTrue(search([.log(log)], query, history)?.keys.isEmpty == true)
    }
    func testHandshakeHeadersHaveProvenanceAndScopedExclusionDoesNotReadBodies() throws {
        let history = History()
        func record(limited: Bool) throws -> InspectorSearchRecord {
            .socket(try .restore(id: id(1), connectionID: id(10), sessionID: id(100), sequence: 1, timestampMillis: 100,
                url: "wss://example.test/ws?token=address-only", source: .host, kind: .opened,
                headers: [HTTPHeader(name: "authorization", value: "Bearer header-only")], headersLimited: limited,
                statusCode: 101, closeCode: nil, failure: nil, direction: nil, outcome: nil, relatedMessageID: nil, payload: nil))
        }
        let event = try record(limited: false)
        var query = InspectorQuery(); query.text = "header-only"
        let match = try XCTUnwrap(search([event], query, history))
        XCTAssertEqual(match.keys, [event.key]); XCTAssertEqual(match.previewSources[event.key], "ui_search_handshake_headers")
        XCTAssertTrue(match.previews[event.key]?.contains("authorization: Bearer") == true)
        query.select(.searchScope, value: "address"); XCTAssertTrue(search([event], query, history)?.keys.isEmpty == true)
        query.select(.searchScope, value: "headers"); query.text = ""; query.excludedText = "address-only"
        XCTAssertEqual(search([event], query, history)?.keys, [event.key])
        XCTAssertTrue(search([try record(limited: true)], query, history)?.keys.isEmpty == true)
        XCTAssertEqual(search([try record(limited: true)], query, history)?.truncated, 1)
        XCTAssertEqual(history.reads, 0)
    }
    func testTextScopeReadsOnlyTheSelectedHTTPBody() throws {
        let history = Requests(), requestID = id(1) + ".request", responseID = id(1) + ".response"
        history.bodies[requestID] = try .restore(id: requestID, text: "request-marker")
        history.bodies[responseID] = try .restore(id: responseID, text: "response-marker")
        let record = InspectorSearchRecord.http(try .restore(id: id(1), sessionID: id(100), sequence: 1, revision: 2, timestampMillis: 100,
            method: "POST", source: .host, url: "https://example.test/response-marker", responseURL: nil,
            requestHeaders: [], responseHeaders: [], headersLimited: false, statusCode: 200, state: .completed, failure: nil, durationMillis: 1,
            requestBody: .restore(state: .available, blobID: requestID, observedBytes: 14),
            responseBody: .restore(state: .available, blobID: responseID, observedBytes: 15)))
        var query = InspectorQuery(); query.text = "response-marker"; query.select(.searchScope, value: "request")
        XCTAssertTrue(InspectorRecordSearch.run([record], query: query, now: 100, http: history, sockets: nil, cancelled: { false })?.keys.isEmpty == true)
        XCTAssertEqual(history.reads, 1)
        query.select(.searchScope, value: "response")
        let result = InspectorRecordSearch.run([record], query: query, now: 100, http: history, sockets: nil, cancelled: { false })
        XCTAssertEqual(result?.keys, [record.key]); XCTAssertEqual(result?.previewSources[record.key], "ui_search_response_body")
        XCTAssertEqual(history.reads, 2)
    }
    private final class Requests: HTTPHistory, @unchecked Sendable {
        var bodies: [String: HTTPBody] = [:], reads = 0
        func snapshot() -> HTTPSnapshot? { XCTFail("Search must use its input snapshot"); return nil }
        func body(id: String) -> HTTPBodyLookup { reads += 1; return bodies[id].map(HTTPBodyLookup.available) ?? .unavailable }
        func clear() { XCTFail("Search must not clear history") }
    }
    private func search(_ records: [InspectorSearchRecord], _ query: InspectorQuery, _ history: History, limit: Int = InspectorRecordSearch.maximumUnits) -> InspectorRecordSearch.Result? {
        InspectorRecordSearch.run(records, query: query, now: 1_000_000, http: nil, sockets: history, unitLimit: limit, cancelled: { false })
    }
    private func message(_ value: Int, connection: Int, text: String, history: History, time: Int64 = 100) throws -> WebSocketEvent {
        history.bodies[id(value)] = try .restore(id: id(value), text: text)
        return try .restore(id: id(value), connectionID: id(connection), sessionID: id(100), sequence: Int64(value), timestampMillis: time,
            url: "wss://example.test/stream", source: .host, kind: .message, headers: [], headersLimited: false, statusCode: nil, closeCode: nil,
            failure: nil, direction: .incoming, outcome: .received, relatedMessageID: nil,
            payload: .restore(state: .available, blobID: id(value), byteCount: Int64(text.utf8.count)))
    }
    private final class History: WebSocketHistory, @unchecked Sendable {
        var bodies: [String: WebSocketPayload] = [:], reads = 0
        func snapshot() -> WebSocketSnapshot? { XCTFail("Search must use its input snapshot"); return nil }
        func payload(id: String) -> WebSocketPayloadLookup { reads += 1; return bodies[id].map(WebSocketPayloadLookup.available) ?? .unavailable }
        func clear() { XCTFail("Search must not clear history") }
    }
}
