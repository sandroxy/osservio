import OsservioCore
import OsservioStore
@testable import OsservioRuntime
import XCTest

final class NetworkPathSignalTests: XCTestCase {
    func testDeduplicationDoesNotCrossCaptureOrRegistrationLifetimes() throws {
        let capture = CaptureController(baselineEnabled: true), history = try MemoryBreadcrumbStore()
        let recorder = try BreadcrumbRecorder(sink: history, captureController: capture)
        let old = NetworkPathLease(recorder: recorder)
        old.record("status=available; interfaces=wifi"); old.record("status=available; interfaces=wifi")
        XCTAssertEqual(history.snapshot()?.events.count, 1)
        capture.stopAll(); old.record("status=unavailable"); XCTAssertEqual(history.snapshot()?.events.count, 1)
        capture.setBaselineEnabled(true); old.record("status=available; interfaces=wifi")
        old.invalidate(); old.record("status=unavailable")
        NetworkPathLease(recorder: recorder).record("status=unavailable")
        XCTAssertEqual(history.snapshot()?.events.count, 3)
        XCTAssertEqual(history.snapshot()?.events.last?.kind, .networkPath)
        XCTAssertEqual(history.snapshot()?.events.last?.retention, .restartRelevant)
    }
    private final class Sink: BreadcrumbSink, @unchecked Sendable {
        var writes = 0, rejectFirst = false
        var lease: NetworkPathLease?
        func append(_ event: BreadcrumbEvent) -> BreadcrumbWriteResult {
            writes += 1
            lease?.record("recursive"); lease?.invalidate()
            return rejectFirst && writes == 1 ? .busy : .stored
        }
    }
    func testFailedAdmissionIsRetriedOnlyOnAnotherCallback() throws {
        let sink = Sink(); sink.rejectFirst = true
        let recorder = try BreadcrumbRecorder(sink: sink, captureController: CaptureController(baselineEnabled: true))
        let lease = NetworkPathLease(recorder: recorder)
        lease.record("status=available"); lease.record("status=available"); lease.record("status=available")
        XCTAssertEqual(sink.writes, 2)
    }
    func testHostSinkCanInvalidateWithoutDeadlockOrRecursiveDelivery() throws {
        let sink = Sink(), recorder = try BreadcrumbRecorder(sink: sink, captureController: CaptureController(baselineEnabled: true))
        let lease = NetworkPathLease(recorder: recorder); sink.lease = lease
        lease.record("status=unavailable"); lease.record("late"); XCTAssertEqual(sink.writes, 1); sink.lease = nil
    }
}
