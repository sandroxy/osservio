import Foundation
import OsservioCore
import OsservioStore
import XCTest

final class StructuredLogTests: XCTestCase {
    private let message = LogMessage.publicText("Request failed")
    private func example() -> LogDetails {
        .publicValues(arguments: [.null, .boolean(true), .integer(.min), .integer(.max), .number(1.25),
            .string("中文🙂\0\n\"\\"), .array([.boolean(false)]), .object([LogField("attempt", .integer(1)), LogField("attempt", .integer(2))])],
            attributes: [LogField("requestId", .string("fixture-42"))],
            error: LogError(type: "TimeoutError", message: "Try again", domain: "transport", code: "E_TIMEOUT", stackTrace: "Origin.run\nCaller.run"),
            callSite: LogCallSite(file: "Host/Client.swift", function: "send()", line: 42))
    }
    private func latest(_ store: MemoryLogStore) throws -> LogEvent { try XCTUnwrap(store.snapshot()?.events.last) }
    private func content(_ store: MemoryLogStore, _ event: LogEvent) throws -> LogDetailsContent {
        let id = try XCTUnwrap(event.details?.blobID)
        guard case .available(let content) = store.details(id: id) else { XCTFail("Missing test payload"); throw PersistenceError.invalidRecord }
        return content
    }
    func testSharedTypedEvidenceAndFrozenSelectionSurviveClear() throws {
        let store = try MemoryLogStore(), recorder = try LogRecorder(sink: store); try recorder.start()
        XCTAssertEqual(recorder.record(.error, category: "host", message: message, details: example()), .recorded)
        let event = try latest(store), payload = try content(store, event)
        var root = URL(fileURLWithPath: #filePath); for _ in 0..<5 { root.deleteLastPathComponent() }
        let expected = try String(contentsOf: root.appendingPathComponent("testing/structured-log.json"), encoding: .utf8).trimmingCharacters(in: .newlines)
        XCTAssertEqual(payload.text, expected); XCTAssertEqual(event.message, "Request failed")
        XCTAssertEqual(store.snapshot()?.retainedTextBytes, 18)
        XCTAssertEqual(store.snapshot()?.retainedDetailsBytes, expected.utf8.count)
        let metadata = try IncidentLogs.capture(XCTUnwrap(store.snapshot()), observedAtMillis: 10)
        XCTAssertEqual(metadata.payloads[payload.id]?.state, .notSelected)
        XCTAssertEqual(metadata.retainedPayloadBytes, 0)
        let limited = try metadata.includingDetails(from: store, payloadByteLimit: 1)
        XCTAssertEqual(limited.payloads[payload.id]?.state, .budgetExceeded)
        let frozen = try metadata.includingDetails(from: store)
        store.clear()
        XCTAssertEqual(frozen.payloads[payload.id]?.text, expected); XCTAssertEqual(frozen.observedAtMillis, 10)
        XCTAssertEqual(try metadata.includingDetails(from: store).payloads[payload.id]?.state, .unavailable)
        XCTAssertEqual(store.snapshot()?.retainedDetailsBytes, 0)
    }
    func testBoundedDetailsDoNotEraseTheSummary() throws {
        let store = try MemoryLogStore(), recorder = try LogRecorder(sink: store, detailsByteLimit: 256); try recorder.start()
        var deep = LogValue.null; for _ in 0..<10 { deep = .array([deep]) }
        let many = Array(repeating: LogValue.array(Array(repeating: .null, count: 64)), count: 5)
        for details in [LogDetails.publicValues(arguments: [.string(String(repeating: "x", count: 1_000_000))]),
                        .publicValues(arguments: [deep]), .publicValues(arguments: many)] {
            XCTAssertEqual(recorder.record(.info, category: "host", message: message, details: details), .recorded)
            XCTAssertEqual(try latest(store).details?.state, .limited)
            XCTAssertEqual(try latest(store).message, "Request failed")
        }
        XCTAssertEqual(recorder.record(.error, category: "host", message: message, details: .publicValues(arguments: [.number(.nan)])), .recorded)
        XCTAssertEqual(try latest(store).details?.state, .invalid)
        XCTAssertEqual(recorder.record(.info, category: "host", message: message, details: .privateValues), .recorded)
        XCTAssertEqual(try latest(store).details?.state, .omitted)
        XCTAssertEqual(store.snapshot()?.retainedDetailsBytes, 0)
    }
    func testBodyEvictionIsIndependentAndMetadataEvictionLeavesNoOrphans() throws {
        let store = try MemoryLogStore(recordLimit: 2, errorReserve: 0, detailsByteLimit: 80), recorder = try LogRecorder(sink: store); try recorder.start()
        for _ in 0..<2 { XCTAssertEqual(recorder.record(.info, category: "host", message: message, details: .publicValues(arguments: [.string("0123456789")])), .recorded) }
        let events = try XCTUnwrap(store.snapshot()).events
        guard case .unavailable = store.details(id: try XCTUnwrap(events.first?.details?.blobID)) else { return XCTFail("Old payload was retained") }
        XCTAssertEqual(store.snapshot()?.evictedDetails, 1)
        let lastID = try XCTUnwrap(events.last?.details?.blobID)
        for _ in 0..<2 { recorder.record(.info, category: "host", message: message) }
        guard case .unavailable = store.details(id: lastID) else { return XCTFail("Orphan payload survived") }
        XCTAssertEqual(store.snapshot()?.retainedDetailsBytes, 0); XCTAssertEqual(store.snapshot()?.evictedDetails, 2)
    }
    func testUnsupportedSinkAndAdmissionStayExplicit() throws {
        let sink = PlainSink(), recorder = try LogRecorder(sink: sink, eventsPerSecond: 1, errorReservePerSecond: 0)
        XCTAssertEqual(recorder.record(.info, category: "host", message: message, details: example()), .inactive)
        try recorder.start()
        XCTAssertEqual(recorder.record(.info, category: "host", message: message, details: example()), .recorded)
        XCTAssertEqual(sink.event?.details?.state, .unsupported)
        XCTAssertEqual(recorder.record(.info, category: "host", message: message, details: example()), .rateLimited)
    }
    func testInvalidPayloadRelationsAreRejected() throws {
        let store = try MemoryLogStore(), recorder = try LogRecorder(sink: store); try recorder.start()
        recorder.record(.info, category: "host", message: message, details: .publicValues())
        let event = try latest(store), payload = try content(store, event)
        XCTAssertThrowsError(try IncidentLogs.restore(events: [event], observedAtMillis: 0, sourceEventCount: 1, sourceEvictedCount: 0))
        let wrong = try IncidentPayload.restore(id: payload.id, state: .available, text: "x")
        XCTAssertThrowsError(try IncidentLogs.restore(events: [event], observedAtMillis: 0, sourceEventCount: 1, sourceEvictedCount: 0, payloads: [payload.id: wrong]))
        XCTAssertThrowsError(try store.append(event, details: LogDetailsContent.restore(id: payload.id, text: "x")))
    }
    private final class PlainSink: LogSink, @unchecked Sendable {
        var event: LogEvent?
        func append(_ event: LogEvent) -> LogWriteResult { self.event = event; return .stored }
    }
}
