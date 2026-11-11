#if canImport(UIKit)
import Foundation
import OsservioCore
import OsservioStore
@testable import OsservioUI
import UIKit
import XCTest

final class IncidentShareTests: XCTestCase {
    @MainActor
    func testAbsentExporterDoesNotOfferSharing() throws {
        let viewer = IncidentSnapshotViewController(snapshot: try IncidentSnapshot.capture())
        viewer.loadViewIfNeeded()
        XCTAssertEqual(viewer.navigationItem.rightBarButtonItems?.count, 1)
        XCTAssertEqual(viewer.navigationItem.rightBarButtonItems?.first?.accessibilityLabel, RuntimePresentation.text("archive_copy_summary"))
    }

    @MainActor
    func testFailureAndRetryKeepFrozenSnapshotAndBackgroundingRejectsLateSuccess() async throws {
        let exporter = ControlledExporter(), snapshot = try IncidentSnapshot.restore(id: UUID().uuidString, timestampMillis: 123,
            note: nil, environment: nil, breadcrumbs: nil)
        let viewer = IncidentSnapshotViewController(snapshot: snapshot, exporter: exporter), window = show(viewer)
        defer { window.isHidden = true; window.rootViewController = nil }
        let share = try XCTUnwrap(viewer.navigationItem.rightBarButtonItems?.first)
        try send(share)
        XCTAssertEqual(share.title, RuntimePresentation.text("archive_cancel"))
        try await waitUntil("First preparation was never admitted") { exporter.count == 1 }
        XCTAssertEqual(exporter.snapshot(at: 0).id, snapshot.id)
        XCTAssertEqual(exporter.snapshot(at: 0).timestampMillis, 123)
        exporter.finish(0, .failure(.tooLarge))
        try await waitUntil("Export failure was not presented") { status(viewer) == RuntimePresentation.text("incident_export_too_large") }
        XCTAssertTrue(share.isEnabled)
        try send(share); try await waitUntil("Retry was never admitted") { exporter.count == 2 }
        XCTAssertEqual(exporter.snapshot(at: 1).id, snapshot.id); XCTAssertEqual(exporter.snapshot(at: 1).timestampMillis, 123)
        NotificationCenter.default.post(name: UIApplication.willResignActiveNotification, object: nil)
        XCTAssertTrue(exporter.cancelled(at: 1)); XCTAssertTrue(share.isEnabled)
        exporter.finish(1, .success(try IncidentExportFile(url: URL(fileURLWithPath: "/unopened-fixture.zip"), byteCount: 1)))
        try await waitUntil { viewer.presentedViewController == nil }
        for _ in 0..<5 { await Task.yield() }
        XCTAssertNil(viewer.presentedViewController)
    }

    @MainActor
    func testLeavingTheSnapshotCancelsPreparationAndIgnoresItsCompletion() async throws {
        let exporter = ControlledExporter(), viewer = IncidentSnapshotViewController(snapshot: try IncidentSnapshot.capture(), exporter: exporter)
        let window = show(viewer); defer { window.isHidden = true; window.rootViewController = nil }
        try send(XCTUnwrap(viewer.navigationItem.rightBarButtonItems?.first))
        try await waitUntil { exporter.count == 1 }
        let navigation = try XCTUnwrap(viewer.navigationController)
        // Swift package tests have no application event dispatcher to drive appearance.
        viewer.beginAppearanceTransition(false, animated: false)
        navigation.pushViewController(UIViewController(), animated: false)
        viewer.endAppearanceTransition()
        XCTAssertTrue(exporter.cancelled(at: 0))
        exporter.finish(0, .failure(.unavailable))
        for _ in 0..<5 { await Task.yield() }
        XCTAssertNil(navigation.topViewController?.presentedViewController)
        XCTAssertNil(viewer.presentedViewController)
    }

    @MainActor
    func testImmediateRejectionRestoresTheShareControl() async throws {
        let exporter = ControlledExporter(immediate: .busy)
        let viewer = IncidentSnapshotViewController(snapshot: try IncidentSnapshot.capture(), exporter: exporter), window = show(viewer)
        defer { window.isHidden = true; window.rootViewController = nil }
        let share = try XCTUnwrap(viewer.navigationItem.rightBarButtonItems?.first); try send(share)
        try await waitUntil { status(viewer) == RuntimePresentation.text("incident_export_busy") }
        XCTAssertTrue(share.isEnabled)
        XCTAssertNil(share.title); XCTAssertNotNil(share.image)
    }

    @MainActor
    func testCancelControlClearsPreparationAndKeepsLateFailureOutOfTheView() async throws {
        let exporter = ControlledExporter()
        let viewer = IncidentSnapshotViewController(snapshot: try IncidentSnapshot.capture(), exporter: exporter), window = show(viewer)
        defer { window.isHidden = true; window.rootViewController = nil }
        let share = try XCTUnwrap(viewer.navigationItem.rightBarButtonItems?.first); try send(share)
        try await waitUntil { exporter.count == 1 }
        XCTAssertEqual(status(viewer), RuntimePresentation.text("incident_export_preparing"))
        try send(share)
        XCTAssertTrue(exporter.cancelled(at: 0)); XCTAssertNil(status(viewer)); XCTAssertNil(share.title)
        exporter.finish(0, .failure(.unavailable))
        for _ in 0..<5 { await Task.yield() }
        XCTAssertNil(status(viewer)); XCTAssertNil(viewer.presentedViewController)
    }

