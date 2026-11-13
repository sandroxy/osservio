import Foundation
@testable import OsservioCore
import OsservioStore
import XCTest

final class CaptureStatisticsTests: XCTestCase {
    func testResultsIncludeInactiveRejectedAndSinkFailuresWithoutResettingHistory() throws {
        let clock = LogPipelineTests.Clock(), store = try MemoryLogStore()
        let recorder = try LogRecorder(sink: store, eventsPerSecond: 1, errorReservePerSecond: 0, clock: clock)
        let statistics = recorder.captureController.statistics
        XCTAssertEqual(recorder.record(.info, category: "app", message: .publicText("off")), .inactive)
        try recorder.start()
        XCTAssertEqual(recorder.record(.info, category: "bad label", message: .publicText("invalid")), .invalidCategory)
        XCTAssertEqual(recorder.record(.info, category: "app", message: .publicText("one")), .recorded)
        XCTAssertEqual(recorder.record(.info, category: "app", message: .publicText("two")), .rateLimited)
        let before = statistics.snapshot()
        store.clear(); XCTAssertTrue(recorder.stop()); try recorder.start()
        let broken = try LogRecorder(sink: LogPipelineTests.Sink { _ in throw PersistenceError.invalidRecord }, captureController: recorder.captureController)
        XCTAssertEqual(broken.record(.error, category: "app", message: .publicText("error")), .sinkFailure)
        let after = statistics.snapshot()
        for outcome in [CaptureStatistics.Outcome.accepted, .inactive, .invalid, .rateLimited, .sinkFailure] {
            XCTAssertEqual(after.count(.logs, outcome), 1)
        }
        XCTAssertEqual(before.count(.logs, .sinkFailure), 0)
        XCTAssertEqual(after.startedAtMillis, clock.wallTimeMillis()); XCTAssertFalse(after.isIncomplete)
    }

    func testReentrantBusyRejectionIsCountedWithoutWaitingOnCaptureLock() throws {
        final class Box: @unchecked Sendable { var recorder: LogRecorder? }
        let box = Box()
        let sink = LogPipelineTests.Sink { _ in
            XCTAssertEqual(box.recorder?.record(.info, category: "app", message: .publicText("reentrant")), .busy)
            return .stored
        }
        let recorder = try LogRecorder(sink: sink); box.recorder = recorder; defer { box.recorder = nil }
        try recorder.start()
        XCTAssertEqual(recorder.record(.info, category: "app", message: .publicText("outer")), .recorded)
        XCTAssertEqual(recorder.captureController.statistics.snapshot().count(.logs, .busy), 1)
        XCTAssertEqual(recorder.captureController.statistics.snapshot().count(.logs, .accepted), 1)
    }

    func testHTTPExpiryIsCountedButMaintenancePollingIsNot() throws {
        let clock = LogPipelineTests.Clock(), controller = CaptureController(clock: clock)
        try controller.startDetailed()
        let recorder = try HTTPRecorder(sink: MemoryHTTPStore(), captureController: controller, privacy: HTTPPrivacyPolicy(), exchangeLifetimeMillis: 10)
        XCTAssertEqual(recorder.begin(method: "GET", url: "https://example.com/").result, .recorded)
        for _ in 0..<20 { XCTAssertEqual(recorder.maintenance(), .recorded) }
        XCTAssertEqual(controller.statistics.snapshot().count(.http, .accepted), 1)
        clock.now += 10; XCTAssertEqual(recorder.maintenance(), .recorded)
        XCTAssertEqual(controller.statistics.snapshot().count(.http, .accepted), 2)
    }

    func testConcurrentAccountingIsBoundedAndDeclaresSkippedBookkeeping() {
        let statistics = CaptureStatistics(startedAtMillis: 123)
        DispatchQueue.concurrentPerform(iterations: 8) { _ in
            for _ in 0..<2_000 { statistics.record(.webSocket, .busy) }
        }
        let value = statistics.snapshot(), count = value.count(.webSocket, .busy)
        XCTAssertGreaterThan(count, 0); XCTAssertLessThanOrEqual(count, 16_000)
        XCTAssertTrue(count == 16_000 || value.isIncomplete)
        XCTAssertEqual(value.count(.logs, .busy), 0)
    }
}
