#if canImport(UIKit)
import OsservioCore
import OsservioStore
@testable import OsservioUI
import UIKit
import XCTest

final class InspectorSessionTests: XCTestCase {
    @MainActor
    func testFactoryCanRevealEntryWithoutReenteringConstruction() throws {
        let sources = try Sources(), host = hostWindow()
        var session: InspectorSession!
        var creations = 0
        session = InspectorSession(window: host) {
            creations += 1; XCTAssertTrue(session.showEntry()); return sources.makeInspector()
        }
        defer { session.dispose(); host.isHidden = true; host.rootViewController = nil; _ = sources.capture.stopAll() }
        XCTAssertTrue(session.expand()); XCTAssertEqual(creations, 1)
        XCTAssertEqual(session.presentation, .expanded)
    }

    @MainActor
    func testReleasingOwnerRestoresHostAndReleasesRetainedHierarchy() async throws {
        let sources = try Sources(), host = hostWindow()
        defer { host.isHidden = true; host.rootViewController = nil; _ = sources.capture.stopAll() }
        weak var controller: InspectorViewController?
        weak var window: UIWindow?
        var session: InspectorSession? = InspectorSession(window: host) {
            let inspector = sources.makeInspector(); controller = inspector; return inspector
        }
        XCTAssertTrue(session?.expand() == true)
        window = controller?.view.window
        XCTAssertNotNil(window)
        session = nil
        try await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertNil(controller); XCTAssertNil(window); XCTAssertTrue(host.isKeyWindow)
        XCTAssertEqual(sources.capture.status()?.mode, .detailed)
    }

    @MainActor
    func testLazyEntryRepeatedTransitionsAndDisposalLeaveCaptureIndependent() async throws {
        let sources = try Sources(), host = hostWindow()
        defer { host.isHidden = true; host.rootViewController = nil; _ = sources.capture.stopAll() }
        var creations = 0
        weak var created: InspectorViewController?
        let session = InspectorSession(window: host) {
            creations += 1
            let controller = sources.makeInspector(); created = controller
            return controller
        }
        XCTAssertEqual(session.presentation, .hidden); XCTAssertEqual(creations, 0)
        XCTAssertTrue(session.showEntry()); XCTAssertTrue(session.showEntry())
        XCTAssertEqual(creations, 0); XCTAssertTrue(host.isKeyWindow)
        XCTAssertTrue(session.expand()); XCTAssertEqual(creations, 1)
        XCTAssertTrue(created?.view.window?.isKeyWindow == true)
        for _ in 0..<30 { session.collapse(); XCTAssertTrue(session.expand()) }
        XCTAssertEqual(creations, 1); XCTAssertEqual(session.presentation, .expanded)
        XCTAssertTrue(created?.view.window?.isKeyWindow == true)
        session.hideEntry()
        XCTAssertEqual(session.presentation, .hidden); XCTAssertTrue(host.isKeyWindow)
        XCTAssertTrue(created?.view.window?.isHidden == true)
        XCTAssertEqual(sources.capture.status()?.mode, .detailed)
        session.dispose(); session.dispose()
        XCTAssertFalse(session.showEntry()); XCTAssertFalse(session.expand())
        try await Task.sleep(nanoseconds: 80_000_000)
        XCTAssertNil(created, "Disposal must release the retained controller hierarchy")
        XCTAssertEqual(sources.capture.status()?.mode, .detailed)
    }

    @MainActor
    func testDeepReaderSurvivesMinimize() async throws {
        let sources = try Sources(), host = hostWindow()
        var created: InspectorViewController?
        let session = InspectorSession(window: host) { let controller = sources.makeInspector(); created = controller; return controller }
        defer { session.dispose(); host.isHidden = true; host.rootViewController = nil; _ = sources.capture.stopAll() }
        XCTAssertTrue(session.expand())
        let root = try XCTUnwrap(created), navigation = try XCTUnwrap(root.viewControllers?.first as? InspectorNavigationController)
        let reader = InspectorContentViewController(title: "Fixture", content: String(repeating: "retained body\n", count: 150), code: true)
        navigation.pushViewController(reader, animated: false)
        reader.loadViewIfNeeded(); reader.view.layoutIfNeeded()
        let text = try XCTUnwrap(descendants(reader.view).compactMap { $0 as? UITextView }.first)
        text.setContentOffset(CGPoint(x: 0, y: 80), animated: false)
        let position = text.contentOffset
        let minimize = try XCTUnwrap(reader.navigationItem.rightBarButtonItems?.first { $0.accessibilityIdentifier == "Osservio.Minimize" })
        _ = (minimize.target as? NSObject)?.perform(try XCTUnwrap(minimize.action))
        XCTAssertEqual(session.presentation, .entry)
        XCTAssertTrue(session.expand())
        XCTAssertTrue(navigation.topViewController === reader); XCTAssertEqual(text.contentOffset, position)
        XCTAssertNotNil(reader.inspectorPrimaryAction)
    }