    @MainActor
    func testRetiringAccountInspectorCancelsPreparationAndRejectsLateDelivery() async throws {
        let exporter = ControlledExporter(), capture = CaptureController(debugEnabled: true)
        let logs = try MemoryLogStore(), requests = try MemoryHTTPStore()
        let logger = try LogRecorder(sink: logs, captureController: capture)
        let http = try HTTPRecorder(sink: requests, captureController: capture, privacy: HTTPPrivacyPolicy())
        let host = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        host.rootViewController = UIViewController(); host.makeKeyAndVisible()
        let inspector = InspectorViewController(logRecorder: logger, logHistory: logs, httpRecorder: http, httpHistory: requests)
        let session = InspectorSession(window: host) { inspector }
        defer { session.dispose(); _ = capture.close(); host.isHidden = true; host.rootViewController = nil }
        XCTAssertTrue(session.expand())
        let navigation = try XCTUnwrap(inspector.viewControllers?.first as? UINavigationController)
        let viewer = IncidentSnapshotViewController(snapshot: try IncidentSnapshot.capture(), exporter: exporter)
        navigation.pushViewController(viewer, animated: false); viewer.loadViewIfNeeded()
        inspector.view.window?.layoutIfNeeded()
        try await waitUntil { viewer.view.window != nil && navigation.transitionCoordinator == nil }
        try send(XCTUnwrap(viewer.inspectorActions?.first))
        try await waitUntil { exporter.count == 1 }
        session.dispose(); XCTAssertTrue(capture.close()); XCTAssertTrue(exporter.cancelled(at: 0))
        XCTAssertFalse(session.expand()); XCTAssertTrue(host.isKeyWindow)
        exporter.finish(0, .success(try IncidentExportFile(url: URL(fileURLWithPath: "/unopened-fixture.zip"), byteCount: 1)))
        for _ in 0..<5 { await Task.yield() }
        XCTAssertNil(viewer.presentedViewController); XCTAssertNil(host.rootViewController?.presentedViewController)
    }

    @MainActor private func status(_ viewer: IncidentSnapshotViewController) -> String? {
        (viewer.tableView.tableHeaderView?.subviews.first as? UILabel)?.text
    }
    @MainActor private func show(_ viewer: UIViewController) -> UIWindow {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = UINavigationController(rootViewController: viewer)
        window.makeKeyAndVisible(); viewer.loadViewIfNeeded(); window.layoutIfNeeded(); return window
    }
    @MainActor private func send(_ item: UIBarButtonItem) throws {
        let receiver = try XCTUnwrap(item.target as? NSObject), action = try XCTUnwrap(item.action)
        _ = receiver.perform(action)
    }
    @MainActor private func waitUntil(_ message: String = "Share presentation did not reach the expected state",
                                     file: StaticString = #filePath, line: UInt = #line, _ condition: @MainActor () -> Bool) async throws {
        for _ in 0..<150 {
            if condition() { return }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        XCTFail(message, file: file, line: line)
        throw PresentationTimeout.expired
    }
}

private enum PresentationTimeout: Error { case expired }
private final class ControlledExporter: IncidentExporter, @unchecked Sendable {
    private struct Attempt {
        let snapshot: IncidentSnapshot
        let completion: @Sendable (Result<IncidentExportFile, IncidentExportError>) -> Void
        let task: ControlledTask
    }
    private let lock = NSLock()
    private var attempts: [Attempt] = []
    private let immediate: IncidentExportError?
    init(immediate: IncidentExportError? = nil) { self.immediate = immediate }
    var count: Int { lock.lock(); defer { lock.unlock() }; return attempts.count }
    func snapshot(at index: Int) -> IncidentSnapshot { lock.lock(); defer { lock.unlock() }; return attempts[index].snapshot }
    func cancelled(at index: Int) -> Bool { lock.lock(); defer { lock.unlock() }; return attempts[index].task.cancelled }
    func prepare(_ snapshot: IncidentSnapshot,
                 completion: @escaping @Sendable (Result<IncidentExportFile, IncidentExportError>) -> Void) -> (any IncidentExportTask)? {
        if let immediate { completion(.failure(immediate)); return nil }
        let task = ControlledTask()
        lock.lock(); attempts.append(Attempt(snapshot: snapshot, completion: completion, task: task)); lock.unlock(); return task
    }
    func finish(_ index: Int, _ result: Result<IncidentExportFile, IncidentExportError>) {
        lock.lock(); let completion = attempts[index].completion; lock.unlock(); completion(result)
    }
}
private final class ControlledTask: IncidentExportTask, @unchecked Sendable {
    private let lock = NSLock()
    private var value = false
    var cancelled: Bool { lock.lock(); defer { lock.unlock() }; return value }
    func cancel() { lock.lock(); value = true; lock.unlock() }
}
#endif
