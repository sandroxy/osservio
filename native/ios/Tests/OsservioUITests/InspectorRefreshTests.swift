#if canImport(UIKit)
import OsservioCore
@testable import OsservioUI
import UIKit
import XCTest

final class InspectorRefreshTests: XCTestCase {
    @MainActor
    func testFeedReplacementResizesVisibleCellsAndRebindsOffscreenRows() async throws {
        let controller = UITableViewController(style: .insetGrouped)
        let table = InspectorTableView(frame: .zero, style: .insetGrouped), source = FeedSource()
        controller.tableView = table; table.dataSource = source
        table.rowHeight = UITableView.automaticDimension; table.estimatedRowHeight = 100
        table.followsLatest = true
        let window = show(controller)
        defer { dismiss(window) }
        table.reloadData(); table.layoutIfNeeded()
        let first = IndexPath(row: 0, section: 0), cell = table.cellForRow(at: first)
        let height = table.rectForRow(at: first).height
        source.expanded = true
        table.refreshFeed(rowCount: source.count); table.layoutIfNeeded()
        try await waitUntil { table.rectForRow(at: first).height > height }
        if #available(iOS 15, *) { XCTAssertTrue(table.cellForRow(at: first) === cell) }
        XCTAssertTrue(table.cellForRow(at: first)?.accessibilityLabel?.contains("updated-0") == true)
        XCTAssertEqual(table.contentOffset.y, -table.adjustedContentInset.top, accuracy: 1)
        table.followsLatest = false
        let distant = IndexPath(row: 80, section: 0)
        table.scrollToRow(at: distant, at: .top, animated: false); table.layoutIfNeeded()
        XCTAssertTrue(table.cellForRow(at: distant)?.accessibilityLabel?.contains("updated-80") == true)
        source.count = 3
        table.refreshFeed(rowCount: source.count); table.showLatest(); table.layoutIfNeeded()
        XCTAssertEqual(table.numberOfRows(inSection: 0), 3)
        XCTAssertNotNil(table.cellForRow(at: first))
    }

    @MainActor
    func testMinimizeStopsBeforeWindowHidesAndInterruptedResumeStartsOnlyOnce() async throws {
        let window = show(UIViewController()), foreign = UIWindow()
        defer { dismiss(window) }
        var count = 0
        let loop = InspectorRefreshLoop { count += 1 }
        loop.show(in: window.rootViewController!.view)
        NotificationCenter.default.post(name: InspectorSession.willCollapse, object: foreign)
        try await waitUntil { count > 1 }
        NotificationCenter.default.post(name: InspectorSession.willCollapse, object: window)
        XCTAssertFalse(window.isHidden, "Polling stops at the start of the exit animation")
        let suspended = count
        NotificationCenter.default.post(name: InspectorSession.willExpand, object: foreign)
        try await assertStable { count == suspended }
        NotificationCenter.default.post(name: InspectorSession.willExpand, object: window)
        XCTAssertEqual(count, suspended + 1)
        NotificationCenter.default.post(name: InspectorSession.willExpand, object: window)
        XCTAssertEqual(count, suspended + 1)
        loop.hide()
    }

    @MainActor
    func testApplicationInterruptionStopsPollingAndActivationResumesOnce() async throws {
        let window = show(UIViewController())
        defer { dismiss(window) }
        var count = 0
        let loop = InspectorRefreshLoop { count += 1 }
        loop.show(in: window.rootViewController!.view)
        let initial = count
        XCTAssertEqual(initial, 1)
        loop.show(in: window.rootViewController!.view)
        XCTAssertEqual(count, initial)
        try await waitUntil { count > initial }
        NotificationCenter.default.post(name: UIApplication.willResignActiveNotification, object: nil)
        let suspended = count
        // The injected will-notification deliberately leaves UIApplication's state active.
        try await assertStable { count == suspended }
        NotificationCenter.default.post(name: UIApplication.didBecomeActiveNotification, object: nil)
        XCTAssertEqual(count, suspended + 1)
        NotificationCenter.default.post(name: UIApplication.didBecomeActiveNotification, object: nil)
        XCTAssertEqual(count, suspended + 1)
        try await waitUntil { count > suspended + 1 }
        NotificationCenter.default.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        let background = count
        try await assertStable { count == background }
        loop.hide()
        NotificationCenter.default.post(name: UIApplication.didBecomeActiveNotification, object: nil)
        try await assertStable { count == background }
    }

