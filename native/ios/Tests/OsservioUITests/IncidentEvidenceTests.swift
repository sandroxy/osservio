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
    func testLiveOverviewDoesNotReadBodiesAndSelectedContentDoesNotRefreshMetadata() async throws {
        let preferences = InspectorPreferences.shared, language = preferences.language
        defer { preferences.language = language }
        preferences.language = .chinese
        let bodyID = id + ".response"
        let event = try HTTPEvent.restore(id: id, sessionID: id, sequence: 1, revision: 2, timestampMillis: 123, method: "GET", source: .host,
            url: "https://example.com/fixture", responseURL: nil, requestHeaders: [], responseHeaders: [], headersLimited: false, statusCode: 200,
            state: .completed, failure: nil, durationMillis: 4, requestBody: .restore(state: .omitted, blobID: nil, observedBytes: 0),
            responseBody: .restore(state: .available, blobID: bodyID, observedBytes: 7))
        let history = BodyHistory(event: event, body: try HTTPBody.restore(id: bodyID, text: "content"))
        let detail = HTTPDetailViewController(event: event, history: history), window = show(detail)
        defer { window.isHidden = true; window.rootViewController = nil }
        XCTAssertEqual(history.reads, 0)
        XCTAssertNil(detail.tableView(detail.tableView, contextMenuConfigurationForRowAt: IndexPath(row: 0, section: 0), point: .zero),
            "A readable address opens an exact-source reader instead of copying its display projection")
        try activate(detail.view, title: InspectorLocalization.text("ui_response"))
        XCTAssertEqual(history.reads, 1); XCTAssertEqual(history.snapshots, 0)
        XCTAssertEqual(detail.inspectorActions?.count, 1)
        detail.tableView(detail.tableView, didSelectRowAt: IndexPath(row: 0, section: 0))
        try await settled(XCTUnwrap(detail.navigationController))
        XCTAssertEqual(history.reads, 2)
        let reader = try XCTUnwrap(detail.navigationController?.topViewController as? InspectorContentViewController)
        XCTAssertTrue(try XCTUnwrap(find(reader.view, UITextView.self)).text.contains("content"))
        reader.beginAppearanceTransition(false, animated: false); reader.endAppearanceTransition()
        preferences.language = .english
        reader.beginAppearanceTransition(true, animated: false); reader.endAppearanceTransition()
        XCTAssertEqual(history.reads, 2, "Relocalizing a retained reader must not consult live content again")
        XCTAssertTrue(labels(reader.view).joined(separator: "\n").contains(HTTPPresentation.bodyDescription(event.responseBody)))
        XCTAssertEqual(find(reader.view, UITextView.self)?.text, "content")
    }
    @MainActor
    func testCompleteHeaderValueUsesBoundedPreviewAndExactReader() async throws {
        let token = "Bearer " + String(repeating: "synthetic-token.", count: 90)
        let url = "https://example.com/api?token=synthetic-only&name=%E6%B6%88%E6%81%AF"
        let event = try HTTPEvent.restore(id: id, sessionID: id, sequence: 1, revision: 2, timestampMillis: 123, method: "POST", source: .host,
            url: url, responseURL: nil, requestHeaders: [HTTPHeader(name: "authorization", value: token)], responseHeaders: [],
            headersLimited: false, statusCode: 401, state: .completed, failure: nil, durationMillis: 4,
            requestBody: .restore(state: .omitted, blobID: nil, observedBytes: 0), responseBody: .restore(state: .omitted, blobID: nil, observedBytes: 0))
        let detail = HTTPDetailViewController(event: event, savedPayloads: [:]), window = show(detail)
        defer { window.isHidden = true; window.rootViewController = nil }
        XCTAssertTrue(HTTPPresentation.overview(event).contains(token)); XCTAssertTrue(HTTPPresentation.overview(event).contains(url))
        try activate(detail.view, title: InspectorLocalization.text("ui_request"))
        let header = try XCTUnwrap(detail.sections.last?.rows.first)
        XCTAssertEqual(header.label, "authorization"); XCTAssertLessThan(try XCTUnwrap(header.value).count, 200)
        detail.tableView(detail.tableView, didSelectRowAt: IndexPath(row: 0, section: 1))
        try await settled(XCTUnwrap(detail.navigationController))
        let reader = try XCTUnwrap(detail.navigationController?.topViewController as? InspectorContentViewController)
        XCTAssertEqual(find(reader.view, UITextView.self)?.text, token)
        XCTAssertTrue(labels(reader.view).contains("authorization"))
        let processed = try HTTPBodyReference.restore(state: .available, blobID: id + ".request", observedBytes: 2, isProcessed: true)
        XCTAssertTrue(HTTPPresentation.bodyDescription(processed).contains(InspectorLocalization.text("ui_content_processed")))
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
        try await waitForRows(group, count: 1)
        search.text = "frozen"; try changed(search, event: .editingChanged)
        try await waitForRows(group, count: 1)
        group.tableView(group.tableView, didSelectRowAt: IndexPath(row: 0, section: 0))
        try await settled(XCTUnwrap(group.navigationController))
        let detail = try XCTUnwrap(group.navigationController?.topViewController as? HTTPDetailViewController); detail.loadViewIfNeeded()
        XCTAssertEqual(detail.navigationItem.rightBarButtonItems?.count, 1)
        try activate(detail.view, title: InspectorLocalization.text("ui_response"))
        XCTAssertNil(find(detail.view, UITextView.self), "Selecting a section previews content without pushing a reader")
        detail.tableView(detail.tableView, didSelectRowAt: IndexPath(row: 0, section: 0))
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
            let notes = detail.sections.flatMap { $0.rows.map(\.label) + [$0.footer ?? ""] }.joined(separator: "\n")
            XCTAssertTrue(notes.contains(state == .available ? HTTPPresentation.text("http_body_empty") : IncidentPayloadPresentation.content(payload)))
            XCTAssertEqual(detail.sections[0].rows[0].action != nil, state == .available)
            if state == .available {
                detail.tableView(detail.tableView, didSelectRowAt: IndexPath(row: 0, section: 0))
                try await settled(XCTUnwrap(detail.navigationController))
                let reader = try XCTUnwrap(detail.navigationController?.topViewController as? InspectorContentViewController)
                XCTAssertEqual(try XCTUnwrap(find(reader.view, UITextView.self)).text, "", "Explanations must not become payload text")
                XCTAssertTrue(labels(reader.view).joined(separator: "\n").contains(HTTPPresentation.text("incident_frozen_detail")))
            }
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
    @MainActor private func waitForRows(_ controller: IncidentEvidenceViewController, count: Int) async throws {
        for _ in 0..<100 {
            if controller.tableView(controller.tableView, numberOfRowsInSection: 0) == count { return }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        XCTFail("Saved evidence search did not finish")
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
