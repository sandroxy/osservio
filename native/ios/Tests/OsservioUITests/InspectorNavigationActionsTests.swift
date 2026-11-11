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
        XCTAssertEqual(page.navigationItem.rightBarButtonItems, Array(actions.prefix(2)))
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
}
#endif
