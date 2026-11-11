#if canImport(UIKit)
import UIKit
import XCTest
@testable import OsservioUI

final class InspectorPaletteTests: XCTestCase {
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
