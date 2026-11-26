#if canImport(UIKit)
import UIKit
import XCTest
@testable import OsservioUI

final class InspectorNavigationActionsTests: XCTestCase {
    @MainActor
    func testOverflowKeepsExitVisibleAndPreservesOriginalActionIdentity() {
        let page = UIViewController()
        let actions = (0..<3).map { UIBarButtonItem(title: String($0), style: .plain, target: nil, action: nil) }
        let state = InspectorNavigationActions.state(for: page)
        page.inspectorActions = Array(actions.prefix(2))
        XCTAssertEqual(page.navigationItem.rightBarButtonItems, Array(actions.prefix(2).reversed()))
        page.inspectorActions = actions
        XCTAssertEqual(page.navigationItem.rightBarButtonItems?.count, 1)
        XCTAssertEqual(page.navigationItem.rightBarButtonItems?.first?.accessibilityIdentifier, "Osservio.MoreActions")
        let close = UIBarButtonItem(barButtonSystemItem: .close, target: nil, action: nil)
        state.close = close; state.render()
        XCTAssertTrue(page.navigationItem.rightBarButtonItems?.first === close)
        XCTAssertEqual(page.navigationItem.rightBarButtonItems?.count, 2)
        actions[0].isEnabled = false
        page.inspectorActions = Array(actions.prefix(2))
        XCTAssertEqual(page.navigationItem.rightBarButtonItems?.last?.accessibilityIdentifier, "Osservio.MoreActions")
        XCTAssertTrue(page.inspectorActions?.first === actions[0])
        XCTAssertFalse(page.inspectorActions?.first?.isEnabled ?? true)
        page.inspectorActions = [actions[0]]
        XCTAssertTrue(page.navigationItem.rightBarButtonItems?.last === actions[0])
        page.inspectorActions = []
        XCTAssertEqual(page.navigationItem.rightBarButtonItems, [close])
    }
    @MainActor func testOverflowMenuUsesReadingOrderAndKeepsDisabledActions() throws {
        let page = UIViewController(), window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = UINavigationController(rootViewController: page); window.makeKeyAndVisible()
        defer { window.isHidden = true; window.rootViewController = nil }
        let items = ["Copy", "Refresh", "Settings"].map { UIBarButtonItem(title: $0, style: .plain, target: nil, action: nil) }
        items[0].isEnabled = false; page.inspectorActions = items
        let more = try XCTUnwrap(page.navigationItem.rightBarButtonItems?.first)
        _ = try XCTUnwrap(more.target as? NSObject).perform(try XCTUnwrap(more.action), with: more)
        let navigation = try XCTUnwrap(page.presentedViewController as? UINavigationController)
        let panel = try XCTUnwrap(navigation.topViewController as? InspectorActionPanel); panel.loadViewIfNeeded()
        XCTAssertEqual(panel.tableView(panel.tableView, numberOfRowsInSection: 0), 3)
        for (row, title) in ["Copy", "Refresh", "Settings"].enumerated() {
            let cell = panel.tableView(panel.tableView, cellForRowAt: IndexPath(row: row, section: 0))
            XCTAssertEqual(cell.textLabel?.text, title)
            XCTAssertEqual(cell.isUserInteractionEnabled, row != 0)
        }
    }
}
#endif
