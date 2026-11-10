import Foundation
@testable import OsservioCore
import XCTest

final class CapturePermissionTests: XCTestCase {
    private final class Observer: CapturePermissionObserver {
        var values: [CapturePermission] = []
        var callback: (() -> Void)?
        func capturePermissionDidChange(_ permission: CapturePermission) {
            values.append(permission); callback?()
        }
    }

    func testInitialDeliveryFallbackReplacementAndExpiryPreserveAbsoluteDeadlines() throws {
        let clock = LogPipelineTests.Clock(); clock.now = 100
        let capture = CaptureController(clock: clock)
        let observer = Observer()
        try capture.observePermission(observer)
        try capture.startDetailed(durationMillis: 10)
        clock.now = 103
        XCTAssertTrue(capture.setBaselineEnabled(true))
        XCTAssertEqual(capture.status()?.remainingMillis, 7)
        XCTAssertEqual(observer.values.count, 3) // Reading an unexpired status adds no update.
        try capture.startDetailed(durationMillis: 20)
        clock.now = 123
        XCTAssertEqual(capture.status()?.mode, .baseline)
        XCTAssertTrue(capture.stopAll())
        XCTAssertEqual(observer.values.map(\.baselineEnabled), [false, false, true, true, true, false])
        XCTAssertEqual(observer.values.map(\.detailedDeadlineMillis), [0, 110, 110, 123, 0, 0])
        XCTAssertTrue(capture.stopAll())
        XCTAssertThrowsError(try capture.startDetailed(durationMillis: 0))
        XCTAssertEqual(observer.values.count, 6)
    }

    func testContinuousDebugPermissionDoesNotEnableBaselineOrInventDeadline() throws {
        let capture = CaptureController(debugEnabled: true)
        let observer = Observer()
        try capture.observePermission(observer)
        try capture.setDebugEnabled(true)
        XCTAssertEqual(observer.values.count, 1)
        XCTAssertFalse(observer.values[0].baselineEnabled)
        XCTAssertTrue(observer.values[0].continuousDebugEnabled)
        XCTAssertEqual(observer.values[0].detailedDeadlineMillis, 0)
        try capture.setDebugEnabled(false)
        XCTAssertFalse(observer.values[1].continuousDebugEnabled)
        XCTAssertFalse(observer.values[1].baselineEnabled)
    }

    func testBindingIsExclusiveWeakAndSerializesInitialDeliveryWithControl() throws {
        let capture = CaptureController(baselineEnabled: true)
        var first: Observer? = Observer()
        let second = Observer()
        try capture.observePermission(try XCTUnwrap(first))
        XCTAssertEqual(first?.values.first?.baselineEnabled, true)
        XCTAssertThrowsError(try capture.observePermission(second)) {
            XCTAssertEqual($0 as? CapturePermissionBindingError, .alreadyBound)
        }
        XCTAssertTrue(second.values.isEmpty)
        first = nil
        second.callback = {
            XCTAssertNil(capture.status())
            XCTAssertFalse(capture.stopAll())
        }
        try capture.observePermission(second)
        XCTAssertEqual(second.values.count, 1)
        XCTAssertEqual(capture.status()?.mode, .baseline)
        XCTAssertTrue(capture.stopAll())
        XCTAssertEqual(second.values.map(\.baselineEnabled), [true, false])
    }

    func testContendedBindingHasNoInitialDeliveryAndCanRetry() throws {
        let capture = CaptureController()
        let entered = DispatchSemaphore(value: 0), release = DispatchSemaphore(value: 0)
        let finished = expectation(description: "admission released")
        DispatchQueue.global().async {
            _ = capture.inspect { _ in entered.signal(); _ = release.wait(timeout: .now() + 3) }
            finished.fulfill()
        }
        XCTAssertEqual(entered.wait(timeout: .now() + 2), .success)
        let observer = Observer()
        XCTAssertThrowsError(try capture.observePermission(observer)) {
            XCTAssertEqual($0 as? CapturePermissionBindingError, .busy)
        }
        XCTAssertTrue(observer.values.isEmpty)
        release.signal(); wait(for: [finished], timeout: 2)
        try capture.observePermission(observer)
        XCTAssertEqual(observer.values.count, 1)
        XCTAssertFalse(observer.values[0].baselineEnabled)
    }
}
