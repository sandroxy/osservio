import Foundation
import OsservioCore
import OsservioStore
import XCTest

final class IncidentDetailTests: XCTestCase {
    private let id = "12345678-1234-1234-1234-123456789abc"
    func testSharedNewestContiguousWindowAndLossAccounting() throws {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let fixture = try String(contentsOf: root.appendingPathComponent("testing/incident-window.tsv"), encoding: .utf8)
        for line in fixture.split(separator: "\n") {
            let row = line.split(separator: "\t").map(String.init)
            let sizes = row[1] == "-" ? [] : try row[1].split(separator: ",").map { try XCTUnwrap(Int($0)) }
            let events = try sizes.enumerated().map { try LogEvent.restore(sessionID: id, sequence: Int64($0.offset + 1), timestampMillis: 10,
                level: .info, category: "a", message: String(repeating: "x", count: $0.element), isPrivate: false, isTruncated: false) }
            let source = LogSnapshot(events: events, retainedTextBytes: events.reduce(0) { $0 + $1.textByteCount }, evictedCount: try XCTUnwrap(Int64(row[4])), revision: 0)
            do {
                let result = try IncidentLogs.capture(source, observedAtMillis: -9, eventLimit: XCTUnwrap(Int(row[2])), textByteLimit: XCTUnwrap(Int(row[3])))
                XCTAssertEqual(row[6], "true", row[0]); XCTAssertEqual(result.observedAtMillis, -9)
                let expected = row[5] == "-" ? [] : try row[5].split(separator: ",").map { try XCTUnwrap(Int64($0)) }
                XCTAssertEqual(result.events.map(\.sequence), expected, row[0]); XCTAssertEqual(result.sourceEvictedCount, source.evictedCount)
                XCTAssertEqual(result.sourceEventCount, sizes.count); XCTAssertEqual(result.omittedEventCount, sizes.count - expected.count)
            } catch { XCTAssertEqual(row[6], "false", row[0]) }
        }
    }
    func testDuplicateEvidenceAndDetachedPayloadsAreRejected() throws {
        let event = try LogEvent.restore(sessionID: id, sequence: 1, timestampMillis: 0, level: .info, category: "a", message: "x", isPrivate: false, isTruncated: false)
        let same = try LogEvent.restore(sessionID: id.uppercased(), sequence: 1, timestampMillis: 1, level: .error, category: "a", message: "other", isPrivate: false, isTruncated: false)
        XCTAssertThrowsError(try IncidentLogs.restore(events: [event, same], observedAtMillis: 0, sourceEventCount: 2, sourceEvictedCount: 0))
        let extra = try IncidentPayload.restore(id: id, state: .unavailable, text: nil)
        XCTAssertThrowsError(try IncidentWebSockets.restore(events: [], payloads: [id: extra], observedAtMillis: 0, sourceEventCount: 0, sourceEvictedEvents: 0, sourceEvictedPayloads: 0))
        XCTAssertThrowsError(try IncidentPayload.restore(id: id, state: .available, text: nil))
        XCTAssertThrowsError(try IncidentPayload.restore(id: id, state: .unavailable, text: ""))
    }
    func testHTTPSelectionDoesNotLookupUnselectedBodiesAndSurvivesLiveClear() throws {
        let history = try MemoryHTTPStore(), capture = CaptureController()
        try capture.startDetailed()
        let recorder = try HTTPRecorder(sink: history, captureController: capture, privacy: HTTPPrivacyPolicy(bodyPolicy: .publicUTF8))
        let first = try response(recorder, text: "abcd"), second = try response(recorder, text: "xy")
        let source = try XCTUnwrap(history.snapshot())
        let noRead = LookupHTTP { _ in XCTFail("Unselected content was queried"); return .busy }
        let metadataOnly = try IncidentHTTP.capture(source, observedAtMillis: 5, history: noRead)
        XCTAssertTrue(metadataOnly.payloads.values.allSatisfy { $0.state == .notSelected && $0.text == nil })
        let limited = try IncidentHTTP.capture(source, observedAtMillis: 6, history: history, includeBodies: true, payloadByteLimit: 3)
        XCTAssertEqual(limited.payloads[second.id + ".response"]?.text, "xy")
        XCTAssertEqual(limited.payloads[first.id + ".response"]?.state, .budgetExceeded)
        XCTAssertEqual(limited.retainedPayloadBytes, 2)
        _ = try response(recorder, text: "later")
        let deferred = try metadataOnly.includingBodies(from: history, payloadByteLimit: 3)
        XCTAssertEqual(deferred.events.map(\.id), metadataOnly.events.map(\.id))
        XCTAssertEqual(deferred.observedAtMillis, 5); XCTAssertEqual(deferred.sourceEventCount, 2)
        XCTAssertEqual(deferred.sourceEvictedEvents, metadataOnly.sourceEvictedEvents)
        XCTAssertEqual(deferred.sourceEvictedBodies, metadataOnly.sourceEvictedBodies)
        XCTAssertEqual(deferred.payloads[second.id + ".response"]?.text, "xy")
        XCTAssertEqual(deferred.payloads[first.id + ".response"]?.state, .budgetExceeded)
        XCTAssertThrowsError(try metadataOnly.includingBodies(from: noRead, payloadByteLimit: -1))
        history.clear()
        XCTAssertEqual(limited.events.count, 2); XCTAssertEqual(limited.payloads[second.id + ".response"]?.text, "xy")
        let missing = try IncidentHTTP.capture(source, observedAtMillis: 7, history: history, includeBodies: true)
        XCTAssertTrue(missing.payloads.values.allSatisfy { $0.state == .unavailable && $0.text == nil })
        XCTAssertTrue(missing.events.allSatisfy { $0.responseBody.state == .available })
        let busy = try IncidentHTTP.capture(source, observedAtMillis: 8, history: LookupHTTP { _ in .busy }, includeBodies: true)
        XCTAssertTrue(busy.payloads.values.allSatisfy { $0.state == .busy })
        var invalid = limited.payloads
        invalid[second.id + ".response"] = try .restore(id: second.id + ".response", state: .available, text: "wrong-length")
        XCTAssertThrowsError(try IncidentHTTP.restore(events: limited.events, payloads: invalid, observedAtMillis: 0,
            sourceEventCount: 2, sourceEvictedEvents: 0, sourceEvictedBodies: 0))
    }
    func testEmptyBodiesAndWebSocketSubmissionsKeepDistinctMeaning() throws {
        let history = try MemoryWebSocketStore(), capture = CaptureController()
        try capture.startDetailed()
        let recorder = try WebSocketRecorder(sink: history, captureController: capture, privacy: WebSocketPrivacyPolicy(publicMessageText: true))
        let token = try XCTUnwrap(recorder.begin(url: "wss://example.com").token)
        let first = UUID(), empty = UUID()
        XCTAssertEqual(recorder.text(token, direction: .outgoing, outcome: .submitted, value: "😀", messageID: first), .recorded)
        XCTAssertEqual(recorder.text(token, direction: .incoming, outcome: .received, value: "", messageID: empty), .recorded)
        let source = try XCTUnwrap(history.snapshot())
        let noRead = LookupWebSocket { _ in XCTFail("Unselected content was queried"); return .busy }
        let metadata = try IncidentWebSockets.capture(source, observedAtMillis: 1, history: noRead)
        XCTAssertTrue(metadata.payloads.values.allSatisfy { $0.state == .notSelected })
        let bounded = try IncidentWebSockets.capture(source, observedAtMillis: 2, history: history, includePayloads: true, payloadByteLimit: 0)
        XCTAssertEqual(bounded.payloads[empty.uuidString]?.state, .available); XCTAssertEqual(bounded.payloads[empty.uuidString]?.text, "")
        XCTAssertEqual(bounded.payloads[first.uuidString]?.state, .budgetExceeded)
        XCTAssertEqual(recorder.sendResult(token, messageID: first, outcome: .sent), .recorded)
        let resultOnly = try IncidentWebSockets.capture(XCTUnwrap(history.snapshot()), observedAtMillis: 3, history: history, eventLimit: 1)
        XCTAssertEqual(resultOnly.events.first?.relatedMessageID, first.uuidString); XCTAssertTrue(resultOnly.payloads.isEmpty)
        XCTAssertEqual(resultOnly.omittedEventCount, 3)
        let deferred = try metadata.includingPayloads(from: history, payloadByteLimit: 0)
        XCTAssertEqual(deferred.events.map(\.id), metadata.events.map(\.id))
        XCTAssertEqual(deferred.observedAtMillis, 1); XCTAssertEqual(deferred.sourceEventCount, 3)
        XCTAssertEqual(deferred.sourceEvictedEvents, metadata.sourceEvictedEvents)
        XCTAssertEqual(deferred.sourceEvictedPayloads, metadata.sourceEvictedPayloads)
        XCTAssertEqual(deferred.payloads[empty.uuidString]?.text, "")
        XCTAssertEqual(deferred.payloads[first.uuidString]?.state, .budgetExceeded)
        XCTAssertThrowsError(try metadata.includingPayloads(from: noRead, payloadByteLimit: 262_145))
        history.clear()
        XCTAssertEqual(bounded.events[1].outcome, .submitted)
        let unavailable = try IncidentWebSockets.capture(source, observedAtMillis: 4, history: history, includePayloads: true)
        XCTAssertTrue(unavailable.payloads.values.allSatisfy { $0.state == .unavailable })
        let busy = try IncidentWebSockets.capture(source, observedAtMillis: 5, history: LookupWebSocket { _ in .busy }, includePayloads: true)
        XCTAssertTrue(busy.payloads.values.allSatisfy { $0.state == .busy })
    }
    private func response(_ recorder: HTTPRecorder, text: String) throws -> HTTPExchangeToken {
        let token = try XCTUnwrap(recorder.begin(method: "GET", url: "https://example.com").token)
        XCTAssertEqual(recorder.receiveResponse(token, statusCode: 200, headers: [HTTPHeader(name: "Content-Type", value: "text/plain")]), .recorded)
        let data = Data(text.utf8)
        XCTAssertEqual(recorder.observeBody(token, direction: .response, byteCount: Int64(data.count)) { Data(data.prefix($0)) }, .recorded)
        XCTAssertEqual(recorder.finish(token, state: .completed), .recorded); return token
    }
}

private final class LookupHTTP: HTTPHistory, @unchecked Sendable {
    let lookup: (String) -> HTTPBodyLookup
    init(_ lookup: @escaping (String) -> HTTPBodyLookup) { self.lookup = lookup }
    func snapshot() -> HTTPSnapshot? { nil }
    func body(id: String) -> HTTPBodyLookup { lookup(id) }
    func clear() { XCTFail("Capture must not clear history") }
}
private final class LookupWebSocket: WebSocketHistory, @unchecked Sendable {
    let lookup: (String) -> WebSocketPayloadLookup
    init(_ lookup: @escaping (String) -> WebSocketPayloadLookup) { self.lookup = lookup }
    func snapshot() -> WebSocketSnapshot? { nil }
    func payload(id: String) -> WebSocketPayloadLookup { lookup(id) }
    func clear() { XCTFail("Capture must not clear history") }
}
