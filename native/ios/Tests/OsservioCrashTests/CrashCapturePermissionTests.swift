import Foundation
import OsservioCore
@testable import OsservioCrash
import OsservioCrashSupport
import XCTest

final class CrashCapturePermissionTests: XCTestCase {
    private final class Observer: CapturePermissionObserver {
        func capturePermissionDidChange(_ permission: CapturePermission) {}
    }
    private struct CustomClock: LogClock {
        func monotonicMillis() -> UInt64 { 100 }
        func wallTimeMillis() -> Int64 { 100 }
    }
    private final class ControllerReference {
        weak var value: CaptureController?
        init(_ value: CaptureController?) { self.value = value }
    }

    func testCoreControlsNativePermissionWithoutPollingOrInstallingHandlers() throws {
        let capture = CaptureController()
        let permission = try CrashCapturePermission(captureController: capture)
        defer { withExtendedLifetime(permission) {} }
        XCTAssertFalse(OsservioCrashCaptureAllowsRecording())
        XCTAssertTrue(capture.setBaselineEnabled(true))
        XCTAssertTrue(OsservioCrashCaptureAllowsRecording())
        try capture.startDetailed(durationMillis: 10_000)
        XCTAssertTrue(capture.setBaselineEnabled(false))
        XCTAssertTrue(OsservioCrashCaptureAllowsRecording())
        XCTAssertTrue(capture.stopDetailed())
        XCTAssertFalse(OsservioCrashCaptureAllowsRecording())
        try capture.startDetailed(durationMillis: 1)
        Thread.sleep(forTimeInterval: 0.01)
        XCTAssertFalse(OsservioCrashCaptureAllowsRecording()) // No intervening Core status read.
        XCTAssertTrue(capture.setBaselineEnabled(true))
        try capture.startDetailed(durationMillis: 1)
        Thread.sleep(forTimeInterval: 0.01)
        XCTAssertTrue(OsservioCrashCaptureAllowsRecording())
        XCTAssertTrue(capture.stopAll())
        XCTAssertFalse(OsservioCrashCaptureAllowsRecording())
    }

    func testFailedSecondOwnerCannotChangeTheExistingPermission() throws {
        let capture = CaptureController(baselineEnabled: true)
        var owner: CrashCapturePermission? = try CrashCapturePermission(captureController: capture)
        XCTAssertNotNil(owner)
        XCTAssertThrowsError(try CrashCapturePermission(captureController: CaptureController())) {
            XCTAssertEqual($0 as? CrashCapturePermissionError, .alreadyOwned)
        }
        XCTAssertTrue(OsservioCrashCaptureAllowsRecording())
        owner = nil
        XCTAssertFalse(OsservioCrashCaptureAllowsRecording())
        XCTAssertTrue(capture.setBaselineEnabled(false))
        XCTAssertTrue(capture.setBaselineEnabled(true))
        XCTAssertFalse(OsservioCrashCaptureAllowsRecording())
        let next = try CrashCapturePermission(captureController: capture)
        withExtendedLifetime(next) { XCTAssertTrue(OsservioCrashCaptureAllowsRecording()) }
    }

    func testUnsupportedClockAndFailedBindingLeaveOwnershipAvailable() throws {
        XCTAssertThrowsError(try CrashCapturePermission(captureController: CaptureController(clock: CustomClock()))) {
            XCTAssertEqual($0 as? CrashCapturePermissionError, .unsupportedClock)
        }
        let occupied = CaptureController(baselineEnabled: true), observer = Observer()
        try occupied.observePermission(observer)
        XCTAssertThrowsError(try CrashCapturePermission(captureController: occupied)) {
            XCTAssertEqual($0 as? CapturePermissionBindingError, .alreadyBound)
        }
        XCTAssertFalse(OsservioCrashCaptureAllowsRecording())
        let next = try CrashCapturePermission(captureController: CaptureController(baselineEnabled: true))
        withExtendedLifetime(next) { XCTAssertTrue(OsservioCrashCaptureAllowsRecording()) }
        withExtendedLifetime(observer) {}
    }

    func testPermissionRetainsItsControllerAndRevokesOnRelease() throws {
        var capture: CaptureController? = CaptureController(baselineEnabled: true)
        let reference = ControllerReference(capture)
        var permission: CrashCapturePermission? = try CrashCapturePermission(captureController: XCTUnwrap(capture))
        XCTAssertNotNil(permission)
        capture = nil
        XCTAssertNotNil(reference.value)
        XCTAssertTrue(OsservioCrashCaptureAllowsRecording())
        permission = nil
        XCTAssertNil(reference.value)
        XCTAssertFalse(OsservioCrashCaptureAllowsRecording())
    }

    func testPreparationAndPermanentFailureCannotBeOverriddenByCoreUpdates() throws {
        let capture = CaptureController(baselineEnabled: true)
        let permission = try CrashCapturePermission(captureController: capture, initiallyArmed: false)
        defer { withExtendedLifetime(permission) {} }
        XCTAssertFalse(OsservioCrashCaptureAllowsRecording())
        try capture.startDetailed(durationMillis: 1)
        XCTAssertTrue(capture.setBaselineEnabled(false))
        Thread.sleep(forTimeInterval: 0.01)
        permission.arm()
        XCTAssertFalse(OsservioCrashCaptureAllowsRecording(), "Arming must not renew an expired detailed deadline")
        XCTAssertTrue(capture.setBaselineEnabled(true)); XCTAssertTrue(OsservioCrashCaptureAllowsRecording())
        permission.disablePermanently()
        XCTAssertFalse(OsservioCrashCaptureAllowsRecording())
        XCTAssertTrue(capture.stopAll()); XCTAssertTrue(capture.setBaselineEnabled(true))
        try capture.startDetailed(durationMillis: 10_000)
        permission.arm()
        XCTAssertFalse(OsservioCrashCaptureAllowsRecording(), "A failed installation stays disarmed despite later policy changes")
    }
}
