import OsservioCore
import OsservioStore
import XCTest

final class LogProtectionTests: XCTestCase {
    private final class Clock: LogClock, @unchecked Sendable {
        var now: UInt64 = 100
        func monotonicMillis() -> UInt64 { now }
        func wallTimeMillis() -> Int64 { 1_700_000_000_000 }
    }
    func testErrorReserveIsFiniteAndDoesNotAdmitOrdinaryFlood() throws {
        let clock = Clock(), store = try MemoryLogStore()
        let recorder = try LogRecorder(sink: store, eventsPerSecond: 2, clock: clock)
        try recorder.start()
        for _ in 0..<2 { XCTAssertEqual(recorder.record(.info, category: "app", message: .publicText("ordinary")), .recorded) }
        XCTAssertEqual(recorder.record(.info, category: "app", message: .publicText("limited")), .rateLimited)
        XCTAssertEqual(recorder.record(.error, category: "app", message: .publicText("failure")), .recorded)
        XCTAssertEqual(recorder.record(.error, category: "app", message: .publicText("error flood")), .rateLimited)
        clock.now += 1_000
        XCTAssertEqual(recorder.record(.info, category: "app", message: .publicText("new window")), .recorded)
        let disabled = try LogRecorder(sink: store, eventsPerSecond: 1, errorReservePerSecond: 0, clock: clock)
        try disabled.start()
        XCTAssertEqual(disabled.record(.info, category: "app", message: .publicText("ordinary")), .recorded)
        XCTAssertEqual(disabled.record(.error, category: "app", message: .publicText("limited")), .rateLimited)
    }
    func testRecentErrorsSurviveFloodButNewErrorsReplaceOldErrors() throws {
        let clock = Clock(), store = try MemoryLogStore(recordLimit: 3, errorReserve: 1, clock: clock)
        let recorder = try LogRecorder(sink: store, clock: clock); try recorder.start()
        for (level, value) in [(LogLevel.error, "failure1"), (.info, "a"), (.info, "b"), (.info, "c")] {
            XCTAssertEqual(recorder.record(level, category: "app", message: .publicText(value)), .recorded)
        }
        XCTAssertEqual(store.snapshot()?.events.map(\.message), ["failure1", "b", "c"])
        XCTAssertEqual(recorder.record(.error, category: "app", message: .publicText("failure2")), .recorded)
        XCTAssertEqual(store.snapshot()?.events.map(\.message), ["b", "c", "failure2"])
        XCTAssertEqual(store.snapshot()?.evictedCount, 2)
    }
    func testProtectedCapacityRejectsAtomicallyAndExpires() throws {
        let clock = Clock(), store = try MemoryLogStore(recordLimit: 4, textByteLimit: 10, errorReserve: 1, clock: clock)
        let recorder = try LogRecorder(sink: store, clock: clock); try recorder.start()
        XCTAssertEqual(recorder.record(.error, category: "a", message: .publicText("1234567")), .recorded)
        XCTAssertEqual(recorder.record(.info, category: "a", message: .publicText("x")), .recorded)
        let before = try XCTUnwrap(store.snapshot())
        XCTAssertEqual(recorder.record(.info, category: "a", message: .publicText("12345")), .protectedCapacity)
        XCTAssertEqual(store.snapshot()?.revision, before.revision)
        XCTAssertEqual(store.snapshot()?.events.map(\.message), ["1234567", "x"])
        clock.now += 30_000
        XCTAssertEqual(recorder.record(.info, category: "a", message: .publicText("12345")), .recorded)
        XCTAssertEqual(store.snapshot()?.events.map(\.message), ["x", "12345"])
        XCTAssertEqual(store.snapshot()?.retainedTextBytes, 8)
        XCTAssertEqual(before.events.count, 2)
    }
    func testProtectedPrefixSurvivesRepeatedRingWrapsWithoutReordering() throws {
        let clock = Clock(), store = try MemoryLogStore(recordLimit: 7, textByteLimit: 100, errorReserve: 2, clock: clock)
        let recorder = try LogRecorder(sink: store, eventsPerSecond: 1_000, clock: clock); try recorder.start()
        for index in 0..<1_000 {
            clock.now += 10
            XCTAssertEqual(recorder.record(index % 5 == 0 ? .error : .info, category: "a", message: .publicText("\(index)")), .recorded)
            let events = try XCTUnwrap(store.snapshot()).events
            XCTAssertLessThanOrEqual(events.count, 7)
            XCTAssertEqual(events.map(\.sequence), events.map(\.sequence).sorted())
            if index >= 5 {
                let newestError = index / 5 * 5
                XCTAssertTrue(events.contains { $0.message == "\(newestError)" })
                XCTAssertTrue(events.contains { $0.message == "\(newestError - 5)" })
            }
        }
    }
}
