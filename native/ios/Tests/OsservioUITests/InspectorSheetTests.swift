#if canImport(UIKit)
import UIKit
import XCTest
@testable import OsservioUI

final class InspectorSheetTests: XCTestCase {
    @MainActor func testNativeSheetDetentsKeepClearanceWhenActionPanelsExpand() throws {
        guard #available(iOS 16, *) else { throw XCTSkip("Custom native detents require iOS 16") }
        let filter = InspectorFilterViewController(query: InspectorQuery(), groups: []) { _ in }
        let actions = InspectorActionPanel(title: "Actions", message: nil)
        for page in [filter, actions] {
            let navigation = InspectorNavigationController(rootViewController: page)
            navigation.transientPanel = true; navigation.modalPresentationStyle = .pageSheet
            navigation.configureSheet(); page.loadViewIfNeeded(); page.viewWillAppear(false)
            let sheet = try XCTUnwrap(navigation.sheetPresentationController)
            XCTAssertEqual(sheet.detents.count, page === actions ? 2 : 1)
            let expanded = try XCTUnwrap(sheet.detents.last)
            let tall = try XCTUnwrap(expanded.resolvedValue(in: SheetContext(height: 800)))
            let short = try XCTUnwrap(expanded.resolvedValue(in: SheetContext(height: 240)))
            XCTAssertGreaterThanOrEqual(800 - tall, 48, "Expanded action panels must use the same reserved space as editors")
            XCTAssertGreaterThan(tall, 700, "The editor must remain a tall working surface")
            XCTAssertLessThan(short, 240); XCTAssertGreaterThan(short, 200, "Short viewports must retain usable content height")
            XCTAssertTrue(sheet.prefersGrabberVisible); XCTAssertTrue(sheet.prefersEdgeAttachedInCompactHeight)
        }
    }

