#if canImport(UIKit)
import OsservioCore
import OsservioStore
@testable import OsservioUI
import UIKit
import XCTest

final class InspectorNavigationTests: XCTestCase {
    @MainActor
    func testFourDestinationsKeepIndependentQueriesAcrossPreferenceChanges() throws {
        let preferences = InspectorPreferences.shared, language = preferences.language, appearance = preferences.appearance, palette = preferences.palette
        defer { preferences.language = language; preferences.appearance = appearance; preferences.palette = palette }
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
        preferences.language = .english; preferences.appearance = .dark; preferences.palette = .blue
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
        XCTAssertFalse(button.accessibilityTraits.contains(.selected))
        try activate(button, event: .touchUpInside)
        XCTAssertTrue(capture.status()?.debugEnabled == true)
        for tab in root.viewControllers?.compactMap({ $0 as? UINavigationController }) ?? [] {
            XCTAssertTrue((tab.viewControllers.first?.navigationItem.leftBarButtonItem?.customView as? UIButton)?.accessibilityTraits.contains(.selected) == true)
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
        XCTAssertTrue(button.accessibilityTraits.contains(.selected))
    }

    @MainActor
    func testFilterDraftSupportsUnionCancelAndExclusiveTime() throws {
        var applied: InspectorQuery?
        let original = InspectorQuery()
        let filters = InspectorFilterViewController(query: original,
            groups: [InspectorFilterGroup(.level, values: ["info", "warning", "error"])]) { applied = $0 }
        filters.loadViewIfNeeded()
        try selectFilter(filters, title: InspectorFilterGroup(.level, values: []).title)
        try selectFilter(filters, title: InspectorFilterOptions.label(.level, "warning"))
        try selectFilter(filters, title: InspectorFilterOptions.label(.level, "error"))
        try selectFilter(filters, title: InspectorLocalization.text("ui_time"))
        try selectFilter(filters, title: InspectorLocalization.text("ui_time_5"))
        try selectFilter(filters, title: InspectorLocalization.text("ui_time"))
        try selectFilter(filters, title: InspectorLocalization.text("ui_time_30"))
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
    func testEmptyFilterGroupsDisappearButEvictedSelectionsRemainRemovable() throws {
        let empty = InspectorFilterGroup(.state, values: [])
        var query = InspectorQuery(); query.text = "keep search"; query.values[.host] = ["expired.test"]
        var applied: InspectorQuery?
        let filters = InspectorFilterViewController(query: query, groups: [empty, InspectorFilterGroup(.host, values: [])]) { applied = $0 }
        filters.loadViewIfNeeded()
        XCTAssertEqual(filters.numberOfSections(in: filters.tableView), 2)
        try selectFilter(filters, title: InspectorLocalization.text("ui_filter_refine"))
        try selectFilter(filters, title: InspectorFilterGroup(.host, values: []).title)
        try selectFilter(filters, title: "expired.test")
        let save = try XCTUnwrap(filters.navigationItem.rightBarButtonItem)
        _ = (save.target as? NSObject)?.perform(try XCTUnwrap(save.action))
        XCTAssertTrue(applied?.values[.host]?.isEmpty == true); XCTAssertEqual(applied?.text, "keep search")
        XCTAssertTrue(empty.options.isEmpty)
    }

    @MainActor
    func testContentDraftSurvivesExpansionAndProtocolSwitchClearsOnlyHTTPConditions() throws {
        var query = InspectorQuery(); query.text = "main search"; query.excludedText = "heartbeat"
        var applied: InspectorQuery?
        let filters = InspectorFilterViewController(query: query, groups: [InspectorFilterGroup(.transport, values: ["http", "webSocket"]),
            InspectorFilterGroup(.method, values: ["GET"])], payload: true) { applied = $0 }
        filters.loadViewIfNeeded()
        let editor = try XCTUnwrap(descendants(filters.view).compactMap { $0 as? InspectorFilterConditions }.first)
        editor.path.text = "/data/id"; editor.value.text = "42"; try activate(editor.path, event: .editingChanged)
        editor.duration.text = "100"; try activate(editor.duration, event: .editingChanged)
        XCTAssertEqual(editor.path.text, "/data/id")
        let cell = filters.tableView(filters.tableView, cellForRowAt: IndexPath(row: 0, section: 0))
        let scope = try XCTUnwrap(descendants(cell).compactMap { $0 as? InspectorScopeControl }.first)
        scope.onSelect?(2)
        XCTAssertEqual(editor.duration.text, ""); XCTAssertEqual(editor.path.text, "/data/id")
        let save = try XCTUnwrap(filters.navigationItem.rightBarButtonItem)
        _ = (save.target as? NSObject)?.perform(try XCTUnwrap(save.action))
        XCTAssertEqual(applied?.values[.transport], ["webSocket"]); XCTAssertEqual(applied?.minimumDurationMillis, 0)
        XCTAssertEqual(applied?.fieldValue, "42"); XCTAssertEqual(applied?.excludedText, "heartbeat"); XCTAssertEqual(applied?.text, "main search")
        XCTAssertEqual(query.activeCount, 1)
    }

    @MainActor
    private func selectFilter(_ filters: InspectorFilterViewController, title: String) throws {
        for section in 0..<filters.numberOfSections(in: filters.tableView) {
            for row in 0..<filters.tableView(filters.tableView, numberOfRowsInSection: section) {
                let path = IndexPath(row: row, section: section)
                if filters.tableView(filters.tableView, cellForRowAt: path).textLabel?.text == title {
                    filters.tableView(filters.tableView, didSelectRowAt: path); return
                }
            }
        }
        XCTFail("Missing filter row: \(title)")
    }

    @MainActor
    func testPreciseStatusPickerKeepsDraftAndFindsUnobservedCode() throws {
        var query = InspectorQuery(); query.select(.transport, value: "http")
        var applied: InspectorQuery?
        let groups = InspectorFilterOptions.network([], sockets: [], query: query)
        let filters = InspectorFilterViewController(query: query, groups: groups, payload: true) { applied = $0 }
        filters.loadViewIfNeeded()
        try selectFilter(filters, title: InspectorLocalization.text("ui_http_status"))
        let field = try XCTUnwrap(descendants(filters.tableView.tableHeaderView!).compactMap { $0 as? UISearchTextField }.first)
        field.text = "401"; try activate(field, event: .editingChanged)
        try selectFilter(filters, title: "401")
        XCTAssertNil(applied)
        let back = try XCTUnwrap(filters.navigationItem.leftBarButtonItem)
        _ = (back.target as? NSObject)?.perform(try XCTUnwrap(back.action))
        XCTAssertEqual(filters.title, InspectorLocalization.text("ui_filters"))
        let save = try XCTUnwrap(filters.navigationItem.rightBarButtonItem)
        _ = (save.target as? NSObject)?.perform(try XCTUnwrap(save.action))
        XCTAssertEqual(applied?.values[.status], ["401"])
    }

    @MainActor
    func testRuntimeKindsDoNotRequireANetworkScope() throws {
        let kind = BreadcrumbKind.appActive
        var applied: InspectorQuery?
        let filters = InspectorFilterViewController(query: InspectorQuery(), groups: [InspectorFilterGroup(.kind, values: [kind.rawValue])]) { applied = $0 }
        filters.loadViewIfNeeded()
        try selectFilter(filters, title: InspectorLocalization.text("ui_kind"))
        try selectFilter(filters, title: InspectorFilterOptions.label(.kind, kind.rawValue))
        let save = try XCTUnwrap(filters.navigationItem.rightBarButtonItem)
        _ = (save.target as? NSObject)?.perform(try XCTUnwrap(save.action))
        XCTAssertEqual(applied?.values[.kind], [kind.rawValue]); XCTAssertNil(applied?.transport)
    }

    @MainActor
    func testSavedConnectionContextSurvivesFilterReset() throws {
        let first = "00000000-0000-0000-0000-000000000001", second = "00000000-0000-0000-0000-000000000002"
        let events = try [first, second].enumerated().map { index, id in
            try WebSocketEvent.restore(id: id, connectionID: id, sessionID: first, sequence: Int64(index + 1), timestampMillis: 100,
                url: "wss://example.test/stream", source: .host, kind: .connecting, headers: [], headersLimited: false,
                statusCode: nil, closeCode: nil, failure: nil, direction: nil, outcome: nil, relatedMessageID: nil, payload: nil)
        }
        let saved = try IncidentWebSockets.restore(events: events, payloads: [:], observedAtMillis: 100, sourceEventCount: 2, sourceEvictedEvents: 0, sourceEvictedPayloads: 0)
        let viewer = IncidentEvidenceViewController(.webSockets(saved), connectionID: first); viewer.loadViewIfNeeded()
        let header = try XCTUnwrap(descendants(viewer.view).compactMap { $0 as? InspectorFeedHeader }.first)
        XCTAssertEqual(viewer.tableView(viewer.tableView, numberOfRowsInSection: 0), 1)
        var query = InspectorQuery(); query.select(.direction, value: "incoming"); header.onQueryChange?(query)
        XCTAssertEqual(viewer.tableView(viewer.tableView, numberOfRowsInSection: 0), 0)
        query.resetFilters(); header.onQueryChange?(query)
        XCTAssertEqual(viewer.tableView(viewer.tableView, numberOfRowsInSection: 0), 1)
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
