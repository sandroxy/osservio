import OsservioCore
import OsservioStore
@testable import OsservioRuntime
import XCTest

final class RuntimeSignalTests: XCTestCase {
    func testInvalidatedCallbackCannotEnterReplacementLease() throws {
        let capture = CaptureController(baselineEnabled: true)
        let history = try MemoryBreadcrumbStore()
        let recorder = try BreadcrumbRecorder(sink: history, captureController: capture)
        let old = SignalLease(recorder: recorder)
        old.record(.appActive, label: "app.active")
        old.invalidate()
        let replacement = SignalLease(recorder: recorder)
        old.record(.appBackground, label: "late")
        replacement.record(.appForeground, label: "new")
        XCTAssertEqual(history.snapshot()?.events.map(\.label), ["app.active", "new"])
        XCTAssertTrue(history.snapshot()?.events.allSatisfy { $0.retention == .restartRelevant } == true)
        capture.stopAll()
        replacement.record(.appBackground, label: "off")
        XCTAssertEqual(history.snapshot()?.events.count, 2)
    }
    private final class Sink: BreadcrumbSink, @unchecked Sendable {
        var lease: SignalLease?
        var count = 0
        func append(_ event: BreadcrumbEvent) -> BreadcrumbWriteResult {
            lease?.invalidate(); count += 1; return .stored
        }
    }
    func testHostSinkControlReentryCannotDeadlockSignalDelivery() throws {
        let sink = Sink()
        let recorder = try BreadcrumbRecorder(sink: sink, captureController: CaptureController(baselineEnabled: true))
        let lease = SignalLease(recorder: recorder); sink.lease = lease
        lease.record(.appActive, label: "first")
        lease.record(.appBackground, label: "after-stop")
        XCTAssertEqual(sink.count, 1)
        sink.lease = nil
    }
}
