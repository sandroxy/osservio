#if canImport(UIKit)
import UIKit
@testable import OsservioUI
import XCTest

final class InspectorFormReaderTests: XCTestCase {
    @MainActor func testDecodedSearchRawSourceAndFullFieldReadRemainDistinct() async throws {
        let source = "tag=one&tag=%E4%BD%A0%E5%A5%BD&empty=&flag"
        var content = InspectorReadContent(text: source, allowsJSON: true); content.isForm = true
        let reader = InspectorContentViewController(title: "Form", value: content)
        let window = show(reader); defer { window.isHidden = true; window.rootViewController = nil }
        let form = try XCTUnwrap(find(reader.view, InspectorFormView.self))
        for _ in 0..<100 { if form.document != nil { break }; try await Task.sleep(nanoseconds: 10_000_000) }
        XCTAssertEqual(form.document?.fields.count, 4); XCTAssertFalse(form.isHidden)
        let search = try XCTUnwrap(find(reader.view, UISearchTextField.self)); search.text = "你好"; activate(search)
        try await Task.sleep(nanoseconds: 180_000_000)
        let cell = form.tableView(form, cellForRowAt: IndexPath(row: 1, section: 0))
        XCTAssertEqual(cell.detailTextLabel?.text, "你好"); XCTAssertEqual(cell.backgroundColor?.resolvedColor(with: reader.traitCollection), InspectorTheme.tint.resolvedColor(with: reader.traitCollection))
        let mode = try XCTUnwrap(find(reader.view, InspectorScopeControl.self))
        mode.selectedIndex = 1; mode.onSelect?(1)
        let raw = try XCTUnwrap(find(reader.view, UITextView.self)); XCTAssertFalse(raw.isHidden); XCTAssertEqual(raw.text, source)
        mode.selectedIndex = 0; mode.onSelect?(0)
        form.tableView(form, didSelectRowAt: IndexPath(row: 1, section: 0))
        let detail = try XCTUnwrap(reader.navigationController?.topViewController as? InspectorContentViewController)
        XCTAssertFalse(detail === reader); detail.loadViewIfNeeded()
        XCTAssertEqual(find(detail.view, UITextView.self)?.text, "你好")
    }
    @MainActor func testIncompleteOrInvalidFormsRemainRawAndRowsAreReused() async throws {
        for (source, complete) in [("a=%FF", true), ("a=1", false)] {
            var content = InspectorReadContent(text: source, allowsJSON: complete); content.isForm = true
            let reader = InspectorContentViewController(title: "Form", value: content)
            let window = show(reader); defer { window.isHidden = true; window.rootViewController = nil }
            try await Task.sleep(nanoseconds: 100_000_000)
            XCTAssertNil(find(reader.view, InspectorFormView.self)?.document)
            XCTAssertEqual(find(reader.view, UITextView.self)?.text, source)
        }
        let form = InspectorFormView(); form.display(try InspectorFormDocument.parse(String(repeating: "long=some+text&", count: 256)))
        form.frame = CGRect(x: 0, y: 0, width: 320, height: 500); form.layoutIfNeeded()
        XCTAssertGreaterThan(form.visibleCells.count, 0); XCTAssertLessThan(form.visibleCells.count, 40)
        form.scrollToRow(at: IndexPath(row: 230, section: 0), at: .top, animated: false); form.layoutIfNeeded()
        XCTAssertLessThan(form.visibleCells.count, 40)
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
    @MainActor private func activate(_ control: UIControl) {
        for target in control.allTargets {
            guard let receiver = target.base as? NSObject else { continue }
            for action in control.actions(forTarget: receiver, forControlEvent: .editingChanged) ?? [] { _ = receiver.perform(NSSelectorFromString(action), with: control) }
        }
    }
}
#endif
