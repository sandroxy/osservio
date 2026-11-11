#if canImport(UIKit)
import UIKit
import OsservioCore
@testable import OsservioUI
import XCTest

final class InspectorJSONReaderTests: XCTestCase {
    private let source = #"{"profile":{"name":"你好🙂"},"items":[1,2],"id":900719925474099312345}"#
    @MainActor func testExpansionSearchSourceAndRetainedReadingState() async throws {
        let reader = InspectorContentViewController(title: "JSON", content: source, code: true)
        let window = show(reader); defer { window.isHidden = true; window.rootViewController = nil }
        let tree = try XCTUnwrap(find(reader.view, InspectorJSONTreeView.self))
        try await ready(tree)
        XCTAssertEqual(tree.rows.count, 5)
        tree.tableView(tree, didSelectRowAt: IndexPath(row: 1, section: 0))
        XCTAssertEqual(tree.rows.count, 7); XCTAssertEqual(tree.expanded, [1])
        let cell = tree.tableView(tree, cellForRowAt: IndexPath(row: 1, section: 0))
        XCTAssertEqual(cell.accessoryType, .none); XCTAssertTrue(cell.accessibilityTraits.contains(.button))
        let search = try XCTUnwrap(find(reader.view, UISearchTextField.self))
        search.text = "900719925474099312345"; activate(search, .editingChanged)
        try await Task.sleep(nanoseconds: 180_000_000)
        XCTAssertTrue(tree.expanded.contains(1), "Search must not discard manual expansion")
        let scope = try XCTUnwrap(find(reader.view, InspectorScopeControl.self))
        scope.selectedIndex = 1; scope.onSelect?(1)
        let text = try XCTUnwrap(find(reader.view, UITextView.self)); XCTAssertEqual(text.text, source)
        XCTAssertTrue(tree.isHidden); XCTAssertFalse(text.isHidden)
        scope.selectedIndex = 0; scope.onSelect?(0)
        reader.beginAppearanceTransition(false, animated: false); reader.endAppearanceTransition()
        reader.beginAppearanceTransition(true, animated: false); reader.endAppearanceTransition()
        XCTAssertEqual(search.text, "900719925474099312345"); XCTAssertTrue(tree.expanded.contains(1))
        XCTAssertEqual(try XCTUnwrap(tree.document).original(6), "900719925474099312345")
    }
    @MainActor func testLargeRootArrayReusesRowsAndMalformedContentStaysReadable() async throws {
        let value = "[" + (0..<3_000).map(String.init).joined(separator: ",") + "]"
        let reader = InspectorContentViewController(title: "Large", content: value, code: true)
        let window = show(reader); defer { window.isHidden = true; window.rootViewController = nil }
        let tree = try XCTUnwrap(find(reader.view, InspectorJSONTreeView.self)); try await ready(tree)
        reader.view.layoutIfNeeded(); tree.layoutIfNeeded()
        XCTAssertEqual(tree.rows.count, 3_002)
        XCTAssertGreaterThan(tree.visibleCells.count, 0); XCTAssertLessThan(tree.visibleCells.count, 40)
        tree.scrollToRow(at: IndexPath(row: 2_500, section: 0), at: .top, animated: false); tree.layoutIfNeeded()
        XCTAssertGreaterThan(tree.visibleCells.count, 0); XCTAssertLessThan(tree.visibleCells.count, 40)
        for value in ["{\"broken\":", String(repeating: "[", count: 50) + "0" + String(repeating: "]", count: 50)] {
            let raw = InspectorContentViewController(title: "Source", content: value, code: true)
            let rawWindow = show(raw); defer { rawWindow.isHidden = true; rawWindow.rootViewController = nil }
            try await Task.sleep(nanoseconds: 150_000_000)
            XCTAssertNil(find(raw.view, InspectorJSONTreeView.self)?.document)
            XCTAssertEqual(find(raw.view, UITextView.self)?.text, value)
            XCTAssertFalse(try XCTUnwrap(find(raw.view, UITextView.self)).isHidden)
        }
    }
    @MainActor func testIncompleteSourceNeverBecomesAStructuralSuccessAndCancelledReadCanResume() async throws {
        let incomplete = InspectorContentViewController(title: "Partial", value: InspectorReadContent(text: "{}", notice: { "Truncated" }, allowsJSON: false))
        let window = show(incomplete); defer { window.isHidden = true; window.rootViewController = nil }
        try await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertNil(find(incomplete.view, InspectorJSONTreeView.self)?.document)
        XCTAssertEqual(find(incomplete.view, UITextView.self)?.text, "{}")
        let reader = InspectorContentViewController(title: "Cancellation", content: source, code: true)
        reader.loadViewIfNeeded(); reader.beginAppearanceTransition(true, animated: false); reader.endAppearanceTransition()
        reader.beginAppearanceTransition(false, animated: false); reader.endAppearanceTransition()
        try await Task.sleep(nanoseconds: 100_000_000)
        let tree = try XCTUnwrap(find(reader.view, InspectorJSONTreeView.self)); XCTAssertNil(tree.document)
        reader.beginAppearanceTransition(true, animated: false); reader.endAppearanceTransition(); try await ready(tree)
        XCTAssertEqual(tree.rows.count, 5)
    }
    @MainActor func testRetainedVisibleRowsUpdateTheirLanguageOnReturn() async throws {
        let preferences = InspectorPreferences.shared, language = preferences.language
        defer { preferences.language = language }
        preferences.language = .chinese
        let id = "12345678-1234-1234-1234-123456789abc.response"
        let reference = try HTTPBodyReference.restore(state: .available, blobID: id, observedBytes: Int64(source.utf8.count))
        let payload = try IncidentPayload.restore(id: id, state: .available, text: source)
        let content = HTTPPresentation.body(reference, savedPayloads: [id: payload])
            .withNoticePrefix { InspectorLocalization.text("incident_frozen_detail") }
        let reader = InspectorContentViewController(title: "JSON", value: content)
        let window = show(reader); defer { window.isHidden = true; window.rootViewController = nil }
        let tree = try XCTUnwrap(find(reader.view, InspectorJSONTreeView.self)); try await ready(tree)
        tree.tableView(tree, didSelectRowAt: IndexPath(row: 1, section: 0)); tree.layoutIfNeeded()
        let path = IndexPath(row: 1, section: 0)
        XCTAssertEqual(try XCTUnwrap(tree.cellForRow(at: path)).accessibilityValue, "已展开")
        XCTAssertTrue(hasLabel(reader.view, containing: HTTPPresentation.bodyDescription(reference)))
        let offset = tree.contentOffset
        reader.beginAppearanceTransition(false, animated: false); reader.endAppearanceTransition()
        preferences.language = .english
        reader.beginAppearanceTransition(true, animated: false); reader.endAppearanceTransition()
        XCTAssertEqual(try XCTUnwrap(tree.cellForRow(at: path)).accessibilityValue, "Expanded")
        XCTAssertEqual(tree.expanded, [1]); XCTAssertEqual(tree.contentOffset, offset)
        XCTAssertTrue(hasLabel(reader.view, containing: HTTPPresentation.bodyDescription(reference)))
        XCTAssertTrue(hasLabel(reader.view, containing: InspectorLocalization.text("incident_frozen_detail")))
        XCTAssertEqual(tree.document?.original(0), source)
    }
    @MainActor private func hasLabel(_ view: UIView, containing text: String) -> Bool {
        if let label = view as? UILabel, label.text?.contains(text) == true { return true }
        return view.subviews.contains { hasLabel($0, containing: text) }
    }
    @MainActor private func ready(_ tree: InspectorJSONTreeView) async throws {
        for _ in 0..<100 {
            if tree.document != nil { return }; try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTFail("JSON parsing did not complete")
    }
    @MainActor private func show(_ reader: UIViewController) -> UIWindow {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = UINavigationController(rootViewController: reader); window.makeKeyAndVisible(); window.layoutIfNeeded()
        return window
    }
    @MainActor private func find<T: UIView>(_ view: UIView, _ type: T.Type) -> T? {
        if let result = view as? T { return result }
        return view.subviews.lazy.compactMap { self.find($0, type) }.first
    }
    @MainActor private func activate(_ control: UIControl, _ event: UIControl.Event) {
        for target in control.allTargets {
            guard let receiver = target.base as? NSObject else { continue }
            for action in control.actions(forTarget: receiver, forControlEvent: event) ?? [] { _ = receiver.perform(NSSelectorFromString(action), with: control) }
        }
    }
}
#endif
