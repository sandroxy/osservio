#if canImport(UIKit)
import UIKit
import XCTest
import OsservioCore
import OsservioStore
@testable import OsservioUI

final class InspectorMaterialTests: XCTestCase {
    @MainActor
    func testNativeBackSymbolRendersAccentAcrossNavigationAndAppearanceChanges() async throws {
        let preferences = InspectorPreferences.shared, palette = preferences.palette, appearance = preferences.appearance
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 700))
        defer {
            window.isHidden = true; window.rootViewController = nil
            preferences.palette = palette; preferences.appearance = appearance
        }
        for panel in [false, true] {
            let root = UIViewController(), detail = UIViewController(), nested = UIViewController()
            root.title = "Records"; detail.title = "Detail"; nested.title = "Content"
            let navigation = InspectorNavigationController(rootViewController: root)
            navigation.transientPanel = panel; window.rootViewController = navigation; window.makeKeyAndVisible()
            navigation.pushViewController(detail, animated: false)
            navigation.pushViewController(nested, animated: false)
            for mode in [InspectorPreferences.Appearance.light, .dark] {
                preferences.appearance = mode
                for color in [InspectorPalette.purple, .teal] {
                    preferences.palette = color
                    try await Task.sleep(nanoseconds: 50_000_000)
                    navigation.view.layoutIfNeeded(); navigation.navigationBar.layoutIfNeeded()
                    XCTAssertNil(nested.navigationItem.leftBarButtonItem)
                    XCTAssertTrue(navigation.navigationBar.backItem === detail.navigationItem)
                    let glyphs = backImages(in: navigation.navigationBar, bar: navigation.navigationBar)
                    XCTAssertEqual(glyphs.count, 1, "The native back indicator must remain visible")
                    for glyph in glyphs { assertRenderedColor(glyph, expected: InspectorTheme.accent) }
                }
            }
            XCTAssertTrue(navigation.popViewController(animated: false) === nested)
            try await Task.sleep(nanoseconds: 50_000_000)
            navigation.view.layoutIfNeeded()
            XCTAssertTrue(navigation.navigationBar.backItem === root.navigationItem)
            let glyphs = backImages(in: navigation.navigationBar, bar: navigation.navigationBar)
            XCTAssertEqual(glyphs.count, 1)
            for glyph in glyphs { assertRenderedColor(glyph, expected: InspectorTheme.accent) }
            XCTAssertNotNil(navigation.interactivePopGestureRecognizer)
            XCTAssertTrue(navigation.popViewController(animated: false) === detail)
            XCTAssertNil(navigation.navigationBar.backItem)
        }
    }

    @MainActor
    private func backImages(in view: UIView, bar: UINavigationBar) -> [UIImageView] {
        guard !view.isHidden, view.alpha > 0 else { return [] }
        if let image = view as? UIImageView, image.image != nil, !image.bounds.isEmpty,
           image.convert(image.bounds, to: bar).maxX < 90 { return [image] }
        return view.subviews.flatMap { backImages(in: $0, bar: bar) }
    }

    @MainActor
    func testGlassFilterSymbolRendersPaletteAfterAppearanceAndEnabledStateChanges() throws {
        guard #available(iOS 26, *), InspectorTheme.allowsTransparentMaterial else { throw XCTSkip("Transparent system glass is unavailable") }
        let previous = InspectorPreferences.shared.palette
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 700))
        let host = UIViewController(), header = InspectorFeedHeader(compact: true)
        defer { window.isHidden = true; window.rootViewController = nil; InspectorPreferences.shared.palette = previous }
        window.rootViewController = host
        header.frame = CGRect(x: 0, y: 50, width: 390, height: 170); host.view.addSubview(header)
        window.makeKeyAndVisible()
        for appearance in [UIUserInterfaceStyle.light, .dark] {
            window.overrideUserInterfaceStyle = appearance
            for palette in [InspectorPalette.purple, .teal] {
                InspectorPreferences.shared.palette = palette
                for active in [false, true] {
                    var query = InspectorQuery(); query.recentMinutes = active ? 5 : 0
                    header.relabel(paused: false, query: query)
                    header.filters.isHighlighted = true
                    header.filters.updateConfiguration()
                    header.filters.isHighlighted = false
                    header.filters.isEnabled = false
                    header.filters.updateConfiguration()
                    header.filters.isEnabled = true
                    header.filters.updateConfiguration()
                    host.view.layoutIfNeeded(); header.filters.layoutIfNeeded()
                    let image = try XCTUnwrap(header.filters.imageView)
                    assertRenderedColor(image, expected: InspectorTheme.accent)
                }
            }
        }
    }

    @MainActor
    private func assertRenderedColor(_ view: UIView, expected: UIColor, file: StaticString = #filePath, line: UInt = #line) {
        let format = UIGraphicsImageRendererFormat(); format.scale = 2
        let image = UIGraphicsImageRenderer(bounds: view.bounds, format: format).image { view.layer.render(in: $0.cgContext) }
        guard let bitmap = image.cgImage else { return XCTFail("Missing rendered foreground", file: file, line: line) }
        var bytes = [UInt8](repeating: 0, count: bitmap.width * bitmap.height * 4)
        bytes.withUnsafeMutableBytes { storage in
            guard let context = CGContext(data: storage.baseAddress, width: bitmap.width, height: bitmap.height, bitsPerComponent: 8,
                bytesPerRow: bitmap.width * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue) else {
                return XCTFail("Missing foreground context", file: file, line: line)
            }
            context.draw(bitmap, in: CGRect(x: 0, y: 0, width: bitmap.width, height: bitmap.height))
        }
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        expected.resolvedColor(with: view.traitCollection).getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        var opaque = 0, matches = 0
        for index in stride(from: 0, to: bytes.count, by: 4) where bytes[index + 3] > 250 {
            opaque += 1
            if abs(CGFloat(bytes[index]) / 255 - red) < 0.04 && abs(CGFloat(bytes[index + 1]) / 255 - green) < 0.04
                && abs(CGFloat(bytes[index + 2]) / 255 - blue) < 0.04 { matches += 1 }
        }
        XCTAssertGreaterThan(opaque, 0, "The native foreground must render", file: file, line: line)
        XCTAssertGreaterThanOrEqual(Double(matches) / Double(max(1, opaque)), 0.95,
            "The rendered foreground must retain its palette", file: file, line: line)
    }

    @MainActor
    func testFilterMaterialUpdatesKeepCountDescriptionAndSingleActionTarget() throws {
        guard #available(iOS 15, *) else { throw XCTSkip("Configured buttons require iOS 15") }
        let previous = InspectorPreferences.shared.palette
        defer { InspectorPreferences.shared.palette = previous }
        InspectorPreferences.shared.palette = .purple
        let header = InspectorFeedHeader(compact: true)
        let action = ButtonAction()
        header.filters.addTarget(action, action: #selector(ButtonAction.activate), for: .touchUpInside)
        var query = InspectorQuery(); query.recentMinutes = 5
        header.relabel(paused: false, query: query)
        XCTAssertEqual(header.filters.configuration?.title, "1")
        XCTAssertEqual(header.filters.accessibilityValue, InspectorFilterOptions.summary(query))
        InspectorPreferences.shared.palette = .teal
        let light = UITraitCollection(userInterfaceStyle: .light)
        XCTAssertEqual(header.filters.configuration?.baseForegroundColor?.resolvedColor(with: light), InspectorTheme.accent.resolvedColor(with: light))
        XCTAssertEqual(action.calls, 0)
        // This UIKit runner has no application event dispatcher. Check the native
        // target registration and invoke it directly; device journeys own input.
        let actions = header.filters.actions(forTarget: action, forControlEvent: .touchUpInside) ?? []
        XCTAssertEqual(actions.count, 1)
        for selector in actions { _ = action.perform(NSSelectorFromString(selector)) }
        XCTAssertEqual(action.calls, 1)
        header.relabel(paused: false, query: InspectorQuery())
        XCTAssertNil(header.filters.configuration?.title)
        XCTAssertNil(header.filters.accessibilityValue)
        XCTAssertEqual(action.calls, 1)
    }

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
