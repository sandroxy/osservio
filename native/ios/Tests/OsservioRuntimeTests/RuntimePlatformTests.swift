#if canImport(UIKit)
import XCTest
import UIKit
import OsservioCore
import OsservioStore
import OsservioRuntime

final class RuntimePlatformTests: XCTestCase {
    @MainActor
    func testNotificationOwnershipAndTeardownDoNotReadNotificationContents() throws {
        let history = try MemoryBreadcrumbStore()
        let capture = CaptureController(baselineEnabled: true)
        let recorder = try BreadcrumbRecorder(sink: history, captureController: capture)
        let center = NotificationCenter()
        var signals: RuntimeSignals? = RuntimeSignals(recorder: recorder, notificationCenter: center)
        signals?.start(); signals?.start()
        center.post(name: UIApplication.didBecomeActiveNotification, object: nil, userInfo: ["secret": "never-read"])
        XCTAssertEqual(history.snapshot()?.events.count, 1)
        XCTAssertEqual(history.snapshot()?.events.first?.kind, .appActive)
        XCTAssertNil(history.snapshot()?.events.first?.message)
        signals?.stop(); signals?.stop()
        center.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        XCTAssertEqual(history.snapshot()?.events.count, 1)
        signals?.start()
        center.post(name: UIApplication.didReceiveMemoryWarningNotification, object: nil)
        XCTAssertEqual(history.snapshot()?.events.last?.kind, .memoryWarning)
        XCTAssertEqual(history.snapshot()?.events.count, 2)
        signals = nil
        center.post(name: UIApplication.didBecomeActiveNotification, object: nil)
        XCTAssertEqual(history.snapshot()?.events.count, 2)
    }

    @MainActor
    func testRuntimeSnapshotDeclaresPlatformLimitsWithoutEnablingBatteryMonitoring() async throws {
        let batteryMonitoring = UIDevice.current.isBatteryMonitoringEnabled
        let provider = RuntimeEnvironment()
        let result = await withCheckedContinuation { continuation in provider.read { continuation.resume(returning: $0) } }
        let snapshot = try result.get()
        XCTAssertEqual(snapshot.platform, .ios)
        XCTAssertEqual(snapshot.values.count, EnvironmentKey.allCases.count)
        guard case .number(let processID) = snapshot.values[.processID]?.fact else { return XCTFail("Process ID missing") }
        XCTAssertEqual(processID, Int64(ProcessInfo.processInfo.processIdentifier))
        for key in [EnvironmentKey.processProportionalBytes, .runtimeHeapBytes, .runtimeHeapLimitBytes] {
            guard case .unsupported = snapshot.values[key]?.fact else { return XCTFail("Platform metric must stay unsupported") }
        }
        if !batteryMonitoring {
            guard case .unavailable = snapshot.values[.batteryPercent]?.fact else { return XCTFail("Battery must not be fabricated") }
        }
        XCTAssertEqual(UIDevice.current.isBatteryMonitoringEnabled, batteryMonitoring)
        let second = await withCheckedContinuation { continuation in provider.read { continuation.resume(returning: $0) } }
        _ = try second.get()
    }
}
#endif