    @MainActor
    func testDetachedOrHiddenWindowsDoNotReadAndReattachmentCanResume() async throws {
        let viewer = UIViewController(), window = show(viewer)
        defer { dismiss(window) }
        var count = 0
        let loop = InspectorRefreshLoop { count += 1 }
        loop.show(in: viewer.view)
        XCTAssertEqual(count, 1)
        window.isHidden = true
        let hidden = count
        try await assertStable { count == hidden }
        window.isHidden = false
        try await waitUntil { count > hidden }
        loop.hide()
        let detached = UIView()
        loop.show(in: detached)
        let absent = count
        NotificationCenter.default.post(name: UIApplication.didBecomeActiveNotification, object: nil)
        try await assertStable { count == absent }
        loop.show(in: viewer.view)
        XCTAssertEqual(count, absent + 1)
        loop.hide()
    }

    @MainActor
    func testRefreshMayReentrantlyHideAndReleaseDoesNotLeaveAPollingOwner() async throws {
        let window = show(UIViewController())
        defer { dismiss(window) }
        var count = 0
        var loop: InspectorRefreshLoop?
        loop = InspectorRefreshLoop {
            count += 1
            if count == 1 { loop?.hide() }
        }
        loop?.show(in: window.rootViewController!.view)
        try await assertStable { count == 1 }
        loop?.show(in: window.rootViewController!.view)
        XCTAssertEqual(count, 2)
        let reference = WeakLoop(loop)
        loop = nil
        XCTAssertNil(reference.value)
        NotificationCenter.default.post(name: UIApplication.didBecomeActiveNotification, object: nil)
        try await assertStable { count == 2 }
    }

    @MainActor
    func testActivationAndReappearancePreserveTheUsersPausedView() async throws {
        let probe = HistoryProbe(), recorder = try LogRecorder(sink: probe)
        let viewer = LogInspectorViewController(recorder: recorder, history: probe), window = show(viewer)
        defer { dismiss(window) }
        viewer.beginAppearanceTransition(true, animated: false); viewer.endAppearanceTransition()
        try await waitUntil { probe.readCount("log") > 0 }
        viewer.scrollViewWillBeginDragging(viewer.tableView)
        let pause = try XCTUnwrap(button(in: viewer.view, title: RuntimePresentation.text("ui_follow_latest")))
        XCTAssertEqual(pause.accessibilityLabel, RuntimePresentation.text("ui_follow_latest"))
        viewer.tableView.layoutIfNeeded()
        let origin = pause.convert(pause.bounds, to: window).minY
        viewer.tableView.setContentOffset(CGPoint(x: 0, y: 300), animated: false); viewer.tableView.layoutIfNeeded()
        XCTAssertEqual(pause.convert(pause.bounds, to: window).minY, origin, accuracy: 1)
        XCTAssertFalse(pause.isHidden)
        let frozen = probe.readCount("log")
        NotificationCenter.default.post(name: UIApplication.willResignActiveNotification, object: nil)
        NotificationCenter.default.post(name: UIApplication.didBecomeActiveNotification, object: nil)
        viewer.beginAppearanceTransition(false, animated: false); viewer.endAppearanceTransition()
        viewer.beginAppearanceTransition(true, animated: false); viewer.endAppearanceTransition()
        try await assertStable { probe.readCount("log") == frozen }
        XCTAssertEqual(pause.accessibilityLabel, RuntimePresentation.text("ui_follow_latest"))
        try activate(pause)
        XCTAssertGreaterThan(probe.readCount("log"), frozen)
        XCTAssertEqual(viewer.tableView.contentOffset.y, -viewer.tableView.adjustedContentInset.top, accuracy: 1)
        XCTAssertTrue(pause.isHidden)
    }

    @MainActor
    func testAllLiveViewersSuspendQueriesWithoutChangingCapture() async throws {
        for kind in ["log", "http", "websocket", "breadcrumb"] {
            let probe = HistoryProbe(), capture = CaptureController(baselineEnabled: true)
            try capture.startDetailed()
            let viewer: UIViewController
            switch kind {
            case "log": viewer = LogInspectorViewController(recorder: try LogRecorder(sink: probe, captureController: capture), history: probe)
            case "http": viewer = HTTPInspectorViewController(recorder: try HTTPRecorder(sink: probe, captureController: capture, privacy: HTTPPrivacyPolicy()), history: probe)
            case "websocket": viewer = WebSocketInspectorViewController(recorder: try WebSocketRecorder(sink: probe, captureController: capture, privacy: WebSocketPrivacyPolicy()), history: probe)
            default: viewer = BreadcrumbInspectorViewController(recorder: try BreadcrumbRecorder(sink: probe, captureController: capture), history: probe)
            }
            let window = show(viewer)
            defer { dismiss(window) }
            viewer.beginAppearanceTransition(true, animated: false); viewer.endAppearanceTransition()
            try await waitUntil { probe.readCount(kind) > 0 }
            let before = probe.readCount(kind)
            try await waitUntil { probe.readCount(kind) > before }
            NotificationCenter.default.post(name: UIApplication.willResignActiveNotification, object: nil)
            let suspended = probe.readCount(kind)
            try await assertStable { probe.readCount(kind) == suspended }
            XCTAssertEqual(capture.status()?.mode, .detailed)
            XCTAssertTrue(capture.status()?.baselineEnabled == true)
            NotificationCenter.default.post(name: UIApplication.didBecomeActiveNotification, object: nil)
            try await waitUntil { probe.readCount(kind) > suspended }
            viewer.beginAppearanceTransition(false, animated: false); viewer.endAppearanceTransition()
            let hidden = probe.readCount(kind)
            NotificationCenter.default.post(name: UIApplication.didBecomeActiveNotification, object: nil)
            try await assertStable { probe.readCount(kind) == hidden }
            XCTAssertEqual(capture.status()?.mode, .detailed)
            XCTAssertTrue(capture.status()?.baselineEnabled == true)
        }
    }

