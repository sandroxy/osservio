import Foundation
import OsservioCore
@testable import OsservioUI
import XCTest

final class InspectorStructuredLogTests: XCTestCase {
    private let id = "11111111-1111-1111-1111-111111111111"
    private let text = "{\"arguments\":[9223372036854775807],\"attributes\":{\"requestId\":\"lookup-42\"}}"
    private func event() throws -> LogEvent {
        try .restore(sessionID: id, sequence: 1, timestampMillis: 1, level: .error, category: "fixture", message: "Request failed",
            isPrivate: false, isTruncated: false, details: .restore(state: .available, blobID: id, textByteCount: text.utf8.count))
    }
    func testLazyLiveAndSavedSearchRespectFieldPrecisionExclusionsAndEviction() throws {
        let event = try event(), history = History(try .restore(id: id, text: text)), records = [InspectorSearchRecord.log(event)]
        var query = InspectorQuery(); query.text = "fixture"
        XCTAssertEqual(InspectorRecordSearch.run(records, query: query, now: 1, http: nil, sockets: nil, logs: history, cancelled: { false })?.keys.count, 1)
        XCTAssertEqual(history.reads, 0)
        query.text = "lookup-42"
        let result = try XCTUnwrap(InspectorRecordSearch.run(records, query: query, now: 1, http: nil, sockets: nil, logs: history, cancelled: { false }))
        XCTAssertEqual(result.keys.count, 1); XCTAssertEqual(result.previewSources[records[0].key], "ui_search_log_details")
        query.text = ""; query.fieldPath = "/arguments/0"; query.fieldValue = "9223372036854775807"
        XCTAssertEqual(InspectorRecordSearch.run(records, query: query, now: 1, http: nil, sockets: nil, logs: history, cancelled: { false })?.keys.count, 1)
        let saved = try IncidentPayload.restore(id: id, state: .available, text: text); history.clear()
        XCTAssertEqual(InspectorRecordSearch.run(records, query: query, now: 1, http: nil, sockets: nil, savedPayloads: [id: saved], cancelled: { false })?.keys.count, 1)
        query.fieldPath = ""; query.text = "fixture"; query.excludedText = "lookup-42"
        let missing = try XCTUnwrap(InspectorRecordSearch.run(records, query: query, now: 1, http: nil, sockets: nil, logs: history, cancelled: { false }))
        XCTAssertTrue(missing.keys.isEmpty); XCTAssertGreaterThan(missing.unavailable, 0)
    }
    func testOpenedReaderKeepsItsSnapshotAndSavedReadsNeverFallBackToLiveHistory() throws {
        let event = try event(), history = History(try .restore(id: id, text: text))
        let content = InspectorLogDetails.content(event, history: history, saved: nil)
        XCTAssertTrue(content.allowsJSON); XCTAssertEqual(content.text, text); history.clear()
        XCTAssertEqual(content.text, text); XCTAssertTrue(content.isAvailable)
        XCTAssertFalse(InspectorLogDetails.content(event, history: history, saved: nil).isAvailable)
        let reads = history.reads, missing = try IncidentPayload.restore(id: id, state: .notSelected, text: nil)
        XCTAssertFalse(InspectorLogDetails.content(event, history: history, saved: [id: missing]).isAvailable)
        XCTAssertEqual(history.reads, reads)
    }
    private final class History: LogDetailsHistory, @unchecked Sendable {
        var value: LogDetailsContent?; var reads = 0
        init(_ value: LogDetailsContent) { self.value = value }
        func details(id: String) -> LogDetailsLookup { reads += 1; return value.map(LogDetailsLookup.available) ?? .unavailable }
        func snapshot() -> LogSnapshot? { nil }
        func clear() { value = nil }
    }
}
