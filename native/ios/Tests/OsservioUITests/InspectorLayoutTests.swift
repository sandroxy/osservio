#if canImport(UIKit)
import OsservioCore
import OsservioStore
@testable import OsservioUI
import UIKit
import XCTest

final class InspectorLayoutTests: XCTestCase {
    @MainActor
    func testCompactLargeTextKeepsHistoryReachableAndControlsReadable() throws {
        for category in [UIContentSizeCategory.large, .accessibilityExtraExtraExtraLarge] {
            for size in [CGSize(width: 320, height: 240), CGSize(width: 568, height: 180)] {
                for viewer in try viewers() {
                    let host = UIViewController(), window = UIWindow(frame: CGRect(x: 0, y: 0, width: 640, height: 800))
                    defer { window.isHidden = true; window.rootViewController = nil }
                    window.rootViewController = host; host.loadViewIfNeeded()
                    host.addChild(viewer)
                    let traits = UITraitCollection(preferredContentSizeCategory: category)
                    host.setOverrideTraitCollection(traits, forChild: viewer)
                    traits.performAsCurrent { viewer.loadViewIfNeeded() }
                    host.view.addSubview(viewer.view); viewer.didMove(toParent: host)
                    window.makeKeyAndVisible(); host.view.layoutIfNeeded()
                    viewer.view.frame = CGRect(origin: .zero, size: size)
                    viewer.beginAppearanceTransition(true, animated: false); viewer.endAppearanceTransition()
                    viewer.view.setNeedsLayout(); viewer.view.layoutIfNeeded()
                    let table = try XCTUnwrap(descendants(viewer.view).compactMap { $0 as? UITableView }.first)
                    table.layoutIfNeeded()
                    let label = "\(type(of: viewer)) \(category.rawValue) \(size)"
                    XCTAssertGreaterThanOrEqual(table.bounds.height, 44, label)
                    XCTAssertGreaterThan(table.numberOfRows(inSection: 0), 0, label)
                    table.scrollToRow(at: IndexPath(row: 0, section: 0), at: .top, animated: false)
                    table.layoutIfNeeded()
                    let row = table.rectForRow(at: IndexPath(row: 0, section: 0))
                    XCTAssertTrue(row.intersects(table.bounds), label)
                    let buttons = descendants(viewer.view).compactMap { $0 as? UIButton }.filter { $0.title(for: .normal) != nil }
                    XCTAssertGreaterThanOrEqual(buttons.count, 2, label)
                    for button in buttons {
                        XCTAssertGreaterThanOrEqual(button.bounds.height, 44, label)
                        let title = try XCTUnwrap(button.titleLabel)
                        XCTAssertTrue(title.adjustsFontForContentSizeCategory, label)
                        if category.isAccessibilityCategory { XCTAssertGreaterThan(title.font.pointSize, 20, label) }
                        let needed = title.sizeThatFits(CGSize(width: title.bounds.width, height: .greatestFiniteMagnitude))
                        XCTAssertLessThanOrEqual(ceil(needed.height), title.bounds.height + 1, label)
                    }
                    viewer.beginAppearanceTransition(false, animated: false); viewer.endAppearanceTransition()
                    window.isHidden = true; window.rootViewController = nil
                }
            }
        }
    }

    @MainActor
    func testEmptyStatesStayBelowControlsAndResizeWithTraitsAndWidth() throws {
        for viewer in try viewers(empty: true) {
            let host = UIViewController(), window = UIWindow(frame: CGRect(x: 0, y: 0, width: 640, height: 800))
            defer { window.isHidden = true; window.rootViewController = nil }
            window.rootViewController = host; host.loadViewIfNeeded(); host.addChild(viewer)
            host.view.addSubview(viewer.view); viewer.didMove(toParent: host)
            window.makeKeyAndVisible(); host.view.layoutIfNeeded()
            viewer.beginAppearanceTransition(true, animated: false); viewer.endAppearanceTransition()
            let table = try XCTUnwrap(descendants(viewer.view).compactMap { $0 as? UITableView }.first)
            var heights: [CGFloat] = []
            for (category, size) in [(UIContentSizeCategory.large, CGSize(width: 320, height: 240)),
                                     (.accessibilityExtraExtraExtraLarge, CGSize(width: 568, height: 180)),
                                     (.large, CGSize(width: 320, height: 240))] {
                host.setOverrideTraitCollection(UITraitCollection(preferredContentSizeCategory: category), forChild: viewer)
                viewer.view.frame = CGRect(origin: .zero, size: size)
                viewer.view.setNeedsLayout(); viewer.view.layoutIfNeeded(); table.setNeedsLayout(); table.layoutIfNeeded()
                let header = try XCTUnwrap(table.tableHeaderView), footer = try XCTUnwrap(table.tableFooterView)
                XCTAssertEqual(table.numberOfRows(inSection: 0), 0)
                XCTAssertGreaterThanOrEqual(footer.frame.minY, header.frame.maxY - 1)
                XCTAssertGreaterThan(footer.frame.height, 32)
                XCTAssertEqual(header.frame.width, table.bounds.width)
                XCTAssertEqual(footer.frame.width, table.bounds.width)
                XCTAssertNil(table.backgroundView)
                table.scrollRectToVisible(footer.frame, animated: false)
                XCTAssertTrue(footer.frame.intersects(table.bounds))
                heights.append(header.frame.height)
            }
            XCTAssertGreaterThan(heights[1], heights[0])
            XCTAssertEqual(heights[0], heights[2], accuracy: 1)
            viewer.beginAppearanceTransition(false, animated: false); viewer.endAppearanceTransition()
        }
    }

    @MainActor private func viewers(empty: Bool = false) throws -> [UIViewController] {
        let capture = CaptureController(baselineEnabled: true); try capture.startDetailed()
        let logs = try MemoryLogStore(), crumbs = try MemoryBreadcrumbStore()
        let requests = try MemoryHTTPStore(), sockets = try MemoryWebSocketStore()
        let log = try LogRecorder(sink: logs, captureController: capture)
        let breadcrumb = try BreadcrumbRecorder(sink: crumbs, captureController: capture)
        let http = try HTTPRecorder(sink: requests, captureController: capture, privacy: HTTPPrivacyPolicy())
        let ws = try WebSocketRecorder(sink: sockets, captureController: capture, privacy: WebSocketPrivacyPolicy())
        if !empty {
            XCTAssertEqual(log.record(.info, category: "layout", message: .publicText("Synthetic layout evidence")), .recorded)
            XCTAssertEqual(breadcrumb.record(.hostMarker, label: "layout.ready"), .recorded)
            let request = try XCTUnwrap(http.begin(method: "GET", url: "https://example.com").token)
            XCTAssertEqual(http.receiveResponse(request, statusCode: 200, headers: []), .recorded)
            XCTAssertEqual(http.finish(request, state: .completed), .recorded)
            let connection = try XCTUnwrap(ws.begin(url: "wss://example.com").token)
            XCTAssertEqual(ws.lifecycle(connection, kind: .closed, closeCode: 1000), .recorded)
        }
        return [LogInspectorViewController(recorder: log, history: logs),
                HTTPInspectorViewController(recorder: http, history: requests),
                WebSocketInspectorViewController(recorder: ws, history: sockets),
                BreadcrumbInspectorViewController(recorder: breadcrumb, history: crumbs)]
    }
    @MainActor private func descendants(_ view: UIView) -> [UIView] {
        [view] + view.subviews.flatMap { descendants($0) }
    }
}
#endif
