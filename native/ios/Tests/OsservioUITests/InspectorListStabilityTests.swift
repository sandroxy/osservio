#if canImport(UIKit)
import OsservioCore
import OsservioStore
@testable import OsservioUI
import UIKit
import XCTest

final class InspectorListStabilityTests: XCTestCase {
    @MainActor func testPreviewSlotKeepsGeometryAndResetsForNaturalContent() {
        let cell = InspectorEventCell(style: .default, reuseIdentifier: nil)
        func height(_ text: String, reserved: Bool) -> CGFloat {
            cell.configure(badge: "Socket", time: 1, title: text, subtitle: "stream", reservePreview: reserved)
            return cell.contentView.systemLayoutSizeFitting(CGSize(width: 260, height: 0),
                withHorizontalFittingPriority: .required, verticalFittingPriority: .fittingSizeLevel).height
        }
        for category in [UIContentSizeCategory.large, .accessibilityExtraExtraExtraLarge] {
            if #available(iOS 17, *) { cell.traitOverrides.preferredContentSizeCategory = category }
            cell.layoutIfNeeded()
            let reserved = height("文本消息", reserved: true)
            for text in ["", "missing", String(repeating: "long 消息内容 ", count: 30), String(repeating: "🙂👨‍👩‍👧‍👦汉Å", count: 30)] {
                XCTAssertEqual(height(text, reserved: true), reserved, accuracy: 1)
            }
            XCTAssertLessThan(height("short", reserved: false), reserved)
            XCTAssertGreaterThanOrEqual(reserved, height("汉🙂\n汉🙂\n汉🙂", reserved: false))
        }
    }

    @MainActor func testInsertionAndRemovedAnchorKeepTheNextVisibleRecordInPlace() throws {
        let controller = UITableViewController(style: .insetGrouped), table = InspectorTableView(frame: .zero, style: .insetGrouped)
        let source = IdentitySource(); controller.tableView = table; table.dataSource = source
        table.rowHeight = 70; table.estimatedRowHeight = 70
        let window = show(controller); defer { window.isHidden = true; window.rootViewController = nil }
        table.reloadData(); table.layoutIfNeeded(); table.scrollToRow(at: IndexPath(row: 40, section: 0), at: .top, animated: false)
        table.layoutIfNeeded()
        let first = try XCTUnwrap(table.indexPathsForVisibleRows?.sorted().first)
        let next = IndexPath(row: first.row + 1, section: 0), id = source.ids[next.row]
        let y = table.rectForRow(at: next).minY - table.contentOffset.y
        table.preservingReadingPosition(count: { source.ids.count }, key: { source.ids[$0] }) {
            source.ids.remove(at: first.row)
            source.ids.insert(contentsOf: ["new-a", "new-b", "new-c"], at: 0)
            table.reloadData()
        }
        table.layoutIfNeeded()
        let current = try XCTUnwrap(source.ids.firstIndex(of: id))
        XCTAssertEqual(table.rectForRow(at: IndexPath(row: current, section: 0)).minY - table.contentOffset.y, y, accuracy: 1)
    }

