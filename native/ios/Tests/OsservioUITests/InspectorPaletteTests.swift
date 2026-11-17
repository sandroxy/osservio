#if canImport(UIKit)
import UIKit
import XCTest
import OsservioCore
@testable import OsservioUI

final class InspectorPaletteTests: XCTestCase {
    @MainActor func testFilterLeadingActionsKeepAccentAfterEditingAndReturningFromPicker() throws {
        let preferences = InspectorPreferences.shared, previous = preferences.palette
        defer { preferences.palette = previous }
        let groups = [InspectorFilterGroup(.category, values: (0..<8).map { "category-\($0)" })]
        let filter = InspectorFilterViewController(query: InspectorQuery(), groups: groups) { _ in }
        let navigation = InspectorNavigationController(rootViewController: filter)
        navigation.transientPanel = true
        navigation.loadViewIfNeeded(); filter.loadViewIfNeeded()
        navigation.navigationController(navigation, willShow: filter, animated: false)
        for palette in [InspectorPalette.purple, .teal] {
            preferences.palette = palette
            let reset = try XCTUnwrap(filter.navigationItem.leftBarButtonItem)
            _ = filter.perform(try XCTUnwrap(reset.action))
            assertAccent(filter.navigationItem.leftBarButtonItem)
            filter.tableView(filter.tableView, didSelectRowAt: IndexPath(row: 0, section: 0))
            assertAccent(filter.navigationItem.leftBarButtonItem)
            // Collapse Time, then enter the large category picker and return.
            filter.tableView(filter.tableView, didSelectRowAt: IndexPath(row: 0, section: 0))
            filter.tableView(filter.tableView, didSelectRowAt: IndexPath(row: 1, section: 0))
            let back = try XCTUnwrap(filter.navigationItem.leftBarButtonItem)
            XCTAssertEqual(back.title, InspectorLocalization.text("ui_back")); assertAccent(back)
            _ = filter.perform(try XCTUnwrap(back.action))
            XCTAssertEqual(filter.navigationItem.leftBarButtonItem?.title, InspectorLocalization.text("ui_reset"))
            assertAccent(filter.navigationItem.leftBarButtonItem)
        }
    }
    @MainActor func testActionRerenderPreservesDoneButtonContrastInEveryAppearance() {
        let preferences = InspectorPreferences.shared, previous = preferences.palette
        defer { preferences.palette = previous }
        let page = UIViewController()
        let done = UIBarButtonItem(title: "Apply", style: .done, target: nil, action: nil)
        for palette in InspectorPalette.allCases {
            preferences.palette = palette; page.inspectorPrimaryAction = done
            for mode in [UIUserInterfaceStyle.light, .dark] {
                let traits = UITraitCollection(userInterfaceStyle: mode)
                let expected: UIColor
                if #available(iOS 26, *) { expected = InspectorTheme.filledAccent }
                else { expected = InspectorTheme.accent }
                XCTAssertEqual(done.tintColor?.resolvedColor(with: traits), expected.resolvedColor(with: traits))
            }
        }
    }
    @MainActor private func assertAccent(_ item: UIBarButtonItem?, file: StaticString = #filePath, line: UInt = #line) {
        for mode in [UIUserInterfaceStyle.light, .dark] {
            let traits = UITraitCollection(userInterfaceStyle: mode)
            XCTAssertEqual(item?.tintColor?.resolvedColor(with: traits), InspectorTheme.accent.resolvedColor(with: traits), file: file, line: line)
        }
    }
    @MainActor func testDebugButtonsRefreshForPaletteOnlyChange() throws {
        let preferences = InspectorPreferences.shared, previous = preferences.palette
        let capture = CaptureController(debugEnabled: true)
        defer { preferences.palette = previous; _ = capture.stopAll() }
        preferences.palette = .purple
        let control = InspectorDebugControl(controllers: [capture])
        let buttons = try (0..<4).map { _ in try XCTUnwrap(control.item().customView as? UIButton) }
        let traits = UITraitCollection(userInterfaceStyle: .light)
        let oldColor = try XCTUnwrap(buttons.first?.tintColor.resolvedColor(with: traits))
        preferences.palette = .teal; control.refresh()
        for button in buttons {
            XCTAssertEqual(button.tintColor.resolvedColor(with: traits), InspectorTheme.accent.resolvedColor(with: traits))
            XCTAssertNotEqual(button.tintColor.resolvedColor(with: traits), oldColor)
        }
        XCTAssertEqual(capture.status()?.debugEnabled, true)
    }
    @MainActor
    func testEveryPaletteKeepsTextContrastAndSemanticColorsIndependent() {
        let preferences = InspectorPreferences.shared, previous = preferences.palette
        defer { preferences.palette = previous }
        let semantic = InspectorTheme.jsonString
        for palette in InspectorPalette.allCases {
            preferences.palette = palette
            for mode in [UIUserInterfaceStyle.light, .dark] {
                let traits = UITraitCollection(userInterfaceStyle: mode)
                for background in [InspectorTheme.canvas, InspectorTheme.surface, InspectorTheme.tint] {
                    let values = [luminance(InspectorTheme.accent.resolvedColor(with: traits)), luminance(background.resolvedColor(with: traits))]
                    XCTAssertGreaterThanOrEqual((values.max()! + 0.05) / (values.min()! + 0.05), 4.5, palette.rawValue)
                }
                XCTAssertEqual(semantic.resolvedColor(with: traits), InspectorTheme.jsonString.resolvedColor(with: traits))
            }
        }
    }
    @MainActor
    func testPaletteGridReflowsWithoutShrinkingHitTargets() {
        let grid = InspectorPaletteGrid()
        for width: CGFloat in [160, 280, 500] {
            grid.frame = CGRect(x: 0, y: 0, width: width, height: 200); grid.setNeedsLayout(); grid.layoutIfNeeded()
            for view in grid.subviews {
                XCTAssertGreaterThanOrEqual(view.frame.width, 44); XCTAssertGreaterThanOrEqual(view.frame.height, 44)
                XCTAssertGreaterThanOrEqual(view.frame.minX, 0); XCTAssertLessThanOrEqual(view.frame.maxX, width)
                XCTAssertFalse((view.accessibilityLabel ?? "").isEmpty)
            }
            XCTAssertEqual(grid.subviews.count, InspectorPalette.allCases.count)
        }
    }
    private func luminance(_ color: UIColor) -> CGFloat {
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        color.getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        func linear(_ value: CGFloat) -> CGFloat { value <= 0.04045 ? value / 12.92 : pow((value + 0.055) / 1.055, 2.4) }
        return linear(red) * 0.2126 + linear(green) * 0.7152 + linear(blue) * 0.0722
    }
}
#endif
