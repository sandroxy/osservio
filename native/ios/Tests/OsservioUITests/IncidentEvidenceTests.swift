#if canImport(UIKit)
import Foundation
import OsservioCore
import OsservioStore
@testable import OsservioUI
import UIKit
import XCTest

final class IncidentEvidenceTests: XCTestCase {
    private let id = "12345678-1234-1234-1234-123456789abc"
    @MainActor private func labels(_ view: UIView) -> [String] {
        ((view as? UILabel)?.text.map { [$0] } ?? []) + view.subviews.flatMap(labels)
    }
    @MainActor
    func testLiveSectionsDoNotReadBodiesUntilReaderIsOpened() async throws {
        let bodyID = id + ".response"
        let event = try HTTPEvent.restore(id: id, sessionID: id, sequence: 1, revision: 2, timestampMillis: 123, method: "GET", source: .host,
            url: "https://example.com/fixture", responseURL: nil, requestHeaders: [], responseHeaders: [], headersLimited: false, statusCode: 200,
            state: .completed, failure: nil, durationMillis: 4, requestBody: .restore(state: .omitted, blobID: nil, observedBytes: 0),
            responseBody: .restore(state: .available, blobID: bodyID, observedBytes: 7))
        let history = BodyHistory(event: event, body: try HTTPBody.restore(id: bodyID, text: "content"))
        let detail = HTTPDetailViewController(event: event, history: history), window = show(detail)
        defer { window.isHidden = true; window.rootViewController = nil }
        try activate(detail.view, title: InspectorLocalization.text("ui_response"))
        XCTAssertEqual(history.reads, 0); XCTAssertEqual(history.snapshots, 0)
        XCTAssertEqual(detail.inspectorActions?.count, 1)
        detail.tableView(detail.tableView, didSelectRowAt: IndexPath(row: 1, section: 1))
        try await settled(XCTUnwrap(detail.navigationController))
        XCTAssertEqual(history.reads, 1)
        let reader = try XCTUnwrap(detail.navigationController?.topViewController as? InspectorContentViewController)
        XCTAssertTrue(try XCTUnwrap(find(reader.view, UITextView.self)).text.contains("content"))
    }
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
        let search = try XCTUnwrap(find(group.view, UISearchTextField.self))
        XCTAssertEqual(group.tableView(group.tableView, numberOfRowsInSection: 0), 1)
        search.text = "BODY_ONLY_TOKEN"; try changed(search, event: .editingChanged)
        XCTAssertEqual(group.tableView(group.tableView, numberOfRowsInSection: 0), 0)
        search.text = "frozen"; try changed(search, event: .editingChanged)
        XCTAssertEqual(group.tableView(group.tableView, numberOfRowsInSection: 0), 1)
        group.tableView(group.tableView, didSelectRowAt: IndexPath(row: 0, section: 0))
        try await settled(XCTUnwrap(group.navigationController))
        let detail = try XCTUnwrap(group.navigationController?.topViewController as? HTTPDetailViewController); detail.loadViewIfNeeded()
        XCTAssertEqual(detail.navigationItem.rightBarButtonItems?.count, 1)
        try activate(detail.view, title: InspectorLocalization.text("ui_response"))
        XCTAssertNil(find(detail.view, UITextView.self), "Selecting a section must not open or read a body")
        detail.tableView(detail.tableView, didSelectRowAt: IndexPath(row: 1, section: 1))
        try await settled(XCTUnwrap(detail.navigationController))
        let reader = try XCTUnwrap(detail.navigationController?.topViewController as? InspectorContentViewController)
        let text = try XCTUnwrap(find(reader.view, UITextView.self))
        XCTAssertEqual(text.text, "BODY_ONLY_TOKEN"); XCTAssertTrue(labels(reader.view).joined(separator: "\n").contains(HTTPPresentation.text("incident_frozen_detail")))
        detail.navigationController?.popViewController(animated: false)
        try activate(detail.view, title: InspectorLocalization.text("ui_overview"))
        let metadata = detail.sections.flatMap(\.rows).map { $0.label + ($0.value ?? "") }.joined(separator: "\n")
        XCTAssertTrue(metadata.contains(event.id)); XCTAssertFalse(metadata.contains("BODY_ONLY_TOKEN"))
    }
    @MainActor
    func testSavedSocketDetailKeepsMissingStatesAndAvailableEmptyTextDistinct() async throws {
        for state in [IncidentPayloadState.available, .notSelected, .unavailable, .busy, .budgetExceeded] {
            let event = try WebSocketEvent.restore(id: id, connectionID: id, sessionID: id, sequence: 9, timestampMillis: 123,
                url: "wss://example.com", source: .host, kind: .message, headers: [], headersLimited: false, statusCode: nil, closeCode: nil,
                failure: nil, direction: .incoming, outcome: .received, relatedMessageID: nil, payload: .restore(state: .available, blobID: id, byteCount: 0))
            let payload = try IncidentPayload.restore(id: id, state: state, text: state == .available ? "" : nil)
            let detail = WebSocketDetailViewController(event: event, savedPayloads: [id: payload]), window = show(detail)
            defer { window.isHidden = true; window.rootViewController = nil }
            XCTAssertEqual(detail.navigationItem.rightBarButtonItems?.count, 1)
            try activate(detail.view, title: InspectorLocalization.text("ws_payload"))
            XCTAssertNil(find(detail.view, UITextView.self))
            detail.tableView(detail.tableView, didSelectRowAt: IndexPath(row: 1, section: 0))
            try await settled(XCTUnwrap(detail.navigationController))
            let reader = try XCTUnwrap(detail.navigationController?.topViewController as? InspectorContentViewController)
            let text = try XCTUnwrap(find(reader.view, UITextView.self))
            XCTAssertEqual(text.text, "", "Status explanations must not become payload text")
            let notes = labels(reader.view).joined(separator: "\n")
            XCTAssertTrue(notes.contains(IncidentPayloadPresentation.content(payload)))
            XCTAssertEqual(notes.contains(HTTPPresentation.text("http_body_empty")), state == .available)
            XCTAssertTrue(notes.contains(HTTPPresentation.text("incident_frozen_detail")))
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
        let metadata = detail.sections.flatMap(\.rows).map { $0.label + ($0.value ?? "") }.joined(separator: "\n")
        XCTAssertTrue(metadata.contains(id)); XCTAssertTrue(metadata.contains("#11")); XCTAssertTrue(metadata.contains(RuntimePresentation.text("ui_private_content")))
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
    @MainActor private func activate(_ view: UIView, title: String) throws {
        func buttons(_ view: UIView) -> [UIButton] { (view as? UIButton).map { [$0] } ?? view.subviews.flatMap(buttons) }
        try changed(XCTUnwrap(buttons(view).first { $0.title(for: .normal) == title }), event: .touchUpInside)
    }
    @MainActor private func changed(_ control: UIControl, event: UIControl.Event = .valueChanged) throws {
        var sent = false
        for target in control.allTargets {
            guard let receiver = target.base as? NSObject else { continue }
            for action in control.actions(forTarget: receiver, forControlEvent: event) ?? [] {
                _ = receiver.perform(NSSelectorFromString(action), with: control); sent = true
            }
        }
        XCTAssertTrue(sent)
    }
}

private final class BodyHistory: HTTPHistory, @unchecked Sendable {
    private let event: HTTPEvent, bodyValue: HTTPBody
    private let lock = NSLock()
    private var count = 0, queries = 0
    var snapshots: Int { lock.lock(); defer { lock.unlock() }; return queries }
    init(event: HTTPEvent, body: HTTPBody) { self.event = event; bodyValue = body }
    var reads: Int { lock.lock(); defer { lock.unlock() }; return count }
    func snapshot() -> HTTPSnapshot? {
        lock.lock(); defer { lock.unlock() }; queries += 1
        return HTTPSnapshot(events: [event], retainedTextBytes: 0, retainedBodyBytes: 7, evictedEvents: 0, evictedBodies: 0, revision: 2)
    }
    func body(id: String) -> HTTPBodyLookup {
        lock.lock(); defer { lock.unlock() }; count += 1
        return id == bodyValue.id ? .available(bodyValue) : .unavailable
    }
    func clear() { XCTFail("Viewing content must not clear history") }
}
#endif
