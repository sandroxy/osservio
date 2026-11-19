#if canImport(UIKit)
import UIKit
import XCTest
import OsservioCore
import OsservioStore
@testable import OsservioUI

final class InspectorMaterialTests: XCTestCase {
    @MainActor
    func testSegmentReflowAndAppearancePreserveSelectionWithoutSendingAnAction() throws {
        let previous = InspectorPreferences.shared.palette
        defer { InspectorPreferences.shared.palette = previous }
        let control = InspectorScopeControl(titles: ["All", "HTTP", "WebSocket"])
        var changes = 0; control.onSelect = { _ in changes += 1 }; control.selectedIndex = 2
        control.frame = CGRect(x: 0, y: 0, width: 340, height: 52); control.layoutIfNeeded()
        let segments = try XCTUnwrap(control.arrangedSubviews.first as? UISegmentedControl)
        XCTAssertFalse(segments.isHidden); XCTAssertEqual(segments.selectedSegmentIndex, 2)
        control.frame.size.width = 180; control.setNeedsLayout(); control.layoutIfNeeded()
        XCTAssertTrue(segments.isHidden)
        let choices = try XCTUnwrap(control.arrangedSubviews.last as? UIStackView)
        XCTAssertEqual(choices.axis, .vertical)
        let selected = try XCTUnwrap(choices.arrangedSubviews.last as? UIButton)
        XCTAssertEqual(selected.title(for: .normal), "WebSocket"); XCTAssertTrue(selected.isSelected)
        InspectorPreferences.shared.palette = .teal
        let light = UITraitCollection(userInterfaceStyle: .light)
        XCTAssertEqual(selected.titleColor(for: .selected)?.resolvedColor(with: light), InspectorTheme.accent.resolvedColor(with: light))
        XCTAssertEqual(control.selectedIndex, 2); XCTAssertEqual(changes, 0)
        control.frame.size.width = 340; control.setNeedsLayout(); control.layoutIfNeeded()
        XCTAssertFalse(segments.isHidden); XCTAssertEqual(segments.selectedSegmentIndex, 2); XCTAssertEqual(changes, 0)
    }

    @MainActor
    func testPrimaryActionKeepsItsLabelAndTargetAcrossAppearanceUpdates() throws {
        let button = InspectorCapsuleButton(type: .system)
        let action = ButtonAction(); button.addTarget(action, action: #selector(ButtonAction.activate), for: .touchUpInside)
        for (index, title) in ["记录问题", "Record a problem with the current diagnostic context"].enumerated() {
            button.configure(title: title, image: UIImage(systemName: "plus"))
            XCTAssertEqual(button.accessibilityLabel, title); XCTAssertEqual(action.calls, index)
            XCTAssertGreaterThanOrEqual(button.intrinsicContentSize.height, 48)
            for target in button.allTargets {
                for selector in button.actions(forTarget: target, forControlEvent: .touchUpInside) ?? [] {
                    _ = (target.base as? NSObject)?.perform(NSSelectorFromString(selector))
                }
            }
        }
        XCTAssertEqual(action.calls, 2)
    }
    @MainActor private final class ButtonAction: NSObject {
        var calls = 0
        @objc func activate() { calls += 1 }
    }

    @MainActor
    func testFloatingEntryUsesHostAppearanceIndependentlyOfInspectorPreference() async throws {
        let previous = InspectorPreferences.shared.appearance
        let host = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let root = UIViewController(), entry = InspectorEntryViewController()
        let window = InspectorEntryWindow(frame: host.frame)
        let observer = InspectorHostAppearanceView(entry: entry)
        defer {
            observer.unbind(); window.isHidden = true; window.rootViewController = nil
            host.isHidden = true; host.rootViewController = nil; InspectorPreferences.shared.appearance = previous
        }
        host.overrideUserInterfaceStyle = .light; host.rootViewController = root; host.makeKeyAndVisible()
        InspectorPreferences.shared.appearance = .dark
        window.rootViewController = entry; window.isHidden = false
        observer.bind(to: host)
        XCTAssertEqual(entry.entry.traitCollection.userInterfaceStyle, .light)
        let inspector = UIViewController(); InspectorTheme.apply(inspector)
        XCTAssertEqual(inspector.overrideUserInterfaceStyle, .dark)
        InspectorPreferences.shared.appearance = .light
        XCTAssertEqual(entry.overrideUserInterfaceStyle, .light)
        root.overrideUserInterfaceStyle = .dark
        try await Task.sleep(nanoseconds: 30_000_000)
        XCTAssertEqual(entry.entry.traitCollection.userInterfaceStyle, .dark)
        XCTAssertEqual(host.overrideUserInterfaceStyle, .light)
        root.overrideUserInterfaceStyle = .unspecified
        try await Task.sleep(nanoseconds: 30_000_000)
        XCTAssertEqual(entry.entry.traitCollection.userInterfaceStyle, .light)
        host.overrideUserInterfaceStyle = .dark
        try await Task.sleep(nanoseconds: 30_000_000)
        XCTAssertEqual(entry.entry.traitCollection.userInterfaceStyle, .dark)
        XCTAssertEqual(inspector.overrideUserInterfaceStyle, .dark)
        XCTAssertEqual(observer.bounds.size, .zero)
        XCTAssertFalse(observer.isUserInteractionEnabled); XCTAssertTrue(observer.accessibilityElementsHidden)
    }

    @MainActor
    func testFloatingAppearanceRebindsAfterHostRootReplacementAndStopsOnUnbind() async throws {
        let host = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let entry = InspectorEntryViewController(), observer = InspectorHostAppearanceView(entry: entry)
        weak var oldRoot: UIViewController?
        defer { observer.unbind(); host.isHidden = true; host.rootViewController = nil }
        host.rootViewController = UIViewController(); host.rootViewController?.overrideUserInterfaceStyle = .dark
        oldRoot = host.rootViewController; host.makeKeyAndVisible(); observer.bind(to: host)
        XCTAssertEqual(entry.overrideUserInterfaceStyle, .dark)
        let replacement = UIViewController(); replacement.overrideUserInterfaceStyle = .light
        host.rootViewController = replacement
        try await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertTrue(observer.superview === replacement.view)
        XCTAssertNil(oldRoot); XCTAssertEqual(entry.overrideUserInterfaceStyle, .light)
        observer.unbind(); XCTAssertNil(observer.superview)
        replacement.overrideUserInterfaceStyle = .dark
        try await Task.sleep(nanoseconds: 30_000_000)
        XCTAssertEqual(entry.overrideUserInterfaceStyle, .light)
    }

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
