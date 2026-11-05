import Foundation
import OsservioCore
import OsservioStore
import XCTest

final class LogPipelineTests: XCTestCase {
    final class Clock: LogClock, @unchecked Sendable {
        var now: UInt64 = 100
        func monotonicMillis() -> UInt64 { now }
        func wallTimeMillis() -> Int64 { 1_700_000_000_000 }
    }

    final class Sink: LogSink, @unchecked Sendable {
        let body: (LogEvent) throws -> LogWriteResult
        init(_ body: @escaping (LogEvent) throws -> LogWriteResult) { self.body = body }
        func append(_ event: LogEvent) throws -> LogWriteResult { try body(event) }
    }

    func testSharedPrivacyAndUnicodeCases() throws {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let text = try String(contentsOf: root.appendingPathComponent("testing/log-text-cases.tsv"), encoding: .utf8)
        for line in text.split(separator: "\n") {
            let columns = line.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
            XCTAssertEqual(columns.count, 6)
            let input = try XCTUnwrap(String(data: XCTUnwrap(Data(base64Encoded: columns[3])), encoding: .utf8))
            let expected = try XCTUnwrap(String(data: XCTUnwrap(Data(base64Encoded: columns[4])), encoding: .utf8))
            let store = try MemoryLogStore()
            let recorder = try LogRecorder(sink: store, messageByteLimit: XCTUnwrap(Int(columns[2])))
            try recorder.start()
            let message = columns[1] == "private" ? LogMessage.privateText(input) : .publicText(input)
            XCTAssertEqual(recorder.record(.info, category: "app", message: message), .recorded, columns[0])
            let event = try XCTUnwrap(store.snapshot()?.events.first)
            XCTAssertEqual(event.message, expected, columns[0])
            XCTAssertEqual(event.isPrivate, columns[1] == "private", columns[0])
            XCTAssertEqual(event.isTruncated, columns[5] == "true", columns[0])
            XCTAssertEqual(event.textByteCount, expected.utf8.count + 3, columns[0])
        }
    }

    func testCaptureLifetimeAndHistoryAreIndependent() throws {
        let clock = Clock()
        let store = try MemoryLogStore()
        let recorder = try LogRecorder(sink: store, clock: clock)
        XCTAssertEqual(recorder.record(.info, category: "app", message: .publicText("before")), .inactive)
        try recorder.start(durationMillis: 1_000)
        XCTAssertEqual(recorder.record(.info, category: "app", message: .publicText("first")), .recorded)
        let first = try XCTUnwrap(store.snapshot()?.events.first)
        clock.now = 1_100
        XCTAssertEqual(recorder.record(.info, category: "app", message: .publicText("expired")), .inactive)
        XCTAssertEqual(recorder.status()?.isRecording, false)
        XCTAssertEqual(store.snapshot()?.events.count, 1)
        try recorder.start()
        XCTAssertEqual(recorder.record(.info, category: "app", message: .publicText("second")), .recorded)
        let second = try XCTUnwrap(store.snapshot()?.events.last)
        XCTAssertNotEqual(first.sessionID, second.sessionID)
        XCTAssertEqual(second.sequence, 1)
        XCTAssertEqual(second.timestampMillis, 1_700_000_000_000)
        store.clear()
        XCTAssertEqual(recorder.status()?.isRecording, true)
        XCTAssertEqual(recorder.record(.error, category: "app", message: .privateText("secret")), .recorded)
        XCTAssertTrue(recorder.stop())
        XCTAssertEqual(store.snapshot()?.events.count, 1)
    }

    func testCountEvictionAndSnapshotIsolation() throws {
        let store = try MemoryLogStore(recordLimit: 2)
        let recorder = try LogRecorder(sink: store)
        try recorder.start()
        for value in ["one", "two"] { XCTAssertEqual(recorder.record(.debug, category: "app", message: .publicText(value)), .recorded) }
        let before = try XCTUnwrap(store.snapshot())
        XCTAssertEqual(recorder.record(.debug, category: "app", message: .publicText("three")), .recorded)
        let after = try XCTUnwrap(store.snapshot())
        XCTAssertEqual(after.events.map(\.sequence), [2, 3])
        XCTAssertEqual(after.evictedCount, 1)
        XCTAssertEqual(after.retainedTextBytes, after.events.reduce(0) { $0 + $1.textByteCount })
        store.clear()
        XCTAssertEqual(before.events.map(\.message), ["one", "two"])
        XCTAssertEqual(after.events.map(\.message), ["two", "three"])
        XCTAssertEqual(store.snapshot()?.retainedTextBytes, 0)
        XCTAssertEqual(store.snapshot()?.evictedCount, 1)
    }

