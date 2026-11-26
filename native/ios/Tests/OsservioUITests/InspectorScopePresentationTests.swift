#if canImport(UIKit)
import UIKit
import XCTest
import OsservioCore
@testable import OsservioUI

final class InspectorScopePresentationTests: XCTestCase {
    @MainActor
    func testPresentationTraitsKeepTitleAttributesWhilePaletteAndAppearanceStillUpdate() throws {
        let previous = InspectorPreferences.shared.palette
        defer { InspectorPreferences.shared.palette = previous }
        InspectorPreferences.shared.palette = .purple
        for titles in [["Structure", "Source"], ["All", "HTTP", "WebSocket"], ["Overview", "Request", "Response"]] {
            let control = InspectorScopeControl(titles: titles)
            control.frame = CGRect(x: 0, y: 0, width: 390, height: 44); control.layoutIfNeeded()
            control.selectedIndex = 1
            var changes = 0; control.onSelect = { _ in changes += 1 }
            let segments = try XCTUnwrap(control.arrangedSubviews.first as? UISegmentedControl)
            let foreground = try XCTUnwrap(segments.titleTextAttributes(for: .selected)?[.foregroundColor] as? UIColor)
            for _ in 0..<3 {
                // UIKit delivers trait notifications as a sheet changes active appearance.
                control.traitCollectionDidChange(control.traitCollection)
                XCTAssertTrue((segments.titleTextAttributes(for: .selected)?[.foregroundColor] as? UIColor) === foreground,
                    "Unrelated presentation updates must not restart the native title animation")
            }
            InspectorPreferences.shared.palette = .teal
            let updated = try XCTUnwrap(segments.titleTextAttributes(for: .selected)?[.foregroundColor] as? UIColor)
            XCTAssertFalse(updated === foreground)
            for appearance in [UIUserInterfaceStyle.light, .dark] {
                let traits = UITraitCollection(userInterfaceStyle: appearance)
                XCTAssertEqual(updated.resolvedColor(with: traits), InspectorTheme.accent.resolvedColor(with: traits))
            }
            XCTAssertEqual(segments.selectedSegmentIndex, 1); XCTAssertEqual(changes, 0)
            control.frame.size.width = 100; control.setNeedsLayout(); control.layoutIfNeeded()
            XCTAssertTrue(segments.isHidden)
            control.frame.size.width = 390; control.setNeedsLayout(); control.layoutIfNeeded()
            XCTAssertFalse(segments.isHidden); XCTAssertEqual(segments.selectedSegmentIndex, 1)
            InspectorPreferences.shared.palette = .purple
        }
    }

    private final class Source: KeyValueSource {
        func visitKeys(_ visitor: (String) -> Bool) throws -> Bool { visitor("configuration") }
        func read(key: String) throws -> KeyValueInspection.Value {
            .text(.string, "{\"environment\":\"development\",\"retryCount\":3}")
        }
    }
    @MainActor
    func testOpeningActionsKeepsContentModeLabelsInTheirSegments() async throws {
        let storage = try KeyValueInspection(owner: UUID(), registrations: [.init(
            domain: .init(id: "app", name: "App", backend: "Host", scope: "fixture"), source: Source())])
        let page = StorageValueViewController(storage: storage, domain: storage.domains[0], key: "configuration")
        let navigation = InspectorNavigationController(rootViewController: page)
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = navigation; window.makeKeyAndVisible()
        defer { storage.close(); window.isHidden = true; window.rootViewController = nil }
        page.loadViewIfNeeded(); navigation.view.layoutIfNeeded()
        var ready: InspectorScopeControl?
        for _ in 0..<150 {
            navigation.view.layoutIfNeeded()
            if let mode = descendants(page.view).compactMap({ $0 as? InspectorScopeControl }).first,
               !mode.isHidden, mode.bounds.width > 0 { ready = mode; break }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        let mode = try XCTUnwrap(ready, "The asynchronous value reader must finish parsing before presentation")
        let segments = try XCTUnwrap(mode.arrangedSubviews.first as? UISegmentedControl)
        XCTAssertFalse(mode.isHidden)
        let titles = (0..<segments.numberOfSegments).compactMap { segments.titleForSegment(at: $0) }
        let labels = descendants(segments).compactMap { $0 as? UILabel }.filter { titles.contains($0.text ?? "") }
        XCTAssertGreaterThanOrEqual(labels.count, titles.count)
        let initial = Dictionary(labels.map { ($0.text!, $0.convert($0.bounds, to: segments)) }, uniquingKeysWith: { first, _ in first })
        let panel = InspectorActionPanel(title: "Actions", message: nil)
        panel.addAction(.init(title: "Copy", style: .default) { _ in })
        page.inspectorPresent(panel)
        for _ in 0..<30 {
            try await Task.sleep(nanoseconds: 16_000_000)
            let current = descendants(segments).compactMap { $0 as? UILabel }.filter { titles.contains($0.text ?? "") }
            for label in current {
                let expected = try XCTUnwrap(initial[label.text ?? ""])
                let actual = label.layer.presentation()?.convert(label.bounds, to: segments.layer.presentation())
                    ?? label.convert(label.bounds, to: segments)
                XCTAssertEqual(actual.midX, expected.midX, accuracy: 2, "Unchanged mode text must not move during presentation")
                XCTAssertEqual(actual.midY, expected.midY, accuracy: 2, "Unchanged mode text must not move during presentation")
            }
        }
        page.dismiss(animated: false)
    }

    @MainActor private func descendants(_ view: UIView) -> [UIView] {
        [view] + view.subviews.flatMap(descendants)
    }
}
#endif