    @MainActor func testDelayedSocketPreviewsKeepEveryVisibleRowAndBoundReads() async throws {
        for scoped in [false, true] {
            let capture = CaptureController(debugEnabled: true), store = try MemoryWebSocketStore()
            let recorder = try WebSocketRecorder(sink: store, captureController: capture, privacy: WebSocketPrivacyPolicy())
            let token = try XCTUnwrap(recorder.begin(url: "wss://example.test/stream").token)
            for index in 0..<80 {
                XCTAssertEqual(recorder.text(token, direction: .incoming, outcome: .received,
                    value: "payload-\(index) " + String(repeating: "内容 mixed text ", count: 30)), .recorded)
            }
            let history = PreviewHistory(store: store)
            let content = NetworkInspectorContent(sockets: (recorder, history), connectionID: scoped ? token.connectionID : nil)
            let controller = NetworkInspectorViewController(content: content), window = show(controller)
            defer { history.release(); content.hide(); window.isHidden = true; window.rootViewController = nil; _ = capture.stopAll() }
            content.show()
            try await wait { content.table.numberOfRows(inSection: 0) >= 80 }
            content.scrollViewWillBeginDragging(content.table)
            content.table.scrollToRow(at: IndexPath(row: 20, section: 0), at: .top, animated: false); content.table.layoutIfNeeded()
            let positions = try XCTUnwrap(content.table.indexPathsForVisibleRows)
            XCTAssertGreaterThan(positions.count, 1)
            let frames = Dictionary(uniqueKeysWithValues: positions.map { ($0, content.table.rectForRow(at: $0).offsetBy(dx: 0, dy: -content.table.contentOffset.y)) })
            history.release()
            try await wait { positions.allSatisfy { content.table.cellForRow(at: $0)?.accessibilityLabel?.contains("payload-") == true } }
            content.table.layoutIfNeeded()
            for index in positions {
                let frame = content.table.rectForRow(at: index).offsetBy(dx: 0, dy: -content.table.contentOffset.y)
                XCTAssertEqual(frame.minY, frames[index]!.minY, accuracy: 1)
                XCTAssertEqual(frame.height, frames[index]!.height, accuracy: 1)
            }
            try await Task.sleep(nanoseconds: 600_000_000)
            XCTAssertLessThanOrEqual(history.readCount, 40, "Do not materialize the full retained payload window")
            content.hide(); let reads = history.readCount
            // The collapse animation can still move an attached table after polling stops.
            content.table.scrollToRow(at: IndexPath(row: 60, section: 0), at: .top, animated: false)
            content.table.layoutIfNeeded(); content.scrollViewDidScroll(content.table)
            try await Task.sleep(nanoseconds: 600_000_000)
            XCTAssertEqual(history.readCount, reads, "Hidden viewers must stop preview work")
        }
    }
    @MainActor func testReturningFromDetailKeepsPausedNetworkRows() async throws {
        let capture = CaptureController(debugEnabled: true), store = try MemoryWebSocketStore()
        let recorder = try WebSocketRecorder(sink: store, captureController: capture, privacy: WebSocketPrivacyPolicy())
        let token = try XCTUnwrap(recorder.begin(url: "wss://example.test/stream").token)
        for index in 0..<80 {
            XCTAssertEqual(recorder.text(token, direction: .incoming, outcome: .received,
                value: index.isMultiple(of: 2) ? "short-\(index)" : String(repeating: "内容 mixed text ", count: 30)), .recorded)
        }
        let content = NetworkInspectorContent(sockets: (recorder, store))
        let controller = NetworkInspectorViewController(content: content)
        let navigation = UINavigationController(rootViewController: controller)
        let tabs = UITabBarController(); tabs.viewControllers = [navigation]
        let window = show(tabs)
        defer { content.hide(); window.isHidden = true; window.rootViewController = nil; _ = capture.stopAll() }
        try await wait { content.table.numberOfRows(inSection: 0) >= 80 }
        content.scrollViewWillBeginDragging(content.table)
        content.table.scrollToRow(at: IndexPath(row: 20, section: 0), at: .top, animated: false)
        try await Task.sleep(nanoseconds: 600_000_000)
        content.table.layoutIfNeeded()
        let positions = try XCTUnwrap(content.table.indexPathsForVisibleRows)
        let frames = Dictionary(uniqueKeysWithValues: positions.map { ($0, content.table.rectForRow(at: $0).offsetBy(dx: 0, dy: -content.table.contentOffset.y)) })
        navigation.pushViewController(UIViewController(), animated: true)
        try await Task.sleep(nanoseconds: 500_000_000)
        navigation.popViewController(animated: true)
        try await Task.sleep(nanoseconds: 800_000_000)
        for index in positions {
            let frame = content.table.rectForRow(at: index).offsetBy(dx: 0, dy: -content.table.contentOffset.y)
            XCTAssertEqual(frame.minY, frames[index]!.minY, accuracy: 1)
            XCTAssertEqual(frame.height, frames[index]!.height, accuracy: 1)
        }
    }
    @MainActor func testCompletedSavedSearchRemainsVisibleAcrossNavigation() async throws {
        let capture = CaptureController(debugEnabled: true), store = try MemoryLogStore()
        let recorder = try LogRecorder(sink: store, captureController: capture)
        for index in 0..<80 {
            XCTAssertEqual(recorder.record(.info, category: "saved", message: .publicText("needle-\(index) " + String(repeating: "内容 ", count: index % 7))), .recorded)
        }
        let saved = try IncidentLogs.capture(XCTUnwrap(store.snapshot()), observedAtMillis: 1)
        let controller = IncidentEvidenceViewController(.logs(saved))
        let navigation = UINavigationController(rootViewController: controller), window = show(navigation)
        defer { window.isHidden = true; window.rootViewController = nil; _ = capture.stopAll() }
        let search = try XCTUnwrap(findSearch(controller.view))
        search.text = "needle"; search.sendActions(for: .editingChanged)
        try await wait { controller.tableView.numberOfRows(inSection: 0) == 80 }
        controller.tableView.scrollToRow(at: IndexPath(row: 25, section: 0), at: .top, animated: false)
        controller.tableView.layoutIfNeeded()
        let index = try XCTUnwrap(controller.tableView.indexPathsForVisibleRows?.sorted().first)
        let offset = controller.tableView.rectForRow(at: index).minY - controller.tableView.contentOffset.y
        navigation.pushViewController(UIViewController(), animated: false)
        navigation.popViewController(animated: false)
        XCTAssertEqual(controller.tableView.numberOfRows(inSection: 0), 80, "Returning must not blank a completed immutable search")
        controller.tableView.layoutIfNeeded()
        XCTAssertEqual(controller.tableView.rectForRow(at: index).minY - controller.tableView.contentOffset.y, offset, accuracy: 1)
    }
    @MainActor private func findSearch(_ view: UIView) -> UISearchTextField? {
        if let search = view as? UISearchTextField { return search }
        return view.subviews.lazy.compactMap(findSearch).first
    }
    @MainActor private func show(_ controller: UIViewController) -> UIWindow {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = controller; window.makeKeyAndVisible(); controller.loadViewIfNeeded(); window.layoutIfNeeded()
        return window
    }
    @MainActor private func wait(_ condition: () -> Bool) async throws {
        for _ in 0..<150 { if condition() { return }; try await Task.sleep(nanoseconds: 20_000_000) }
        XCTFail("List update did not settle")
    }
}

@MainActor private final class IdentitySource: NSObject, UITableViewDataSource {
    var ids = (0..<100).map(String.init)
    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { ids.count }
    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "identity") ?? UITableViewCell(style: .default, reuseIdentifier: "identity")
        cell.textLabel?.text = ids[indexPath.row]; return cell
    }
}
private final class PreviewHistory: WebSocketHistory, @unchecked Sendable {
    let store: MemoryWebSocketStore
    private let lock = NSLock(), gate = DispatchSemaphore(value: 0)
    private var released = false, reads = 0
    init(store: MemoryWebSocketStore) { self.store = store }
    var readCount: Int { lock.lock(); defer { lock.unlock() }; return reads }
    func release() { lock.lock(); released = true; lock.unlock(); gate.signal() }
    func snapshot() -> WebSocketSnapshot? { store.snapshot() }
    func payload(id: String) -> WebSocketPayloadLookup {
        XCTAssertFalse(Thread.isMainThread)
        lock.lock(); reads += 1; let wait = !released; lock.unlock()
        if wait { _ = gate.wait(timeout: .now() + 4) }
        return store.payload(id: id)
    }
    func clear() { XCTFail("Preview work must never clear records") }
}
#endif
