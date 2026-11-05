import Foundation
import OsservioCore
import OsservioStore
import XCTest

final class CaptureControllerTests: XCTestCase {
    func testSharedLifecycleFixtures() throws {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let fixture = try String(contentsOf: root.appendingPathComponent("testing/capture-lifecycle.tsv"), encoding: .utf8)
        let clock = LogPipelineTests.Clock()
        let capture = CaptureController(clock: clock)
        var previousID: String?
        for line in fixture.split(separator: "\n") {
            let fields = line.split(separator: "\t").map(String.init)
            XCTAssertEqual(fields.count, 7)
            clock.now = try XCTUnwrap(UInt64(fields[1]))
            switch fields[2] {
            case "status": break
            case "baseline": XCTAssertTrue(capture.setBaselineEnabled(fields[3] == "true"), fields[0])
            case "detailed": try capture.startDetailed(durationMillis: XCTUnwrap(UInt64(fields[3])))
            case "stop_detailed": XCTAssertTrue(capture.stopDetailed(), fields[0])
            case "stop_all": XCTAssertTrue(capture.stopAll(), fields[0])
            default: XCTFail("Unknown fixture operation: \(fields[2])")
            }
            let status = try XCTUnwrap(capture.status())
            XCTAssertEqual(status.mode.rawValue, fields[4], fields[0])
            XCTAssertEqual(status.remainingMillis, UInt64(fields[5]), fields[0])
            XCTAssertEqual(status.sessionID != previousID, fields[6] == "changed", fields[0])
            previousID = status.sessionID
        }
    }

    func testBaselineNeverAdmitsDetailedLogs() throws {
        let clock = LogPipelineTests.Clock()
        let capture = CaptureController(baselineEnabled: true, clock: clock)
        let store = try MemoryLogStore()
        let logger = try LogRecorder(sink: store, captureController: capture)
        XCTAssertEqual(logger.record(.info, category: "app", message: .publicText("hidden")), .inactive)
        XCTAssertEqual(logger.status()?.isRecording, false)
        try logger.start(durationMillis: 10)
        XCTAssertEqual(logger.record(.info, category: "app", message: .publicText("detail")), .recorded)
        clock.now += 10
        XCTAssertEqual(logger.record(.info, category: "app", message: .publicText("after expiry")), .inactive)
        XCTAssertEqual(capture.status()?.mode, .baseline)
        XCTAssertEqual(store.snapshot()?.events.map(\.message), ["detail"])
        XCTAssertTrue(capture.stopAll())
        XCTAssertEqual(store.snapshot()?.events.count, 1)
    }

    func testSharedSessionOrdersRecordersAndResetsTheirRateWindows() throws {
        let capture = CaptureController(clock: LogPipelineTests.Clock())
        let store = try MemoryLogStore()
        let first = try LogRecorder(sink: store, captureController: capture, eventsPerSecond: 1)
        let second = try LogRecorder(sink: store, captureController: capture, eventsPerSecond: 1)
        try capture.startDetailed()
        XCTAssertEqual(first.record(.info, category: "first", message: .publicText("one")), .recorded)
        XCTAssertEqual(second.record(.info, category: "second", message: .publicText("two")), .recorded)
        XCTAssertEqual(first.record(.info, category: "first", message: .publicText("limited")), .rateLimited)
        let before = try XCTUnwrap(store.snapshot())
        XCTAssertEqual(Set(before.events.map(\.sessionID)).count, 1)
        XCTAssertEqual(before.events.map(\.sequence), [1, 2])
        try second.start()
        XCTAssertEqual(first.record(.info, category: "first", message: .publicText("new one")), .recorded)
        XCTAssertEqual(second.record(.info, category: "second", message: .publicText("new two")), .recorded)
        let after = try XCTUnwrap(store.snapshot())
        XCTAssertNotEqual(before.events.first?.sessionID, after.events.last?.sessionID)
        XCTAssertEqual(after.events.suffix(2).map(\.sequence), [1, 2])
        XCTAssertEqual(first.status()?.rateLimitedCount, 1)
    }

    func testSharedContentionRejectsControlWithoutChangingHistory() throws {
        let capture = CaptureController(baselineEnabled: true)
        let entered = DispatchSemaphore(value: 0)
        let release = DispatchSemaphore(value: 0)
        let finished = expectation(description: "writer completed")
        let first = try LogRecorder(sink: LogPipelineTests.Sink { _ in
            entered.signal()
            _ = release.wait(timeout: .now() + 5)
            return .stored
        }, captureController: capture)
        let second = try LogRecorder(sink: MemoryLogStore(), captureController: capture)
        try capture.startDetailed()
        DispatchQueue.global().async {
            XCTAssertEqual(first.record(.info, category: "app", message: .privateText("secret")), .recorded)
            finished.fulfill()
        }
        XCTAssertEqual(entered.wait(timeout: .now() + 2), .success)
        XCTAssertEqual(second.record(.info, category: "app", message: .publicText("busy")), .busy)
        XCTAssertNil(capture.status())
        XCTAssertFalse(capture.stopAll())
        XCTAssertFalse(capture.setBaselineEnabled(false))
        release.signal()
        wait(for: [finished], timeout: 2)
        XCTAssertTrue(capture.stopDetailed())
        XCTAssertEqual(capture.status()?.mode, .baseline)
    }

    func testDelayedFirstLogPreservesSessionRateWindowAnchor() throws {
        let clock = LogPipelineTests.Clock()
        clock.now = 100
        let capture = CaptureController(clock: clock)
        let logger = try LogRecorder(sink: MemoryLogStore(), captureController: capture, eventsPerSecond: 1)
        try capture.startDetailed()
        clock.now = 1_000
        XCTAssertEqual(logger.record(.info, category: "app", message: .publicText("first")), .recorded)
        clock.now = 1_100
        XCTAssertEqual(logger.record(.info, category: "app", message: .publicText("next window")), .recorded)
        XCTAssertEqual(logger.record(.info, category: "app", message: .publicText("limited")), .rateLimited)
    }

    func testInvalidDurationPreservesBaselineAndIdentity() throws {
        let clock = LogPipelineTests.Clock()
        let capture = CaptureController(baselineEnabled: true, clock: clock)
        let before = capture.status()?.sessionID
        for duration in [UInt64(0), 86_400_001] {
            XCTAssertThrowsError(try capture.startDetailed(durationMillis: duration))
        }
        clock.now = UInt64.max
        XCTAssertThrowsError(try capture.startDetailed(durationMillis: 1))
        XCTAssertEqual(capture.status()?.mode, .baseline)
        XCTAssertEqual(capture.status()?.sessionID, before)
    }
}