    func testByteBudgetAndOversizedRejectionPreserveHistory() throws {
        let store = try MemoryLogStore(recordLimit: 10, textByteLimit: 12)
        let recorder = try LogRecorder(sink: store, messageByteLimit: 9)
        try recorder.start()
        for value in ["1111", "2222"] { XCTAssertEqual(recorder.record(.info, category: "app", message: .publicText(value)), .recorded) }
        XCTAssertEqual(store.snapshot()?.events.map(\.message), ["2222"])
        XCTAssertEqual(recorder.record(.info, category: "oversize", message: .publicText("123456789")), .tooLarge)
        XCTAssertEqual(store.snapshot()?.events.map(\.message), ["2222"])
        XCTAssertEqual(store.snapshot()?.evictedCount, 1)
    }

    func testRateLimitBeforeStorageAndWindowReset() throws {
        let clock = Clock()
        let store = try MemoryLogStore()
        let recorder = try LogRecorder(sink: store, eventsPerSecond: 2, clock: clock)
        try recorder.start(durationMillis: 3_000)
        for _ in 0..<2 { XCTAssertEqual(recorder.record(.info, category: "app", message: .publicText("ok")), .recorded) }
        clock.now = 1_099
        XCTAssertEqual(recorder.record(.info, category: "app", message: .publicText("limited")), .rateLimited)
        clock.now = 1_100
        XCTAssertEqual(recorder.record(.info, category: "app", message: .publicText("next")), .recorded)
        XCTAssertEqual(recorder.status()?.rateLimitedCount, 1)
        XCTAssertEqual(store.snapshot()?.events.count, 3)
    }

    func testInvalidConfigurationAndCategories() throws {
        let store = try MemoryLogStore()
        XCTAssertThrowsError(try MemoryLogStore(recordLimit: 0))
        XCTAssertThrowsError(try MemoryLogStore(textByteLimit: 0))
        XCTAssertThrowsError(try LogRecorder(sink: store, messageByteLimit: 8))
        XCTAssertThrowsError(try LogRecorder(sink: store, eventsPerSecond: 0))
        let recorder = try LogRecorder(sink: store)
        XCTAssertThrowsError(try recorder.start(durationMillis: 0))
        XCTAssertThrowsError(try recorder.start(durationMillis: 86_400_001))
        try recorder.start()
        for category in ["", "用户", "user@email", String(repeating: "a", count: 65)] {
            XCTAssertEqual(recorder.record(.info, category: category, message: .publicText("value")), .invalidCategory)
        }
        XCTAssertEqual(store.snapshot()?.events.count, 0)
    }

    func testContentionRejectsWithoutWaitingOrChangingSession() throws {
        let entered = DispatchSemaphore(value: 0)
        let release = DispatchSemaphore(value: 0)
        let completed = expectation(description: "first record completed")
        let recorder = try LogRecorder(sink: Sink { _ in
            entered.signal()
            _ = release.wait(timeout: .now() + 5)
            return .stored
        })
        try recorder.start()
        DispatchQueue.global().async {
            XCTAssertEqual(recorder.record(.info, category: "app", message: .publicText("first")), .recorded)
            completed.fulfill()
        }
        XCTAssertEqual(entered.wait(timeout: .now() + 2), .success)
        defer { release.signal() }
        XCTAssertEqual(recorder.record(.info, category: "app", message: .publicText("second")), .busy)
        XCTAssertNil(recorder.status())
        XCTAssertFalse(recorder.stop())
        XCTAssertThrowsError(try recorder.start())
        release.signal()
        wait(for: [completed], timeout: 2)
        XCTAssertEqual(recorder.status()?.isRecording, true)
    }

    func testSinkFailureIsExplicit() throws {
        enum Failure: Error { case storage }
        let recorder = try LogRecorder(sink: Sink { _ in throw Failure.storage })
        try recorder.start()
        XCTAssertEqual(recorder.record(.error, category: "app", message: .privateText("sensitive")), .sinkFailure)
    }
}
