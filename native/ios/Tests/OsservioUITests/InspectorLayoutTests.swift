#if canImport(UIKit)
import OsservioCore
import OsservioStore
@testable import OsservioUI
import UIKit
import XCTest

final class InspectorLayoutTests: XCTestCase {
    @MainActor
    func testFilterRowsKeepCompleteLabelsInBothLanguagesAndTextSizes() throws {
        let previous = InspectorPreferences.shared.language
        defer { InspectorPreferences.shared.language = previous }
        for language in InspectorPreferences.Language.allCases {
            InspectorPreferences.shared.language = language
            for category in [UIContentSizeCategory.large, .accessibilityExtraExtraExtraLarge] {
                for width in [CGFloat(320), CGFloat(390)] {
                    var query = InspectorQuery(); query.select(.transport, value: "webSocket")
                    let groupSets = [[InspectorFilterGroup(.level, values: ["debug", "info", "warning", "error"])],
                        InspectorFilterOptions.network([], sockets: [], query: query)]
                    for groups in groupSets {
                        let viewer = InspectorFilterViewController(query: query, groups: groups) { _ in XCTFail("Layout applied a draft") }
                        let host = UIViewController(), window = UIWindow(frame: CGRect(x: 0, y: 0, width: width, height: 700))
                        defer { window.isHidden = true; window.rootViewController = nil }
                        window.rootViewController = host; host.loadViewIfNeeded(); host.addChild(viewer)
                        let traits = UITraitCollection(preferredContentSizeCategory: category)
                        host.setOverrideTraitCollection(traits, forChild: viewer)
                        traits.performAsCurrent { viewer.loadViewIfNeeded() }
                        host.view.addSubview(viewer.view); viewer.didMove(toParent: host); window.makeKeyAndVisible()
                        viewer.view.frame = window.bounds; viewer.view.layoutIfNeeded()
                        var options = 0, headings = 0
                        var messageRow: (IndexPath, CGSize, CGFloat)?
                        for section in 0..<viewer.numberOfSections(in: viewer.tableView) {
                            for row in 0..<viewer.tableView(viewer.tableView, numberOfRowsInSection: section) {
                                let path = IndexPath(row: row, section: section)
                                viewer.tableView.scrollToRow(at: path, at: .middle, animated: false); viewer.tableView.layoutIfNeeded()
                                let cell = try XCTUnwrap(viewer.tableView.cellForRow(at: path))
                                guard let label = cell.textLabel, let title = label.text, !title.isEmpty else { continue }
                                if cell.accessibilityTraits.contains(.header) { headings += 1 }
                                else if cell.accessibilityTraits.contains(.button) { options += 1; XCTAssertGreaterThanOrEqual(cell.bounds.height, 44) }
                                if cell.reuseIdentifier == "choice" {
                                    let mark = try XCTUnwrap(cell.accessoryView as? UIImageView)
                                    XCTAssertEqual(mark.image != nil, cell.accessibilityTraits.contains(.selected), title)
                                }
                                let fullText = UILabel(); fullText.text = title; fullText.font = label.font
                                fullText.numberOfLines = 0; fullText.lineBreakMode = .byWordWrapping
                                let required = fullText.sizeThatFits(CGSize(width: label.bounds.width, height: .greatestFiniteMagnitude))
                                XCTAssertGreaterThanOrEqual(label.bounds.height + 1, ceil(required.height), title)
                                XCTAssertLessThanOrEqual(cell.convert(label.bounds, from: label).maxY, cell.bounds.height + 1, title)
                                if title == InspectorFilterOptions.label(.activity, "messages") {
                                    messageRow = (path, label.bounds.size, cell.bounds.height)
                                }
                            }
                        }
                        XCTAssertGreaterThanOrEqual(options, 5); XCTAssertGreaterThanOrEqual(headings, 1)
                        if let (path, size, height) = messageRow {
                            viewer.tableView(viewer.tableView, didSelectRowAt: path)
                            viewer.tableView.scrollToRow(at: path, at: .middle, animated: false); viewer.tableView.layoutIfNeeded()
                            let selected = try XCTUnwrap(viewer.tableView.cellForRow(at: path))
                            XCTAssertTrue(selected.accessibilityTraits.contains(.selected))
                            XCTAssertNotNil((selected.accessoryView as? UIImageView)?.image)
                            XCTAssertEqual(selected.textLabel?.bounds.width ?? 0, size.width, accuracy: 1)
                            XCTAssertEqual(selected.bounds.height, height, accuracy: 1)
                        }
                    }
                }
            }
        }
    }
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
                    table.delegate?.scrollViewWillBeginDragging?(table)
                    table.scrollToRow(at: IndexPath(row: 0, section: 0), at: .top, animated: false)
                    table.layoutIfNeeded()
                    let row = table.rectForRow(at: IndexPath(row: 0, section: 0))
                    XCTAssertTrue(row.intersects(table.bounds), label)
                    let header = try XCTUnwrap(descendants(viewer.view).compactMap { $0 as? InspectorFeedHeader }.first)
                    for button in [header.pause, header.filters, header.actions] where !button.isHidden {
                        XCTAssertFalse((button.accessibilityLabel ?? "").isEmpty, label)
                        XCTAssertGreaterThanOrEqual(button.bounds.width, 44, label)
                        XCTAssertGreaterThanOrEqual(button.bounds.height, 44, label)
                    }
                    let buttons = descendants(viewer.view).compactMap { $0 as? UIButton }.filter { !$0.isHidden && $0.title(for: .normal) != nil }
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
                table.delegate?.scrollViewWillBeginDragging?(table)
                table.scrollRectToVisible(footer.frame, animated: false)
                XCTAssertTrue(footer.frame.intersects(table.bounds))
                heights.append(header.frame.height)
            }
            XCTAssertGreaterThan(heights[1], heights[0])
            XCTAssertEqual(heights[0], heights[2], accuracy: 1)
            viewer.beginAppearanceTransition(false, animated: false); viewer.endAppearanceTransition()
        }
    }

    @MainActor
    func testLatestRemainsAtTopAfterRealRowsSearchAndReappearance() async throws {
        for viewer in try viewers(count: 32) {
            let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
            window.rootViewController = viewer; window.makeKeyAndVisible()
            defer { window.isHidden = true; window.rootViewController = nil }
            viewer.beginAppearanceTransition(true, animated: false); viewer.endAppearanceTransition()
            window.layoutIfNeeded()
            let table = try XCTUnwrap(descendants(viewer.view).compactMap { $0 as? UITableView }.first)
            let header = try XCTUnwrap(descendants(viewer.view).compactMap { $0 as? InspectorFeedHeader }.first)
            XCTAssertGreaterThan(table.numberOfRows(inSection: 0), 20)
            table.delegate?.scrollViewWillBeginDragging?(table)
            table.scrollToRow(at: IndexPath(row: 20, section: 0), at: .top, animated: false)
            table.layoutIfNeeded()
            XCTAssertGreaterThan(table.contentOffset.y, 200)
            let restingSize = header.pause.bounds.size
            viewer.beginAppearanceTransition(false, animated: false); viewer.endAppearanceTransition()
            viewer.beginAppearanceTransition(true, animated: true); viewer.endAppearanceTransition()
            table.layoutIfNeeded()
            XCTAssertEqual(header.pause.bounds.size, restingSize)
            XCTAssertFalse(header.pause.isHidden)
            try invoke(header.pause, event: .touchUpInside)
            for _ in 0..<20 {
                try await Task.sleep(nanoseconds: 20_000_000)
                table.setNeedsLayout(); table.layoutIfNeeded()
                XCTAssertEqual(table.contentOffset.y, -table.adjustedContentInset.top, accuracy: 1, "\(type(of: viewer))")
            }
            XCTAssertTrue(header.pause.isHidden)
            // Search completions reload a real window after the explicit jump.
            header.search.text = viewer is LogInspectorViewController ? "evidence" : "example"
            try invoke(header.search, event: .editingChanged)
            for _ in 0..<60 {
                try await Task.sleep(nanoseconds: 20_000_000)
                table.layoutIfNeeded()
                XCTAssertEqual(table.contentOffset.y, -table.adjustedContentInset.top, accuracy: 1)
            }
            table.delegate?.scrollViewWillBeginDragging?(table)
            table.setContentOffset(CGPoint(x: 0, y: 160), animated: false); table.layoutIfNeeded()
            XCTAssertFalse(header.pause.isHidden)
            XCTAssertEqual(table.contentOffset.y, 160, accuracy: 1, "A drag must release the latest anchor")
            try invoke(header.pause, event: .touchUpInside)
            _ = table.accessibilityScroll(.down)
            table.setContentOffset(CGPoint(x: 0, y: 180), animated: false); table.layoutIfNeeded()
            XCTAssertFalse(header.pause.isHidden)
            XCTAssertEqual(table.contentOffset.y, 180, accuracy: 1, "Accessibility scrolling must also release the anchor")
            viewer.beginAppearanceTransition(false, animated: false); viewer.endAppearanceTransition()
        }
    }
    @MainActor private func invoke(_ control: UIControl, event: UIControl.Event) throws {
        for target in control.allTargets {
            let receiver = try XCTUnwrap(target.base as? NSObject)
            for action in control.actions(forTarget: receiver, forControlEvent: event) ?? [] {
                _ = receiver.perform(NSSelectorFromString(action), with: control)
            }
        }
    }

    @MainActor private func viewers(empty: Bool = false, count: Int = 1) throws -> [UIViewController] {
        let capture = CaptureController(baselineEnabled: true); try capture.startDetailed()
        let logs = try MemoryLogStore(), crumbs = try MemoryBreadcrumbStore()
        let requests = try MemoryHTTPStore(), sockets = try MemoryWebSocketStore()
        let log = try LogRecorder(sink: logs, captureController: capture)
        let breadcrumb = try BreadcrumbRecorder(sink: crumbs, captureController: capture, eventsPerSecond: 100)
        let http = try HTTPRecorder(sink: requests, captureController: capture, privacy: HTTPPrivacyPolicy())
        let ws = try WebSocketRecorder(sink: sockets, captureController: capture, privacy: WebSocketPrivacyPolicy())
        for index in 0..<(empty ? 0 : count) {
            XCTAssertEqual(log.record(.info, category: "layout", message: .publicText("Synthetic layout evidence \(index) " + String(repeating: "variable row height ", count: index % 7))), .recorded)
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
