#if canImport(UIKit)
import UIKit
import XCTest
import OsservioCore
import OsservioStore
@testable import OsservioUI

final class InspectorMaterialTests: XCTestCase {
    @MainActor
    func testNavigationMaterialCanReturnToOpaqueWithoutReplacingPagesOrCapture() throws {
        let capture = CaptureController(debugEnabled: true)
        let logs = try MemoryLogStore(), requests = try MemoryHTTPStore()
        let root = try InspectorViewController(logRecorder: LogRecorder(sink: logs, captureController: capture), logHistory: logs,
            httpRecorder: HTTPRecorder(sink: requests, captureController: capture, privacy: HTTPPrivacyPolicy()), httpHistory: requests)
        root.loadViewIfNeeded(); root.selectedIndex = 1
        let pages = root.viewControllers ?? [], session = capture.status()?.sessionID
        root.enhancedMaterialsEnabled = false
        XCTAssertNil(root.tabBar.standardAppearance.backgroundEffect)
        XCTAssertEqual(root.tabBar.standardAppearance.backgroundColor, InspectorTheme.surface)
        root.enhancedMaterialsEnabled = true
        let expected = UITabBarAppearance()
        if #available(iOS 26, *), InspectorTheme.allowsTransparentMaterial { expected.configureWithDefaultBackground() }
        else { expected.configureWithOpaqueBackground(); expected.backgroundColor = InspectorTheme.surface }
        XCTAssertEqual(root.tabBar.standardAppearance.backgroundEffect, expected.backgroundEffect)
        XCTAssertEqual(root.tabBar.standardAppearance.backgroundColor, expected.backgroundColor)
        XCTAssertEqual(root.viewControllers, pages); XCTAssertEqual(root.selectedIndex, 1)
        XCTAssertEqual(capture.status()?.sessionID, session); XCTAssertTrue(capture.status()?.debugEnabled == true)
        _ = capture.stopAll()
    }
    @MainActor
    func testStyleObserverReactsToAccessibilityChangesAndIsReleased() {
        var updates = 0
        var observer: InspectorStyleObserver? = InspectorStyleObserver { updates += 1 }
        weak var retained = observer
        NotificationCenter.default.post(name: UIAccessibility.reduceTransparencyStatusDidChangeNotification, object: nil)
        NotificationCenter.default.post(name: UIAccessibility.darkerSystemColorsStatusDidChangeNotification, object: nil)
        XCTAssertEqual(updates, 2)
        observer = nil; XCTAssertNil(retained)
        retained = nil
        NotificationCenter.default.post(name: UIAccessibility.reduceTransparencyStatusDidChangeNotification, object: nil)
        XCTAssertEqual(updates, 2)
    }
}
#endif
