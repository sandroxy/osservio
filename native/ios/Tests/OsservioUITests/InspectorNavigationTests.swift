#if canImport(UIKit)
import OsservioCore
import OsservioStore
@testable import OsservioUI
import UIKit
import XCTest

final class InspectorNavigationTests: XCTestCase {
    @MainActor
    func testFourDestinationsKeepIndependentQueriesAcrossPreferenceChanges() throws {
        let preferences = InspectorPreferences.shared, language = preferences.language, appearance = preferences.appearance
        defer { preferences.language = language; preferences.appearance = appearance }
        preferences.language = .chinese; preferences.appearance = .light
        let hostLanguage = UserDefaults.standard.stringArray(forKey: "AppleLanguages")
        let capture = CaptureController(); try capture.startDetailed()
        let logs = try MemoryLogStore(), requests = try MemoryHTTPStore(), sockets = try MemoryWebSocketStore()
        let logger = try LogRecorder(sink: logs, captureController: capture)
        XCTAssertEqual(logger.record(.info, category: "fixture", message: .publicText("retained query")), .recorded)
        let http = try HTTPRecorder(sink: requests, captureController: capture, privacy: HTTPPrivacyPolicy())
        let ws = try WebSocketRecorder(sink: sockets, captureController: capture, privacy: WebSocketPrivacyPolicy())
        let root = InspectorViewController(logRecorder: logger, logHistory: logs, httpRecorder: http, httpHistory: requests, webSocketRecorder: ws, webSocketHistory: sockets)
        let host = UIViewController(); host.overrideUserInterfaceStyle = .dark
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844)); window.rootViewController = host
        defer { window.isHidden = true; window.rootViewController = nil; _ = capture.stopAll() }
        host.loadViewIfNeeded(); host.addChild(root); host.view.addSubview(root.view); root.view.frame = host.view.bounds; root.didMove(toParent: host)
        window.makeKeyAndVisible(); window.layoutIfNeeded()
        XCTAssertEqual(root.viewControllers?.count, 4)
        for navigation in root.viewControllers?.compactMap({ $0 as? UINavigationController }) ?? [] {
            XCTAssertEqual(navigation.topViewController?.navigationItem.leftBarButtonItem?.customView?.accessibilityLabel, "关闭调试")
        }
        XCTAssertEqual(root.overrideUserInterfaceStyle, .light)
        XCTAssertEqual(root.viewControllers?.last?.tabBarItem.title, "应用")
        let log = try XCTUnwrap((root.viewControllers?.first as? UINavigationController)?.topViewController as? LogInspectorViewController)
        log.loadViewIfNeeded()
        let logSearch = try XCTUnwrap(descendants(log.view).compactMap { $0 as? UISearchTextField }.first)
        logSearch.text = "retained query"; try activate(logSearch, event: .editingChanged)
        root.selectedIndex = 1
        let network = try XCTUnwrap((root.selectedViewController as? UINavigationController)?.topViewController as? NetworkInspectorViewController)
        network.loadViewIfNeeded()
        let networkSearch = try XCTUnwrap(descendants(network.view).compactMap { $0 as? UISearchTextField }.first)
        networkSearch.text = "connection query"; try activate(networkSearch, event: .editingChanged)
        let header = try XCTUnwrap(descendants(network.view).compactMap { $0 as? InspectorFeedHeader }.first)
        var query = network.content.query; query.select(.status, value: "5xx"); header.onQueryChange?(query)
        query = network.content.query; query.select(.kind, value: "message"); header.onQueryChange?(query)
        XCTAssertEqual(network.content.query.text, "connection query"); XCTAssertNil(network.content.query.values[.status])
        network.content.scrollViewWillBeginDragging(network.tableView)
        root.selectedIndex = 3
        preferences.language = .english; preferences.appearance = .dark
        XCTAssertEqual(root.selectedIndex, 3); XCTAssertEqual(root.overrideUserInterfaceStyle, .dark)
        XCTAssertEqual(root.viewControllers?.map { $0.tabBarItem.title ?? "" }, ["Logs", "Network", "Problems", "App"])
        root.selectedIndex = 1
        XCTAssertEqual(networkSearch.text, "connection query"); XCTAssertTrue(network.content.paused)
        XCTAssertEqual(network.content.query.values[.transport], ["webSocket"])
        root.selectedIndex = 0
        XCTAssertEqual(logSearch.text, "retained query"); XCTAssertEqual(log.query.text, "retained query")
        XCTAssertEqual(host.overrideUserInterfaceStyle, .dark)
        XCTAssertEqual(UserDefaults.standard.stringArray(forKey: "AppleLanguages"), hostLanguage)
        XCTAssertEqual(capture.status()?.mode, .detailed); XCTAssertEqual(logs.snapshot()?.events.count, 1)
    }

    @MainActor
    func testGlobalDebugButtonsShareStateAndDoNotAppearInChildPages() throws {
        let capture = CaptureController(baselineEnabled: true)
        let logs = try MemoryLogStore(), http = try MemoryHTTPStore()
        let logger = try LogRecorder(sink: logs, captureController: capture)
        let root = try InspectorViewController(logRecorder: logger, logHistory: logs,
            httpRecorder: HTTPRecorder(sink: http, captureController: capture, privacy: HTTPPrivacyPolicy()), httpHistory: http)
        root.loadViewIfNeeded()
        let navigation = try XCTUnwrap(root.viewControllers?.first as? InspectorNavigationController)
        let button = try XCTUnwrap(navigation.topViewController?.navigationItem.leftBarButtonItem?.customView as? UIButton)
        XCTAssertFalse(button.isSelected)
        try activate(button, event: .touchUpInside)
        XCTAssertTrue(capture.status()?.debugEnabled == true)
        for tab in root.viewControllers?.compactMap({ $0 as? UINavigationController }) ?? [] {
            XCTAssertTrue((tab.viewControllers.first?.navigationItem.leftBarButtonItem?.customView as? UIButton)?.isSelected == true)
        }
        XCTAssertEqual(logger.record(.info, category: "test", message: .publicText("kept")), .recorded)
        let reader = InspectorContentViewController(title: "test", content: "kept", code: false)
        navigation.pushViewController(reader, animated: false)
        XCTAssertNil(reader.navigationItem.leftBarButtonItem)
        _ = navigation.popViewController(animated: false)
        try activate(button, event: .touchUpInside)
        XCTAssertEqual(capture.status()?.mode, .baseline)
        XCTAssertEqual(logs.snapshot()?.events.count, 1)
    }

    @MainActor
    func testIndependentControllersExposeMixedDebugStateAndConverge() throws {
        let first = CaptureController(debugEnabled: true), second = CaptureController()
        let control = InspectorDebugControl(controllers: [first, second, first])
        let button = try XCTUnwrap(control.item().customView as? UIButton)
        XCTAssertEqual(button.accessibilityValue, InspectorLocalization.text("ui_debug_partial"))
        let identity = first.status()?.sessionID
        try activate(button, event: .touchUpInside)
        XCTAssertEqual(first.status()?.sessionID, identity)
        XCTAssertTrue(second.status()?.debugEnabled == true)
        XCTAssertTrue(button.isSelected)
    }

    @MainActor
    func testFilterDraftSupportsUnionCancelAndExclusiveTime() throws {
        var applied: InspectorQuery?
        let original = InspectorQuery()
        let filters = InspectorFilterViewController(query: original,
            groups: [InspectorFilterGroup(.level, values: ["info", "warning", "error"])]) { applied = $0 }
        filters.loadViewIfNeeded()
        // All is row zero; fixed choices keep their semantic order.
        filters.tableView(filters.tableView, didSelectRowAt: IndexPath(row: 2, section: 0))
        filters.tableView(filters.tableView, didSelectRowAt: IndexPath(row: 3, section: 0))
        filters.tableView(filters.tableView, didSelectRowAt: IndexPath(row: 1, section: 1))
        filters.tableView(filters.tableView, didSelectRowAt: IndexPath(row: 2, section: 1))
        XCTAssertNil(applied); XCTAssertEqual(original.activeCount, 0)
        let apply = try XCTUnwrap(filters.navigationItem.rightBarButtonItem)
        _ = (apply.target as? NSObject)?.perform(try XCTUnwrap(apply.action))
        XCTAssertEqual(applied?.values[.level], ["error", "warning"]); XCTAssertEqual(applied?.recentMinutes, 30)
        let abandoned = InspectorFilterViewController(query: applied!, groups: []) { _ in XCTFail("Cancel applied a draft") }
        abandoned.loadViewIfNeeded()
        let cancel = try XCTUnwrap(abandoned.navigationItem.leftBarButtonItem)
        _ = (cancel.target as? NSObject)?.perform(try XCTUnwrap(cancel.action))
    }

    @MainActor
    func testReaderKeepsReadingSpaceWithLargeTextAndKeyboard() throws {
        let reader = InspectorContentViewController(title: "Fixture", content: String(repeating: "bounded content\n", count: 50), code: true)
        let host = UIViewController(), window = UIWindow(frame: CGRect(x: 0, y: 0, width: 568, height: 400))
        window.rootViewController = host; defer { window.isHidden = true; window.rootViewController = nil }
        host.loadViewIfNeeded(); host.addChild(reader)
        host.setOverrideTraitCollection(UITraitCollection(preferredContentSizeCategory: .accessibilityExtraExtraExtraLarge), forChild: reader)
        host.view.addSubview(reader.view); reader.didMove(toParent: host); window.makeKeyAndVisible()
        reader.view.frame = CGRect(x: 0, y: 0, width: 568, height: 300); reader.view.layoutIfNeeded()
        let text = try XCTUnwrap(descendants(reader.view).compactMap { $0 as? UITextView }.first)
        XCTAssertGreaterThanOrEqual(text.bounds.height, 100)
        NotificationCenter.default.post(name: UIResponder.keyboardWillChangeFrameNotification, object: nil,
            userInfo: [UIResponder.keyboardFrameEndUserInfoKey: CGRect(x: 0, y: 200, width: 568, height: 200)])
        reader.view.layoutIfNeeded()
        XCTAssertGreaterThanOrEqual(text.bounds.height, 44)
        XCTAssertLessThanOrEqual(text.frame.maxY, 201)
    }

    @MainActor private func descendants(_ view: UIView) -> [UIView] { [view] + view.subviews.flatMap { descendants($0) } }
    @MainActor private func tap(_ view: UIView, title: String) throws {
        for segment in descendants(view).compactMap({ $0 as? UISegmentedControl }) where !segment.isHidden {
            if let index = (0..<segment.numberOfSegments).first(where: { segment.titleForSegment(at: $0) == title }) {
                segment.selectedSegmentIndex = index; try activate(segment, event: .valueChanged); return
            }
        }
        let button = try XCTUnwrap(descendants(view).compactMap { $0 as? UIButton }.first {
            $0.accessibilityLabel == title || $0.title(for: .normal) == title
        })
        try activate(button, event: .touchUpInside)
    }
    @MainActor private func activate(_ control: UIControl, event: UIControl.Event) throws {
        var sent = false
        for target in control.allTargets {
            guard let receiver = target.base as? NSObject else { continue }
            for action in control.actions(forTarget: receiver, forControlEvent: event) ?? [] {
                _ = receiver.perform(NSSelectorFromString(action), with: control); sent = true
            }
        }
        XCTAssertTrue(sent)
    }
}
#endif
