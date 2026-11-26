#if canImport(UIKit)
import OsservioCore
import OsservioStore
@testable import OsservioUI
import UIKit
import XCTest

final class RuntimeInspectorActionsTests: XCTestCase {
    private final class Provider: EnvironmentProvider, @unchecked Sendable {
        private let lock = NSLock()
        private var completion: (@Sendable (Result<EnvironmentSnapshot, EnvironmentError>) -> Void)?
        func read(completion: @escaping @Sendable (Result<EnvironmentSnapshot, EnvironmentError>) -> Void) {
            lock.lock(); self.completion = completion; lock.unlock()
        }
        func finish() throws {
            lock.lock(); let callback = completion; completion = nil; lock.unlock()
            XCTAssertNotNil(callback)
            callback?(.success(try .capture(platform: .ios, publicFacts: Dictionary(uniqueKeysWithValues:
                EnvironmentKey.allCases.map { ($0, EnvironmentFact.unavailable) }))))
        }
    }
    private final class History: BreadcrumbHistory, @unchecked Sendable {
        let store: MemoryBreadcrumbStore
        var unavailable = false
        init(_ store: MemoryBreadcrumbStore) { self.store = store }
        func snapshot() -> BreadcrumbSnapshot? { unavailable ? nil : store.snapshot() }
        func clear() { store.clear() }
    }
    private final class Archive: BreadcrumbArchive, @unchecked Sendable {
        func persistenceStatus() -> PersistenceStatus? { nil }
        func readRuns(completion: @escaping @Sendable (Result<[RetainedRun], PersistenceError>) -> Void) { completion(.success([])) }
        func readRun(id: String, completion: @escaping @Sendable (Result<RetainedBreadcrumbs?, PersistenceError>) -> Void) { XCTFail("No row selected"); completion(.failure(.unavailable)) }
        func flush(completion: @escaping @Sendable (Result<Void, PersistenceError>) -> Void) { XCTFail("Viewing must not flush") }
        func deleteRun(id: String, completion: @escaping @Sendable (Result<Void, PersistenceError>) -> Void) { XCTFail("Viewing must not delete") }
        func deleteAllRuns(completion: @escaping @Sendable (Result<Void, PersistenceError>) -> Void) { XCTFail("Viewing must not delete") }
    }
    @MainActor private final class WeakController { weak var value: UIViewController? }
    @MainActor private func waitUntil(_ condition: () -> Bool) async throws {
        for _ in 0..<100 { if condition() { return }; try await Task.sleep(nanoseconds: 25_000_000) }
        XCTFail("Runtime actions did not update")
    }
    @MainActor private func descendants(_ view: UIView) -> [UIView] { [view] + view.subviews.flatMap(descendants) }
    @MainActor func testStandaloneForwardsCurrentChildActionsAndTheirReplacements() async throws {
        let store = try MemoryBreadcrumbStore(), capture = CaptureController(baselineEnabled: true), provider = Provider()
        let recorder = try BreadcrumbRecorder(sink: store, captureController: capture)
        var viewer: RuntimeInspectorViewController? = RuntimeInspectorViewController(provider: provider, recorder: recorder, history: store, archive: Archive())
        let released = WeakController(); released.value = viewer
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = UINavigationController(rootViewController: viewer!); window.makeKeyAndVisible()
        window.rootViewController?.loadViewIfNeeded(); window.rootViewController?.view.layoutIfNeeded()
        defer { window.isHidden = true; window.rootViewController = nil }
        let environment = try XCTUnwrap(viewer?.children.first)
        XCTAssertEqual(viewer?.inspectorActions?.compactMap(\.accessibilityLabel), ["runtime_copy_environment", "http_refresh", "ui_capture_settings"].map(InspectorLocalization.text))
        XCTAssertFalse(viewer?.inspectorActions?.first?.isEnabled ?? true)
        let oldCopy = try XCTUnwrap(viewer?.inspectorActions?.first)
        try provider.finish()
        try await waitUntil { viewer?.inspectorActions?.first?.isEnabled == true }
        XCTAssertFalse(viewer?.inspectorActions?.first === oldCopy)
        XCTAssertTrue(viewer?.inspectorActions?.first === environment.inspectorActions?.first)
        let selection = try XCTUnwrap(descendants(viewer!.view).compactMap { $0 as? UISegmentedControl }.first)
        for (index, action) in [(1, "crumb_clear"), (2, "archive_delete_all"), (0, "runtime_copy_environment")] {
            selection.selectedSegmentIndex = index
            for target in selection.allTargets {
                for selector in selection.actions(forTarget: target.base, forControlEvent: .valueChanged) ?? [] {
                    _ = (target.base as? NSObject)?.perform(NSSelectorFromString(selector), with: selection)
                }
            }
            XCTAssertEqual(viewer?.inspectorActions?.first?.accessibilityLabel, InspectorLocalization.text(action))
            XCTAssertTrue(viewer?.inspectorActions?.first === viewer?.children.first?.inspectorActions?.first)
            if index != 0 { XCTAssertFalse(viewer?.inspectorActions?.first?.isEnabled ?? true) }
            XCTAssertEqual(viewer?.inspectorActions?.last?.accessibilityLabel, InspectorLocalization.text("ui_capture_settings"))
        }
        window.isHidden = true; window.rootViewController = nil; viewer = nil
        try await waitUntil { released.value == nil }
    }
    @MainActor func testTimelineClearUsesRetainedHistoryNotFilteredRowsAndRejectsStaleReads() async throws {
        let store = try MemoryBreadcrumbStore(), capture = CaptureController(baselineEnabled: true)
        let history = History(store), recorder = try BreadcrumbRecorder(sink: store, captureController: capture)
        let viewer = BreadcrumbInspectorViewController(recorder: recorder, history: history)
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = UINavigationController(rootViewController: viewer); window.makeKeyAndVisible()
        window.rootViewController?.loadViewIfNeeded(); window.rootViewController?.view.layoutIfNeeded()
        defer { window.isHidden = true; window.rootViewController = nil }
        XCTAssertFalse(viewer.inspectorPrimaryAction?.isEnabled ?? true)
        XCTAssertEqual(recorder.record(.hostMarker, label: "retained"), .recorded)
        try await waitUntil { viewer.inspectorPrimaryAction?.isEnabled == true }
        let header = try XCTUnwrap(viewer.tableView.tableHeaderView as? InspectorFeedHeader)
        header.onSearch?("no matching observation")
        XCTAssertEqual(viewer.tableView(viewer.tableView, numberOfRowsInSection: 0), 0)
        XCTAssertTrue(viewer.inspectorPrimaryAction?.isEnabled == true)
        history.unavailable = true
        try await waitUntil { viewer.inspectorPrimaryAction?.isEnabled == false }
        history.unavailable = false
        try await waitUntil { viewer.inspectorPrimaryAction?.isEnabled == true }
        store.clear()
        try await waitUntil { viewer.inspectorPrimaryAction?.isEnabled == false }
    }
}
#endif
