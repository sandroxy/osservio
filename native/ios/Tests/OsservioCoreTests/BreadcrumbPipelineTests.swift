import Foundation
import OsservioCore
import OsservioStore
import XCTest

final class BreadcrumbPipelineTests: XCTestCase {
    func testRestorationPreservesEvidenceAndRejectsInvalidPersistedValues() throws {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let fixture = try String(contentsOf: root.appendingPathComponent("testing/breadcrumb-restoration.tsv"), encoding: .utf8)
        for line in fixture.split(separator: "\n") {
            let row = line.split(separator: "\t").map(String.init)
            let message = row[4] == "~" ? "" : try XCTUnwrap(String(data: XCTUnwrap(Data(base64Encoded: row[4])), encoding: .utf8))
            func restore() throws -> BreadcrumbEvent {
                try BreadcrumbEvent.restore(id: row[1], sessionID: "12345678-1234-1234-1234-123456789abc",
                    sequence: XCTUnwrap(Int64(row[2])), timestampMillis: Int64.min, sessionElapsedMillis: 9_007_199_254_740_993,
                    kind: .hostMarker, label: row[3], message: message, isPrivate: row[5] == "true", isTruncated: row[6] == "true",
                    value: Int64.min, retention: .restartRelevant)
            }
            if row[7] == "invalid" { XCTAssertThrowsError(try restore(), row[0]); continue }
            let event = try restore()
            XCTAssertEqual(event.id, row[1]); XCTAssertEqual(event.message, message); XCTAssertEqual(event.sequence, Int64(row[2]))
            XCTAssertEqual(event.textByteCount, row[3].utf8.count + message.utf8.count)
            XCTAssertEqual(event.timestampMillis, Int64.min); XCTAssertEqual(event.value, Int64.min)
            XCTAssertEqual(event.sessionElapsedMillis, 9_007_199_254_740_993)
        }
    }
    private final class Clock: LogClock, @unchecked Sendable {
        var now: UInt64 = 0
        func wallTimeMillis() -> Int64 { 1_000 + Int64(now) }
        func monotonicMillis() -> UInt64 { now }
    }
    func testSharedPrivacyLabelAndRetentionFixtures() throws {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let cases = try String(contentsOf: root.appendingPathComponent("testing/breadcrumb-cases.tsv"), encoding: .utf8)
        func decode(_ input: String) throws -> String {
            if input == "~" { return "" }
            return try XCTUnwrap(String(data: XCTUnwrap(Data(base64Encoded: input)), encoding: .utf8))
        }
        for line in cases.split(separator: "\n") {
            let row = line.split(separator: "\t").map(String.init)
            let store = try MemoryBreadcrumbStore()
            let capture = CaptureController(baselineEnabled: true)
            let recorder = try BreadcrumbRecorder(sink: store, captureController: capture, messageByteLimit: XCTUnwrap(Int(row[4])))
            let raw = try decode(row[3])
            let result = recorder.record(.hostMarker, label: row[1], message: row[2] == "private" ? .privateText(raw) : .publicText(raw),
                                         retention: try XCTUnwrap(BreadcrumbRetention(rawValue: row[8])))
            XCTAssertEqual(String(describing: result), row[5], row[0])
            if row[6] == "-" { XCTAssertTrue(store.snapshot()?.events.isEmpty == true); continue }
            let event = try XCTUnwrap(store.snapshot()?.events.first)
            XCTAssertEqual(event.message, try decode(row[6]), row[0])
            XCTAssertEqual(event.isTruncated, row[7] == "true")
            XCTAssertEqual(event.isPrivate, row[2] == "private")
            XCTAssertEqual(event.retention.rawValue, row[8])
        }
    }
    func testBaselineOffAndDetailedOrderingAreIndependentFromRetention() throws {
        let store = try MemoryBreadcrumbStore()
        let capture = CaptureController()
        let recorder = try BreadcrumbRecorder(sink: store, captureController: capture)
        let logs = try MemoryLogStore()
        let log = try LogRecorder(sink: logs, captureController: capture)
        XCTAssertEqual(recorder.record(.appActive, label: "app.active"), .inactive)
        XCTAssertTrue(capture.setBaselineEnabled(true))
        XCTAssertEqual(recorder.record(.appActive, label: "app.active", retention: .restartRelevant), .recorded)
        XCTAssertEqual(log.record(.info, category: "app", message: .publicText("detail")), .inactive)
        try capture.startDetailed()
        XCTAssertTrue(capture.status()?.baselineEnabled == true)
        XCTAssertTrue(capture.setBaselineEnabled(false))
        XCTAssertFalse(capture.status()?.baselineEnabled == true)
        XCTAssertEqual(capture.status()?.mode, .detailed)
        XCTAssertTrue(capture.setBaselineEnabled(true))
        log.record(.info, category: "app", message: .publicText("detail"))
        recorder.record(.hostMarker, label: "host.ready")
        XCTAssertEqual(store.snapshot()?.events.last?.sequence, 2)
        XCTAssertEqual(store.snapshot()?.events.last?.sessionID, logs.snapshot()?.events.first?.sessionID)
        XCTAssertTrue(capture.stopDetailed())
        XCTAssertEqual(recorder.record(.appBackground, label: "app.background"), .recorded)
        XCTAssertTrue(capture.stopAll())
        XCTAssertEqual(recorder.record(.memoryWarning, label: "memory.warning"), .inactive)
        XCTAssertEqual(store.snapshot()?.events.count, 3)
    }
    func testRateWindowClockProvenanceAndReplacement() throws {
        let clock = Clock()
        let capture = CaptureController(baselineEnabled: true, clock: clock)
        let store = try MemoryBreadcrumbStore()
        let recorder = try BreadcrumbRecorder(sink: store, captureController: capture, eventsPerSecond: 1)
        XCTAssertEqual(recorder.record(.memoryTrim, label: "memory.trim", value: 80), .recorded)
        XCTAssertEqual(recorder.record(.hostMarker, label: "host.ready"), .rateLimited)
        clock.now = 1_000
        XCTAssertEqual(recorder.record(.appActive, label: "app.active"), .recorded)
        XCTAssertEqual(store.snapshot()?.events.last?.sessionElapsedMillis, 1_000)
        XCTAssertEqual(store.snapshot()?.events.last?.timestampMillis, 2_000)
        XCTAssertEqual(store.snapshot()?.events.first?.value, 80)
        try capture.startDetailed()
        XCTAssertEqual(recorder.record(.appActive, label: "app.active"), .recorded)
        XCTAssertEqual(recorder.status()?.rateLimitedCount, 1)
    }
    func testStoreBudgetsSnapshotsAndOversizedRejection() throws {
        let store = try MemoryBreadcrumbStore(recordLimit: 2, textByteLimit: 10)
        let capture = CaptureController(baselineEnabled: true)
        let recorder = try BreadcrumbRecorder(sink: store, captureController: capture)
        XCTAssertEqual(recorder.record(.hostMarker, label: "a", message: .publicText("1234")), .recorded)
        let old = try XCTUnwrap(store.snapshot())
        recorder.record(.hostMarker, label: "b", message: .publicText("5678"))
        XCTAssertEqual(recorder.record(.hostMarker, label: "x", message: .publicText("1234567890")), .tooLarge)
        XCTAssertEqual(store.snapshot()?.events.count, 2)
        recorder.record(.hostMarker, label: "c", message: .publicText("abcd"))
        XCTAssertEqual(store.snapshot()?.events.first?.label, "b")
        XCTAssertEqual(store.snapshot()?.evictedCount, 1)
        XCTAssertEqual(old.events.count, 1)
        store.clear()
        XCTAssertEqual(store.snapshot()?.retainedTextBytes, 0)
        XCTAssertEqual(recorder.record(.hostMarker, label: "d"), .recorded)
    }
    private struct Sink: BreadcrumbSink {
        let action: @Sendable (BreadcrumbEvent) throws -> BreadcrumbWriteResult
        func append(_ event: BreadcrumbEvent) throws -> BreadcrumbWriteResult { try action(event) }
    }
    func testSinkFailureAndReentryDoNotBlock() throws {
        let capture = CaptureController(baselineEnabled: true)
        let busy = try BreadcrumbRecorder(sink: Sink { _ in XCTAssertNil(capture.status()); return .busy }, captureController: capture)
        XCTAssertEqual(busy.record(.appActive, label: "app.active"), .busy)
        let failed = try BreadcrumbRecorder(sink: Sink { _ in throw CocoaError(.fileWriteOutOfSpace) }, captureController: capture)
        XCTAssertEqual(failed.record(.appActive, label: "app.active"), .sinkFailure)
        XCTAssertThrowsError(try BreadcrumbRecorder(sink: MemoryBreadcrumbStore(), captureController: capture, messageByteLimit: 8))
    }
}
