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
        var reads: Int { lock.lock(); defer { lock.unlock() }; return count }
        func visitKeys(_ visitor: (String) -> Bool) throws -> Bool {
            for key in ["profile", "notifications", "empty"] { if !visitor(key) { return false } }; return true
        }
        func read(key: String) throws -> KeyValueInspection.Value {
            lock.lock(); count += 1; lock.unlock(); return .text(.string, key == "empty" ? "" : "stored-account-A")
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
    @MainActor func testOwnerCloseClearsVisibleValueAndCopyImmediately() async throws {
        let source = Source(), storage = try storage(source)
        let viewer = StorageValueViewController(storage: storage, domain: storage.domains[0], key: "profile")
        let window = show(UINavigationController(rootViewController: viewer)); defer { storage.close(); window.isHidden = true; window.rootViewController = nil }
        try await waitUntil { self.text(viewer).contains("stored-account-A") }
        XCTAssertTrue(viewer.inspectorPrimaryAction?.isEnabled == true)
        storage.close(); try await waitUntil { self.text(viewer).contains(InspectorLocalization.text("storage_closed")) }
        XCTAssertFalse(text(viewer).contains("stored-account-A")); XCTAssertFalse(viewer.inspectorPrimaryAction?.isEnabled == true)
    }
    @MainActor func testEmptyValueIsCopyableAndHiddenPageReleasesReader() async throws {
        let source = Source(), storage = try storage(source); defer { storage.close() }
        let viewer = StorageValueViewController(storage: storage, domain: storage.domains[0], key: "empty")
        let navigation = UINavigationController(rootViewController: viewer), window = show(UINavigationController())
        window.rootViewController = navigation; defer { window.isHidden = true; window.rootViewController = nil }
        try await waitUntil { self.text(viewer).contains(InspectorLocalization.text("storage_empty_value")) }
        XCTAssertTrue(viewer.inspectorPrimaryAction?.isEnabled == true)
        let reader = ReaderReference(); reader.value = viewer.children.first
        viewer.viewWillDisappear(false); viewer.viewDidDisappear(false)
        try await waitUntil { reader.value == nil }; XCTAssertTrue(viewer.children.isEmpty)
    }
}
#endif
