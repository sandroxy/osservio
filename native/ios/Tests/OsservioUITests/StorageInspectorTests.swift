#if canImport(UIKit)
import OsservioCore
@testable import OsservioUI
import UIKit
import XCTest

final class StorageInspectorTests: XCTestCase {
    @MainActor private final class ReaderReference { weak var value: UIViewController? }
    private final class Source: KeyValueSource, @unchecked Sendable {
        let lock = NSLock()
        private var count = 0
        private var value = "stored-account-A"
        func update(_ text: String) { lock.lock(); value = text; lock.unlock() }
        var reads: Int { lock.lock(); defer { lock.unlock() }; return count }
        func visitKeys(_ visitor: (String) -> Bool) throws -> Bool {
            for key in ["profile", "notifications", "empty"] { if !visitor(key) { return false } }; return true
        }
        func read(key: String) throws -> KeyValueInspection.Value {
            lock.lock(); count += 1; let text = value; lock.unlock()
            if key == "error" { throw KeyValueInspection.Failure.unavailable }
            if key == "missing" { return .unavailable(.missing) }
            return .text(.string, key == "empty" ? "" : text)
        }
    }
    private func storage(_ source: Source) throws -> KeyValueInspection {
        try KeyValueInspection(owner: UUID(), registrations: [.init(domain: .init(id: "settings", name: "Settings", backend: "Host", scope: "account-A"), source: source)])
    }
    @MainActor private func show(_ controller: UIViewController) -> UIWindow {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844)); window.rootViewController = controller; window.makeKeyAndVisible()
        controller.loadViewIfNeeded(); controller.view.layoutIfNeeded(); return window
    }
    @MainActor private func waitUntil(_ condition: () -> Bool) async throws {
        for _ in 0..<150 { if condition() { return }; try await Task.sleep(nanoseconds: 20_000_000) }; XCTFail("Storage page did not settle")
    }
    @MainActor private func descendants(_ view: UIView) -> [UIView] { [view] + view.subviews.flatMap(descendants) }
    @MainActor private func text(_ controller: UIViewController) -> String {
        descendants(controller.view).compactMap { ($0 as? UILabel)?.text ?? ($0 as? UITextView)?.text }.joined(separator: "\n")
    }
    @MainActor func testListSearchDoesNotReadValuesAndKeepsSearchInput() async throws {
        let source = Source(), storage = try storage(source); defer { storage.close() }
        let viewer = StorageInspectorViewController(storage: storage, domain: storage.domains[0])
        let window = show(UINavigationController(rootViewController: viewer)); defer { window.isHidden = true; window.rootViewController = nil }
        try await waitUntil { viewer.tableView.numberOfRows(inSection: 0) == 3 }
        let search = try XCTUnwrap(descendants(viewer.view).compactMap { $0 as? UISearchTextField }.first)
        search.text = "profile"
        // The unhosted UIKit runner does not route UIApplication control events.
        for target in search.allTargets {
            guard let receiver = target.base as? NSObject else { continue }
            for action in search.actions(forTarget: receiver, forControlEvent: .editingChanged) ?? [] {
                _ = receiver.perform(NSSelectorFromString(action), with: search)
            }
        }
        try await waitUntil { viewer.tableView.numberOfRows(inSection: 0) == 1 }
        XCTAssertEqual(source.reads, 0); XCTAssertEqual(search.text, "profile")
        let cell = viewer.tableView(viewer.tableView, cellForRowAt: IndexPath(row: 0, section: 0)); XCTAssertEqual(cell.textLabel?.text, "profile")
        storage.close(); try await waitUntil { viewer.tableView.numberOfRows(inSection: 0) == 0 }
        XCTAssertTrue(text(viewer).contains(InspectorLocalization.text("storage_closed")))
    }
    @MainActor private func action(_ viewer: UIViewController, _ label: String) throws -> UIBarButtonItem {
        let item = try XCTUnwrap(viewer.inspectorActions?.first { $0.accessibilityLabel == InspectorLocalization.text(label) })
        if label == "ui_copy_all" {
            XCTAssertTrue(item.target === viewer.children.first)
            XCTAssertEqual(item.action, NSSelectorFromString("copyContent"))
        } else if label == "http_refresh" {
            XCTAssertNotNil(item.image, "Refresh must keep its icon when represented as a menu row")
            XCTAssertTrue(item.target === viewer)
            XCTAssertEqual(item.action, NSSelectorFromString("readValue"))
        }
        return item
    }
    @MainActor private func invoke(_ item: UIBarButtonItem) throws {
        _ = try XCTUnwrap(item.target as? NSObject).perform(try XCTUnwrap(item.action), with: item)
    }
    @MainActor func testOwnerCloseClearsVisibleValueAndCopyImmediately() async throws {
        let source = Source(), storage = try storage(source)
        let viewer = StorageValueViewController(storage: storage, domain: storage.domains[0], key: "profile")
        let window = show(UINavigationController(rootViewController: viewer)); defer { storage.close(); window.isHidden = true; window.rootViewController = nil }
        try await waitUntil { self.text(viewer).contains("stored-account-A") }
        let copy = try action(viewer, "ui_copy_all"), refresh = try action(viewer, "http_refresh")
        XCTAssertTrue(copy.isEnabled); XCTAssertTrue(refresh.isEnabled); XCTAssertFalse(copy === refresh)
        storage.close(); try await waitUntil { self.text(viewer).contains(InspectorLocalization.text("storage_closed")) }
        XCTAssertFalse(text(viewer).contains("stored-account-A"))
        XCTAssertFalse(try action(viewer, "ui_copy_all").isEnabled)
        XCTAssertFalse(try action(viewer, "http_refresh").isEnabled)
        XCTAssertFalse(copy.isEnabled, "An already-open menu must not keep the previous value copyable")
    }
    @MainActor func testEmptyValueIsCopyableAndHiddenPageReleasesReader() async throws {
        let source = Source(), storage = try storage(source); defer { storage.close() }
        let viewer = StorageValueViewController(storage: storage, domain: storage.domains[0], key: "empty")
        let navigation = UINavigationController(rootViewController: viewer), window = show(UINavigationController())
        window.rootViewController = navigation; defer { window.isHidden = true; window.rootViewController = nil }
        try await waitUntil { self.text(viewer).contains(InspectorLocalization.text("storage_empty_value")) }
        let copy = try action(viewer, "ui_copy_all")
        XCTAssertTrue(copy.isEnabled)
        let reader = ReaderReference(); reader.value = viewer.children.first
        viewer.beginAppearanceTransition(false, animated: false); viewer.endAppearanceTransition()
        try await waitUntil { reader.value == nil }; XCTAssertTrue(viewer.children.isEmpty)
        XCTAssertFalse(copy.isEnabled)
    }
    @MainActor func testCopyAndRefreshKeepCorrectTargetsAcrossReadAndReturn() async throws {
        let source = Source(), storage = try storage(source); defer { storage.close() }
        let viewer = StorageValueViewController(storage: storage, domain: storage.domains[0], key: "profile")
        let window = show(UINavigationController(rootViewController: viewer)); defer { window.isHidden = true; window.rootViewController = nil }
        XCTAssertEqual(viewer.inspectorActions?.compactMap(\.accessibilityLabel), ["ui_copy_all", "http_refresh"].map(InspectorLocalization.text))
        XCTAssertFalse(try action(viewer, "ui_copy_all").isEnabled)
        try await waitUntil { self.text(viewer).contains("stored-account-A") }
        let copy = try action(viewer, "ui_copy_all")
        XCTAssertTrue(copy.isEnabled)
        source.update("updated-by-host"); try invoke(action(viewer, "http_refresh"))
        XCTAssertFalse(copy.isEnabled); XCTAssertFalse(try action(viewer, "ui_copy_all").isEnabled)
        XCTAssertFalse(try action(viewer, "http_refresh").isEnabled)
        try await waitUntil { self.text(viewer).contains("updated-by-host") }
        XCTAssertTrue(try action(viewer, "ui_copy_all").isEnabled)
        XCTAssertFalse(try action(viewer, "ui_copy_all") === copy)
        XCTAssertEqual(source.reads, 2)
        viewer.beginAppearanceTransition(false, animated: false); viewer.endAppearanceTransition()
        viewer.beginAppearanceTransition(true, animated: false); viewer.endAppearanceTransition()
        try await waitUntil { self.text(viewer).contains("updated-by-host") }
        XCTAssertTrue(try action(viewer, "ui_copy_all").isEnabled)
        XCTAssertTrue(try action(viewer, "http_refresh").isEnabled)
    }
    @MainActor func testUnavailableValuesDisableCopyButAllowRefresh() async throws {
        for (key, notice) in [("missing", "storage_missing"), ("error", "runtime_read_failed")] {
            let source = Source(), storage = try storage(source); defer { storage.close() }
            let viewer = StorageValueViewController(storage: storage, domain: storage.domains[0], key: key)
            let window = show(UINavigationController(rootViewController: viewer)); defer { window.isHidden = true; window.rootViewController = nil }
            try await waitUntil { self.text(viewer).contains(InspectorLocalization.text(notice)) }
            let copy = try action(viewer, "ui_copy_all")
            XCTAssertFalse(copy.isEnabled); XCTAssertTrue(try action(viewer, "http_refresh").isEnabled)
            // Clipboard delivery is exercised in a running consumer, not this unhosted UIKit runner.
            try invoke(copy) // Unavailable content must return before accessing the system clipboard.
        }
    }
}
#endif