    @MainActor private func show(_ viewer: UIViewController) -> UIWindow {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = viewer
        window.makeKeyAndVisible(); viewer.loadViewIfNeeded(); window.layoutIfNeeded()
        return window
    }
    @MainActor private func button(in view: UIView, title: String) -> UIButton? {
        if let button = view as? UIButton, button.accessibilityLabel == title { return button }
        return view.subviews.lazy.compactMap { self.button(in: $0, title: title) }.first
    }
    @MainActor private func activate(_ control: UIControl) throws {
        // Package tests have no application event dispatcher. Invoke the registered target/action.
        var invoked = false
        for target in control.allTargets {
            let receiver = try XCTUnwrap(target.base as? NSObject)
            for action in control.actions(forTarget: receiver, forControlEvent: .touchUpInside) ?? [] {
                _ = receiver.perform(NSSelectorFromString(action), with: control); invoked = true
            }
        }
        XCTAssertTrue(invoked)
    }
    @MainActor private func dismiss(_ window: UIWindow) {
        window.isHidden = true; window.rootViewController = nil
        NotificationCenter.default.post(name: UIApplication.didBecomeActiveNotification, object: nil)
    }
    @MainActor private func waitUntil(file: StaticString = #filePath, line: UInt = #line, _ condition: () -> Bool) async throws {
        for _ in 0..<100 {
            if condition() { return }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        XCTFail("Visible-list polling did not reach the expected state", file: file, line: line)
    }
    @MainActor private func assertStable(file: StaticString = #filePath, line: UInt = #line, _ condition: () -> Bool) async throws {
        // Cross two actual 250 ms timer intervals, allowing queued MainActor work to run.
        for _ in 0..<30 {
            try await Task.sleep(nanoseconds: 20_000_000)
            guard condition() else { XCTFail("Polling continued while suspended", file: file, line: line); return }
        }
    }
}

@MainActor
private final class FeedSource: NSObject, UITableViewDataSource {
    var count = 100, expanded = false
    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { count }
    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "feed") as? InspectorEventCell
            ?? InspectorEventCell(style: .default, reuseIdentifier: "feed")
        cell.configure(badge: "INFO", time: 1, title: expanded ? "updated-\(indexPath.row) " + String(repeating: "long content ", count: 40) : "short", subtitle: "source")
        return cell
    }
}

private final class WeakLoop {
    weak var value: InspectorRefreshLoop?
    init(_ value: InspectorRefreshLoop?) { self.value = value }
}

private final class HistoryProbe: LogHistory, HTTPHistory, WebSocketHistory, BreadcrumbHistory,
    LogSink, HTTPSink, WebSocketSink, BreadcrumbSink, @unchecked Sendable {
    private let lock = NSLock()
    private var counts: [String: Int] = [:]
    func readCount(_ kind: String) -> Int { lock.lock(); defer { lock.unlock() }; return counts[kind, default: 0] }
    private func read(_ kind: String) { lock.lock(); counts[kind, default: 0] += 1; lock.unlock() }
    func snapshot() -> LogSnapshot? { read("log"); return nil }
    func snapshot() -> HTTPSnapshot? { read("http"); return nil }
    func snapshot() -> WebSocketSnapshot? { read("websocket"); return nil }
    func snapshot() -> BreadcrumbSnapshot? { read("breadcrumb"); return nil }
    func body(id: String) -> HTTPBodyLookup { XCTFail("Polling must not read bodies"); return .unavailable }
    func payload(id: String) -> WebSocketPayloadLookup { XCTFail("Polling must not read payloads"); return .unavailable }
    func clear() { XCTFail("Visibility must not clear history") }
    func append(_ event: LogEvent) -> LogWriteResult { .stored }
    func write(_ update: HTTPUpdate) -> HTTPWriteResult { .stored }
    func append(_ event: WebSocketEvent, payload: WebSocketPayload?) -> WebSocketWriteResult { .stored }
    func append(_ event: BreadcrumbEvent) -> BreadcrumbWriteResult { .stored }
}
#endif