    @MainActor func testSheetEdgeRendersBelowNativeControls() throws {
        let preferences = InspectorPreferences.shared, oldAppearance = preferences.appearance, oldPalette = preferences.palette
        defer { preferences.appearance = oldAppearance; preferences.palette = oldPalette }
        preferences.appearance = .dark; preferences.palette = .teal
        let filter = InspectorFilterViewController(query: InspectorQuery(), groups: []) { _ in }
        let navigation = InspectorNavigationController(rootViewController: filter)
        navigation.transientPanel = true
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = navigation; window.makeKeyAndVisible(); window.layoutIfNeeded()
        defer { window.isHidden = true; window.rootViewController = nil }
        navigation.view.setNeedsLayout(); navigation.view.layoutIfNeeded()
        let parent = try XCTUnwrap(navigation.navigationBar.superview)
        let edge = try XCTUnwrap(parent.subviews.compactMap { $0 as? InspectorSheetEdge }.first)
        let edgeIndex = try XCTUnwrap(parent.subviews.firstIndex(of: edge))
        XCTAssertEqual(edgeIndex + 1, parent.subviews.firstIndex(of: navigation.navigationBar),
                       "Opaque page content must stay below the edge and navigation controls")
        edge.setNeedsDisplay(); edge.layer.displayIfNeeded()
        let format = UIGraphicsImageRendererFormat(); format.scale = 1
        let image = UIGraphicsImageRenderer(bounds: edge.bounds, format: format)
            .image { edge.layer.render(in: $0.cgContext) }
        let attachment = XCTAttachment(image: image); attachment.name = "Sheet upper edge"; add(attachment)
        let bitmap = try XCTUnwrap(image.cgImage)
        var pixel = [UInt8](repeating: 0, count: 4)
        let crop = try XCTUnwrap(bitmap.cropping(to: CGRect(x: bitmap.width / 2, y: 4, width: 1, height: 1)))
        try pixel.withUnsafeMutableBytes { storage in
            let context = try XCTUnwrap(CGContext(data: storage.baseAddress, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
            context.draw(crop, in: CGRect(x: 0, y: 0, width: 1, height: 1))
        }
        XCTAssertGreaterThan(Int(pixel[1]) - Int(pixel[0]), 18, "The dark sheet must render its teal edge light")
        let lower = try XCTUnwrap(bitmap.cropping(to: CGRect(x: bitmap.width / 2, y: bitmap.height - 1, width: 1, height: 1)))
        try pixel.withUnsafeMutableBytes { storage in
            let context = try XCTUnwrap(CGContext(data: storage.baseAddress, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
            context.draw(lower, in: CGRect(x: 0, y: 0, width: 1, height: 1))
        }
        XCTAssertEqual(Array(pixel.prefix(3)), [0x13, 0x17, 0x20], "Edge light must fade into the content canvas, without a header band")
    }

    @MainActor func testSheetDecorationFollowsAppearanceWithoutReplacingPagesOrInterceptingInput() throws {
        let preferences = InspectorPreferences.shared, oldAppearance = preferences.appearance, oldPalette = preferences.palette
        defer { preferences.appearance = oldAppearance; preferences.palette = oldPalette }
        preferences.appearance = .dark
        let page = UIViewController(), navigation = InspectorNavigationController(rootViewController: page)
        navigation.transientPanel = true; navigation.loadViewIfNeeded()
        let edge = try XCTUnwrap(navigation.view.subviews.compactMap { $0 as? InspectorSheetEdge }.first)
        XCTAssertFalse(edge.isUserInteractionEnabled); XCTAssertTrue(edge.accessibilityElementsHidden)
        XCTAssertNil(edge.hitTest(CGPoint(x: 20, y: 20), with: nil))
        for width: CGFloat in [180, 390, 844] {
            navigation.view.frame = CGRect(x: 0, y: 0, width: width, height: 600)
            navigation.view.setNeedsLayout(); navigation.view.layoutIfNeeded()
            XCTAssertEqual(edge.frame.minY, 0); XCTAssertEqual(edge.frame.width, width)
            let barBottom = navigation.navigationBar.convert(navigation.navigationBar.bounds, to: navigation.view).maxY
            XCTAssertEqual(edge.frame.height, max(44, barBottom))
        }
        for palette in [InspectorPalette.purple, .teal] {
            preferences.palette = palette
            preferences.appearance = .light; XCTAssertTrue(edge.isHidden)
            preferences.appearance = .dark; XCTAssertFalse(edge.isHidden)
            XCTAssertTrue(navigation.topViewController === page)
            XCTAssertEqual(navigation.view.subviews.filter { $0 is InspectorSheetEdge }.count, 1)
            XCTAssertNil(edge.layer.animationKeys())
        }
        let ordinary = InspectorNavigationController(rootViewController: UIViewController())
        ordinary.loadViewIfNeeded()
        XCTAssertFalse(ordinary.view.subviews.contains { $0 is InspectorSheetEdge })
    }

    @MainActor func testSheetDecorationIsReleasedWithItsOwner() {
        weak var releasedEdge: InspectorSheetEdge?
        weak var releasedNavigation: InspectorNavigationController?
        autoreleasepool {
            let navigation = InspectorNavigationController(rootViewController: UIViewController())
            navigation.transientPanel = true; navigation.loadViewIfNeeded()
            releasedNavigation = navigation
            releasedEdge = navigation.view.subviews.compactMap { $0 as? InspectorSheetEdge }.first
            XCTAssertNotNil(releasedEdge)
        }
        XCTAssertNil(releasedEdge); XCTAssertNil(releasedNavigation)
    }
    @available(iOS 16, *)
    @MainActor private final class SheetContext: NSObject, UISheetPresentationControllerDetentResolutionContext {
        let maximumDetentValue: CGFloat
        let containerTraitCollection = UITraitCollection(userInterfaceIdiom: .phone)
        init(height: CGFloat) { maximumDetentValue = height }
    }
}
#endif