    @MainActor
    func testTransientFilterHasResetAndApplyWithoutSessionMinimize() throws {
        let sources = try Sources(), host = hostWindow()
        let session = InspectorSession(window: host) { sources.makeInspector() }
        defer { session.dispose(); host.isHidden = true; host.rootViewController = nil; _ = sources.capture.stopAll() }
        var applied = false
        let filter = InspectorFilterViewController(query: InspectorQuery(), groups: []) { _ in applied = true }
        let navigation = InspectorNavigationController(rootViewController: filter)
        navigation.transientPanel = true; navigation.session = session
        navigation.loadViewIfNeeded(); filter.loadViewIfNeeded()
        XCTAssertEqual(filter.navigationItem.leftBarButtonItem?.title, InspectorLocalization.text("ui_reset"))
        XCTAssertEqual(filter.inspectorPrimaryAction?.title, InspectorLocalization.text("ui_apply"))
        XCTAssertFalse(filter.navigationItem.rightBarButtonItems?.contains { $0.accessibilityIdentifier == "Osservio.Minimize" } ?? false)
        XCTAssertFalse(applied)
        // UIKit modal dismissal and draft restoration require a running consumer application.
    }

    @MainActor
    func testEntryWindowPassesUnoccupiedTouchesAndDockingUsesAvailableBounds() {
        let host = InspectorEntryViewController(), window = InspectorEntryWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = host; window.isHidden = false
        defer { window.isHidden = true; window.rootViewController = nil }
        host.loadViewIfNeeded(); host.view.layoutIfNeeded()
        XCTAssertNil(window.hitTest(CGPoint(x: 100, y: 100), with: nil))
        XCTAssertTrue(window.hitTest(host.entry.center, with: nil) === host.entry)
        let portrait = CGRect(x: 32, y: 80, width: 326, height: 700)
        let dock = InspectorDockPosition.nearest(to: CGPoint(x: 20, y: 430), in: portrait)
        XCTAssertFalse(dock.trailing); XCTAssertEqual(dock.fraction, 0.5, accuracy: 0.001)
        XCTAssertEqual(dock.center(in: CGRect(x: 32, y: 32, width: 780, height: 300)), CGPoint(x: 32, y: 182))
        XCTAssertEqual(InspectorDockPosition.nearest(to: CGPoint(x: -10, y: -10), in: portrait).fraction, 0)
        XCTAssertEqual(InspectorDockPosition.nearest(to: CGPoint(x: 900, y: 900), in: portrait).fraction, 1)
    }

    @MainActor
    private func hostWindow() -> UIWindow {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = UIViewController(); window.makeKeyAndVisible()
        return window
    }
    @MainActor private func descendants(_ view: UIView) -> [UIView] { [view] + view.subviews.flatMap { descendants($0) } }

    @MainActor private final class Sources {
        let capture = CaptureController()
        let logs: MemoryLogStore, requests: MemoryHTTPStore
        let logger: LogRecorder, http: HTTPRecorder
        init() throws {
            logs = try MemoryLogStore(); requests = try MemoryHTTPStore()
            logger = try LogRecorder(sink: logs, captureController: capture)
            http = try HTTPRecorder(sink: requests, captureController: capture, privacy: HTTPPrivacyPolicy())
            try capture.startDetailed()
        }
        func makeInspector() -> InspectorViewController {
            InspectorViewController(logRecorder: logger, logHistory: logs, httpRecorder: http, httpHistory: requests)
        }
    }
}
#endif
