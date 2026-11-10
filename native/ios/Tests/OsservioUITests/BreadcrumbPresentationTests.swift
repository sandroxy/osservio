#if canImport(UIKit)
import UIKit
import OsservioCore
@testable import OsservioUI
import XCTest

final class BreadcrumbPresentationTests: XCTestCase {
    @MainActor
    func testFrozenDetailKeepsIdentifiersCollapsedAndBodySeparateFromMetadata() throws {
        let event = try BreadcrumbEvent.restore(id: "00000000-0000-0000-0000-000000000001", sessionID: "00000000-0000-0000-0000-000000000002",
            sequence: 7, timestampMillis: 1_000, sessionElapsedMillis: 42, kind: .hostMarker, label: "fixture.ready",
            message: "{\"item\":42}", isPrivate: false, isTruncated: false, value: nil, retention: .restartRelevant)
        let detail = BreadcrumbDetailViewController(event: event); detail.loadViewIfNeeded()
        XCTAssertFalse(detail.sections.flatMap(\.rows).contains { $0.value == event.id })
        XCTAssertFalse(descendants(detail.view).contains { $0 is UISearchTextField })
        XCTAssertTrue(detail.sections.flatMap(\.rows).contains { $0.value == "{\"item\":42}" && $0.action != nil })
        XCTAssertTrue(detail.sections.flatMap(\.rows).contains { $0.label == InspectorLocalization.text("crumb_restart_relevant") })
        let expand = try XCTUnwrap(detail.sections.last?.rows.first?.action); expand()
        XCTAssertTrue(detail.sections.flatMap(\.rows).contains { $0.value == event.id })
        let copy = try XCTUnwrap(detail.inspectorPrimaryAction)
        XCTAssertNotNil(copy.target); XCTAssertNotNil(copy.action)
        // System clipboard delivery is verified in an attached consumer, not a headless test process.
        // Reappearance never consults a live history or changes the entry-time snapshot.
        detail.viewWillAppear(false)
        XCTAssertTrue(detail.sections.flatMap(\.rows).contains { $0.value == event.id })
    }

    @MainActor
    func testBaselineSettingsDoNotStopAnActiveDebugSession() throws {
        let capture = CaptureController(baselineEnabled: true, debugEnabled: true)
        let session = capture.status()?.sessionID
        let settings = InspectorCaptureSettingsViewController(baseline: capture, controllers: [("Osservio", capture)])
        settings.loadViewIfNeeded()
        let cell = settings.tableView(settings.tableView, cellForRowAt: IndexPath(row: 0, section: 0))
        let toggle = try XCTUnwrap(cell.accessoryView as? UISwitch)
        toggle.isOn = false
        for target in toggle.allTargets {
            for action in toggle.actions(forTarget: target.base, forControlEvent: .valueChanged) ?? [] {
                _ = (target.base as? NSObject)?.perform(NSSelectorFromString(action), with: toggle)
            }
        }
        XCTAssertEqual(capture.status()?.baselineEnabled, false); XCTAssertEqual(capture.status()?.debugEnabled, true)
        XCTAssertEqual(capture.status()?.sessionID, session)
    }
    @MainActor private func descendants(_ view: UIView) -> [UIView] { [view] + view.subviews.flatMap { descendants($0) } }
}
#endif
