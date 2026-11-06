#if canImport(UIKit)
import Foundation
import OsservioCore
@testable import OsservioUI
import UIKit
import XCTest

final class IncidentEvidenceTests: XCTestCase {
    private let id = "12345678-1234-1234-1234-123456789abc"
    @MainActor
    func testSavedHTTPNavigationSearchAndContentHaveNoLiveRefresh() async throws {
        let bodyID = id + ".response"
        let event = try HTTPEvent.restore(id: id, sessionID: id, sequence: 7, revision: 2, timestampMillis: 123, method: "GET", source: .host,
            url: "https://example.com/frozen", responseURL: nil, requestHeaders: [], responseHeaders: [], headersLimited: false, statusCode: 200,
            state: .completed, failure: nil, durationMillis: 4, requestBody: .restore(state: .omitted, blobID: nil, observedBytes: 0),
            responseBody: .restore(state: .available, blobID: bodyID, observedBytes: 15))
        let http = try IncidentHTTP.restore(events: [event], payloads: [bodyID: .restore(id: bodyID, state: .available, text: "BODY_ONLY_TOKEN")],
            observedAtMillis: 456, sourceEventCount: 3, sourceEvictedEvents: 5, sourceEvictedBodies: 7)
        let snapshot = try IncidentSnapshot.capture(http: http)
        let viewer = IncidentSnapshotViewController(snapshot: snapshot), window = show(viewer)
        defer { window.isHidden = true; window.rootViewController = nil }
        viewer.tableView(viewer.tableView, didSelectRowAt: IndexPath(row: 1, section: 2))
        try await settled(XCTUnwrap(viewer.navigationController))
        let group = try XCTUnwrap(viewer.navigationController?.topViewController as? IncidentEvidenceViewController); group.loadViewIfNeeded()
        let search = try XCTUnwrap(group.navigationItem.searchController)
        XCTAssertEqual(group.tableView(group.tableView, numberOfRowsInSection: 0), 1)
        search.searchBar.text = "BODY_ONLY_TOKEN"; group.updateSearchResults(for: search)
        XCTAssertEqual(group.tableView(group.tableView, numberOfRowsInSection: 0), 0)
        search.searchBar.text = "frozen"; group.updateSearchResults(for: search)
        XCTAssertEqual(group.tableView(group.tableView, numberOfRowsInSection: 0), 1)
        group.tableView(group.tableView, didSelectRowAt: IndexPath(row: 0, section: 0))
        try await settled(XCTUnwrap(group.navigationController))
        let detail = try XCTUnwrap(group.navigationController?.topViewController as? HTTPDetailViewController); detail.loadViewIfNeeded()
        XCTAssertEqual(detail.navigationItem.rightBarButtonItems?.count, 1)
        let section = try XCTUnwrap(find(detail.view, UISegmentedControl.self)), text = try XCTUnwrap(find(detail.view, UITextView.self))
        section.selectedSegmentIndex = 2; try changed(section)
        XCTAssertTrue(text.text.contains("BODY_ONLY_TOKEN")); XCTAssertTrue(text.text.contains(HTTPPresentation.text("incident_frozen_detail")))
        section.selectedSegmentIndex = 0; try changed(section)
        XCTAssertTrue(text.text.contains(event.id)); XCTAssertFalse(text.text.contains("BODY_ONLY_TOKEN"))
    }
    @MainActor
    func testSavedSocketDetailKeepsMissingStatesAndAvailableEmptyTextDistinct() throws {
        for state in [IncidentPayloadState.available, .notSelected, .unavailable, .busy, .budgetExceeded] {
            let event = try WebSocketEvent.restore(id: id, connectionID: id, sessionID: id, sequence: 9, timestampMillis: 123,
                url: "wss://example.com", source: .host, kind: .message, headers: [], headersLimited: false, statusCode: nil, closeCode: nil,
                failure: nil, direction: .incoming, outcome: .received, relatedMessageID: nil, payload: .restore(state: .available, blobID: id, byteCount: 0))
            let payload = try IncidentPayload.restore(id: id, state: state, text: state == .available ? "" : nil)
            let detail = WebSocketDetailViewController(event: event, savedPayloads: [id: payload]), window = show(detail)
            defer { window.isHidden = true; window.rootViewController = nil }
            XCTAssertEqual(detail.navigationItem.rightBarButtonItems?.count, 1)
            let section = try XCTUnwrap(find(detail.view, UISegmentedControl.self)), text = try XCTUnwrap(find(detail.view, UITextView.self))
            section.selectedSegmentIndex = 1; try changed(section)
            XCTAssertTrue(text.text.contains(IncidentPayloadPresentation.content(payload)))
            XCTAssertEqual(text.text.contains(HTTPPresentation.text("http_body_empty")), state == .available)
            XCTAssertTrue(text.text.contains(HTTPPresentation.text("incident_frozen_detail")))
        }
    }
    @MainActor
    func testAbsentAndEmptyAttachmentRowsAndLogEvidenceRemainExplicit() async throws {
        let log = try LogEvent.restore(sessionID: id, sequence: 11, timestampMillis: 123, level: .error,
            category: "fixture", message: "[private]", isPrivate: true, isTruncated: false)
        let snapshot = try IncidentSnapshot.capture(logs: .restore(events: [log], observedAtMillis: 456, sourceEventCount: 1, sourceEvictedCount: 0),
            webSockets: .restore(events: [], payloads: [:], observedAtMillis: 789, sourceEventCount: 3, sourceEvictedEvents: 0, sourceEvictedPayloads: 0))
        let viewer = IncidentSnapshotViewController(snapshot: snapshot), window = show(viewer)
        defer { window.isHidden = true; window.rootViewController = nil }
        let absent = viewer.tableView(viewer.tableView, cellForRowAt: IndexPath(row: 1, section: 2))
        let empty = viewer.tableView(viewer.tableView, cellForRowAt: IndexPath(row: 2, section: 2))
        XCTAssertEqual(absent.accessoryType, .none); XCTAssertEqual(empty.accessoryType, .disclosureIndicator)
        XCTAssertEqual(absent.detailTextLabel?.text, RuntimePresentation.text("incident_not_attached")); XCTAssertEqual(empty.detailTextLabel?.text, "0")
        viewer.tableView(viewer.tableView, didSelectRowAt: IndexPath(row: 0, section: 2))
        try await settled(XCTUnwrap(viewer.navigationController))
        let group = try XCTUnwrap(viewer.navigationController?.topViewController as? IncidentEvidenceViewController); group.loadViewIfNeeded()
        group.tableView(group.tableView, didSelectRowAt: IndexPath(row: 0, section: 0))
        try await settled(XCTUnwrap(group.navigationController))
        let detail = try XCTUnwrap(group.navigationController?.topViewController as? LogDetailViewController); detail.loadViewIfNeeded()
        let text = try XCTUnwrap(detail.view as? UITextView)
        XCTAssertTrue(text.text.contains(id)); XCTAssertTrue(text.text.contains("#11")); XCTAssertTrue(text.text.contains("[private]"))
    }
    @MainActor private func show(_ controller: UIViewController) -> UIWindow {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = UINavigationController(rootViewController: controller); window.makeKeyAndVisible()
        controller.loadViewIfNeeded(); window.layoutIfNeeded(); return window
    }
    @MainActor private func find<T: UIView>(_ view: UIView, _ type: T.Type) -> T? {
        if let found = view as? T { return found }
        for child in view.subviews { if let found = find(child, type) { return found } }; return nil
    }
    @MainActor private func settled(_ navigation: UINavigationController) async throws {
        for _ in 0..<100 {
            if navigation.transitionCoordinator == nil { return }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        XCTFail("Navigation did not finish its transition")
    }
    @MainActor private func changed(_ control: UIControl) throws {
        var sent = false
        for target in control.allTargets {
            guard let receiver = target.base as? NSObject else { continue }
            for action in control.actions(forTarget: receiver, forControlEvent: .valueChanged) ?? [] {
                _ = receiver.perform(NSSelectorFromString(action), with: control); sent = true
            }
        }
        XCTAssertTrue(sent)
    }
}
#endif
