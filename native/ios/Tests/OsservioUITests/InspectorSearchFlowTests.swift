#if canImport(UIKit)
import OsservioCore
import OsservioStore
@testable import OsservioUI
import UIKit
import XCTest

final class InspectorSearchFlowTests: XCTestCase {
    @MainActor func testClearActionsDependOnRetainedDataRatherThanSearchMatches() async throws {
        let capture = CaptureController(debugEnabled: true), logs = try MemoryLogStore(), requests = try MemoryHTTPStore()
        let logger = try LogRecorder(sink: logs, captureController: capture)
        let http = try HTTPRecorder(sink: requests, captureController: capture, privacy: HTTPPrivacyPolicy())
        let log = LogInspectorViewController(recorder: logger, history: logs)
        let content = NetworkInspectorContent(http: (http, requests)), network = NetworkInspectorViewController(content: content)
        let navigation = InspectorNavigationController(rootViewController: log)
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = navigation; window.makeKeyAndVisible(); log.loadViewIfNeeded()
        defer { content.hide(); window.isHidden = true; window.rootViewController = nil; _ = capture.stopAll() }
        XCTAssertEqual(log.inspectorPrimaryAction?.isEnabled, false)
        _ = logger.record(.info, category: "fixture", message: .publicText("retained"))
        try await wait { log.inspectorPrimaryAction?.isEnabled == true }
        let logHeader = try XCTUnwrap(log.tableView.tableHeaderView as? InspectorFeedHeader)
        logHeader.onSearch?("no-match")
        try await wait { log.tableView.numberOfRows(inSection: 0) == 0 }
        XCTAssertEqual(log.inspectorPrimaryAction?.isEnabled, true)
        navigation.setViewControllers([network], animated: false); network.loadViewIfNeeded(); content.show()
        XCTAssertEqual(network.inspectorPrimaryAction?.isEnabled, false)
        _ = http.begin(method: "GET", url: "https://example.test/retained")
        try await wait { network.inspectorPrimaryAction?.isEnabled == true }
        let networkHeader = try XCTUnwrap(network.tableView.tableHeaderView as? InspectorFeedHeader)
        networkHeader.onSearch?("no-match")
        try await wait { network.tableView.numberOfRows(inSection: 0) == 0 }
        XCTAssertEqual(network.inspectorPrimaryAction?.isEnabled, true)
    }
    @MainActor func testCrossConnectionContentAndFieldSearchKeepCaptureAndContext() async throws {
        let capture = CaptureController(debugEnabled: true), sockets = try MemoryWebSocketStore()
        let recorder = try WebSocketRecorder(sink: sockets, captureController: capture, privacy: WebSocketPrivacyPolicy(publicURLPath: true, publicMessageText: true))
        for number in 1...2 {
            let token = try XCTUnwrap(recorder.begin(url: "wss://example.test/stream").token)
            XCTAssertEqual(recorder.text(token, direction: .incoming, outcome: .received, value: "{\"text\":\"shared-marker\",\"id\":\(number)}"), .recorded)
        }
        let content = NetworkInspectorContent(sockets: (recorder, sockets)), viewer = NetworkInspectorViewController(content: content)
        let navigation = InspectorNavigationController(rootViewController: viewer), window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = navigation; window.makeKeyAndVisible(); viewer.loadViewIfNeeded()
        defer { content.hide(); window.isHidden = true; window.rootViewController = nil; _ = capture.stopAll() }
        content.show()
        let header = try XCTUnwrap(viewer.tableView.tableHeaderView as? InspectorFeedHeader)
        header.onSearch?("shared-marker")
        try await wait { viewer.tableView.numberOfRows(inSection: 0) == 2 && header.status.text?.contains(InspectorLocalization.text("ui_searching")) != true }
        var query = content.query; query.fieldPath = "/id"; query.fieldValue = "2"; header.onQueryChange?(query)
        try await wait { viewer.tableView.numberOfRows(inSection: 0) == 1 }
        let cell = try XCTUnwrap(content.tableView(viewer.tableView, cellForRowAt: IndexPath(row: 0, section: 0)) as? InspectorEventCell)
        XCTAssertTrue(cell.accessibilityLabel?.contains("shared-marker") == true)
        content.tableView(viewer.tableView, didSelectRowAt: IndexPath(row: 0, section: 0))
        let detail = try XCTUnwrap(navigation.topViewController as? WebSocketDetailViewController)
        try await wait { navigation.transitionCoordinator == nil && detail.view.window != nil }
        XCTAssertNotNil(detail.showConnection); detail.showConnection?()
        XCTAssertTrue(navigation.topViewController is NetworkInspectorViewController)
        XCTAssertTrue(capture.status()?.debugEnabled == true)
        XCTAssertEqual(sockets.snapshot()?.events.count, 4)
    }
    @MainActor func testCancelledSearchCannotDeliverItsResultAfterReplacement() async throws {
        let started = expectation(description: "Worker started")
        let old = try XCTUnwrap(InspectorRecordSearchTask.submit(search: { cancelled in
            started.fulfill()
            let deadline = Date().addingTimeInterval(3)
            while !cancelled() && Date() < deadline { Thread.sleep(forTimeInterval: 0.001) }
            return cancelled() ? nil : InspectorRecordSearch.Result()
        }, completion: { _ in XCTFail("Cancelled search reached the UI") }))
        await fulfillment(of: [started], timeout: 2)
        old.cancel()
        let completed = expectation(description: "Replacement completed")
        let replacement = try XCTUnwrap(InspectorRecordSearchTask.submit(search: { _ in InspectorRecordSearch.Result() }, completion: { _ in completed.fulfill() }))
        await fulfillment(of: [completed], timeout: 2)
        withExtendedLifetime([old, replacement]) { }
    }
    @MainActor private func wait(_ condition: () -> Bool) async throws {
        for _ in 0..<150 { if condition() { return }; try await Task.sleep(nanoseconds: 20_000_000) }
        XCTFail("Search did not settle")
    }
}
#endif
