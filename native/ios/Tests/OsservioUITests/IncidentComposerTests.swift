#if canImport(UIKit)
import Foundation
import OsservioCore
@testable import OsservioUI
import UIKit
import XCTest

final class IncidentComposerTests: XCTestCase {
    @MainActor
    func testEnvironmentDeliveryPreservesEditingAndOriginalMarkTime() async throws {
        let provider = ControlledEnvironment(), archive = RecordingArchive()
        let mark = try IncidentSnapshot.restore(id: UUID().uuidString, timestampMillis: 123, note: nil, environment: nil,
            breadcrumbs: IncidentBreadcrumbs.restore(events: [], sourceEventCount: 0, sourceEvictedCount: 0))
        let saved = expectation(description: "save completed")
        let composer = IncidentComposerViewController(mark: mark, archive: archive, provider: provider) { saved.fulfill() }
        let window = show(composer)
        defer { window.isHidden = true; window.rootViewController = nil }
        XCTAssertFalse(try XCTUnwrap(composer.navigationItem.rightBarButtonItem).isEnabled)
        let noteSwitch = try control(composer, row: 7)
        noteSwitch.isOn = true; try sendChange(noteSwitch)
        composer.tableView.layoutIfNeeded()
        let editorCell = try XCTUnwrap(composer.tableView.dataSource?.tableView(composer.tableView, cellForRowAt: IndexPath(row: 0, section: 2)))
        let editor = try XCTUnwrap(editorCell.contentView.subviews.compactMap { $0 as? UITextView }.first)
        editor.text = "Synthetic diagnostic note"
        let facts = Dictionary(uniqueKeysWithValues: EnvironmentKey.allCases.map { ($0, EnvironmentFact.unavailable) })
        provider.finish(.success(try EnvironmentSnapshot.capture(platform: .ios, publicFacts: facts, timestampMillis: 456)))
        try await waitUntil { composer.navigationItem.rightBarButtonItem?.isEnabled == true }
        window.layoutIfNeeded(); composer.tableView.layoutIfNeeded()
        XCTAssertEqual(editor.text, "Synthetic diagnostic note")
        XCTAssertTrue(noteSwitch.isOn)
        XCTAssertTrue(try control(composer, row: 0).isOn)
        let save = try XCTUnwrap(composer.navigationItem.rightBarButtonItem)
        try send(save)
        await fulfillment(of: [saved], timeout: 3)
        let snapshot = try XCTUnwrap(archive.snapshot)
        XCTAssertEqual(snapshot.id, mark.id); XCTAssertEqual(snapshot.timestampMillis, 123)
        XCTAssertEqual(snapshot.environment?.timestampMillis, 456)
        XCTAssertEqual(snapshot.note?.text, "Synthetic diagnostic note")
        XCTAssertEqual(snapshot.breadcrumbs?.events.count, 0)
    }

    @MainActor
    func testPendingEnvironmentCanBeExcludedAndDescriptionStaysAbsent() async throws {
        let archive = RecordingArchive(), provider = ControlledEnvironment()
        let mark = try IncidentSnapshot.capture()
        let saved = expectation(description: "saved without pending attachment")
        let composer = IncidentComposerViewController(mark: mark, archive: archive, provider: provider) { saved.fulfill() }
        let window = show(composer)
        defer { window.isHidden = true; window.rootViewController = nil }
        let environment = try control(composer, row: 0)
        environment.isOn = false; try sendChange(environment)
        let save = try XCTUnwrap(composer.navigationItem.rightBarButtonItem)
        XCTAssertTrue(save.isEnabled)
        try send(save)
        await fulfillment(of: [saved], timeout: 3)
        let snapshot = try XCTUnwrap(archive.snapshot)
        XCTAssertNil(snapshot.environment); XCTAssertNil(snapshot.note); XCTAssertNil(snapshot.breadcrumbs)
        XCTAssertEqual(snapshot.timestampMillis, mark.timestampMillis)
    }

    @MainActor
    func testBodySelectionAndFailedSaveReuseFrozenEvidence() async throws {
        let id = UUID().uuidString, socketID = UUID().uuidString, bodyID = id + ".response"
        let event = try HTTPEvent.restore(id: id, sessionID: id, sequence: 7, revision: 2, timestampMillis: 123, method: "GET", source: .host,
            url: "https://example.com/frozen", responseURL: nil, requestHeaders: [], responseHeaders: [], headersLimited: false, statusCode: 200,
            state: .completed, failure: nil, durationMillis: 4, requestBody: .restore(state: .omitted, blobID: nil, observedBytes: 0),
            responseBody: .restore(state: .available, blobID: bodyID, observedBytes: 3))
        let socket = try WebSocketEvent.restore(id: socketID, connectionID: id, sessionID: id, sequence: 8, timestampMillis: 124,
            url: "wss://example.com", source: .host, kind: .message, headers: [], headersLimited: false, statusCode: nil, closeCode: nil,
            failure: nil, direction: .incoming, outcome: .received, relatedMessageID: nil, payload: .restore(state: .available, blobID: socketID, byteCount: 3))
        let log = try LogEvent.restore(sessionID: id, sequence: 9, timestampMillis: 125, level: .error, category: "fixture", message: "[private]", isPrivate: true, isTruncated: false)
        let mark = try IncidentSnapshot.capture(logs: .restore(events: [log], observedAtMillis: 450, sourceEventCount: 1, sourceEvictedCount: 2),
            http: .restore(events: [event], payloads: [bodyID: .restore(id: bodyID, state: .notSelected, text: nil)],
                observedAtMillis: 456, sourceEventCount: 3, sourceEvictedEvents: 5, sourceEvictedBodies: 7),
            webSockets: .restore(events: [socket], payloads: [socketID: .restore(id: socketID, state: .notSelected, text: nil)],
                observedAtMillis: 457, sourceEventCount: 4, sourceEvictedEvents: 6, sourceEvictedPayloads: 8))
        let http = ComposerHTTP(body: try .restore(id: bodyID, text: "abc")), sockets = ComposerWebSockets()
        let archive = RecordingArchive(failFirstSave: true), saved = expectation(description: "retry saved frozen content")
        let composer = IncidentComposerViewController(mark: mark, archive: archive, provider: ControlledEnvironment(), httpHistory: http, webSocketHistory: sockets) { saved.fulfill() }
        let window = show(composer)
        defer { window.isHidden = true; window.rootViewController = nil }
        XCTAssertEqual(http.reads, 0); XCTAssertEqual(sockets.reads, 0)
        let environment = try control(composer, row: 0); environment.isOn = false; try sendChange(environment)
        let logs = try control(composer, row: 2); XCTAssertTrue(logs.isOn); logs.isOn = false; try sendChange(logs)
        let httpBodies = try control(composer, row: 4), socketBodies = try control(composer, row: 6)
        XCTAssertFalse(httpBodies.isOn); XCTAssertFalse(socketBodies.isOn)
        httpBodies.isOn = true; try sendChange(httpBodies); socketBodies.isOn = true; try sendChange(socketBodies)
        XCTAssertEqual(http.reads, 1); XCTAssertEqual(sockets.reads, 1)
        http.missing = true
        httpBodies.isOn = false; try sendChange(httpBodies); httpBodies.isOn = true; try sendChange(httpBodies)
        let httpGroup = try control(composer, row: 3); httpGroup.isOn = false; try sendChange(httpGroup)
        XCTAssertFalse(httpBodies.isEnabled)
        httpGroup.isOn = true; try sendChange(httpGroup); XCTAssertTrue(httpBodies.isEnabled)
        let save = try XCTUnwrap(composer.navigationItem.rightBarButtonItem); try send(save)
        try await waitUntil { archive.attempts.count == 1 && save.isEnabled }
        try send(save); await fulfillment(of: [saved], timeout: 3)
        let result = try XCTUnwrap(archive.snapshot)
        XCTAssertEqual(result.id, mark.id); XCTAssertEqual(result.timestampMillis, mark.timestampMillis); XCTAssertNil(result.logs)
        XCTAssertEqual(result.http?.events.map(\.id), [event.id]); XCTAssertEqual(result.http?.observedAtMillis, 456)
        XCTAssertEqual(result.http?.sourceEventCount, 3); XCTAssertEqual(result.http?.sourceEvictedBodies, 7)
        XCTAssertEqual(result.http?.payloads[bodyID]?.text, "abc"); XCTAssertEqual(result.webSockets?.payloads[socketID]?.state, .busy)
        XCTAssertEqual(result.webSockets?.observedAtMillis, 457); XCTAssertEqual(archive.attempts.first?.http?.payloads[bodyID]?.text, "abc")
        XCTAssertEqual(http.reads, 1); XCTAssertEqual(sockets.reads, 1)
    }

    @MainActor
    private func show(_ composer: UIViewController) -> UIWindow {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = UINavigationController(rootViewController: composer)
        window.makeKeyAndVisible(); composer.loadViewIfNeeded(); window.layoutIfNeeded()
        return window
    }
    @MainActor
    private func control(_ composer: IncidentComposerViewController, row: Int) throws -> UISwitch {
        let cell = try XCTUnwrap(composer.tableView.dataSource?.tableView(composer.tableView, cellForRowAt: IndexPath(row: row, section: 1)))
        return try XCTUnwrap(cell.accessoryView as? UISwitch)
    }
    // Package tests have no UIApplication event dispatcher. Invoke the actual registered
    // targets here; the consumer smoke check covers event routing in a running application.
    @MainActor
    private func sendChange(_ control: UIControl) throws {
        var sent = false
        for target in control.allTargets {
            guard let receiver = target.base as? NSObject else { continue }
            for action in control.actions(forTarget: receiver, forControlEvent: .valueChanged) ?? [] {
                _ = receiver.perform(NSSelectorFromString(action), with: control); sent = true
            }
        }
        XCTAssertTrue(sent)
    }
    @MainActor
    private func send(_ item: UIBarButtonItem) throws {
        let receiver = try XCTUnwrap(item.target as? NSObject), action = try XCTUnwrap(item.action)
        _ = receiver.perform(action)
    }
    @MainActor
    private func waitUntil(_ condition: @MainActor () -> Bool) async throws {
        for _ in 0..<100 {
            if condition() { return }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        XCTFail("UI did not finish the environment update")
    }
}

private final class ControlledEnvironment: EnvironmentProvider, @unchecked Sendable {
    private let lock = NSLock()
    private var callback: (@Sendable (Result<EnvironmentSnapshot, EnvironmentError>) -> Void)?
    func read(completion: @escaping @Sendable (Result<EnvironmentSnapshot, EnvironmentError>) -> Void) {
        lock.lock(); callback = completion; lock.unlock()
    }
    func finish(_ result: Result<EnvironmentSnapshot, EnvironmentError>) {
        lock.lock(); let completion = callback; callback = nil; lock.unlock(); completion?(result)
    }
}
private final class RecordingArchive: IncidentArchive, @unchecked Sendable {
    private let lock = NSLock()
    private var saved: IncidentSnapshot?
    private var attempted: [IncidentSnapshot] = []
    private let failFirstSave: Bool
    init(failFirstSave: Bool = false) { self.failFirstSave = failFirstSave }
    var attempts: [IncidentSnapshot] { lock.lock(); defer { lock.unlock() }; return attempted }
    var snapshot: IncidentSnapshot? { lock.lock(); defer { lock.unlock() }; return saved }
    func persistenceStatus() -> PersistenceStatus? { nil }
    func save(_ snapshot: IncidentSnapshot, completion: @escaping @Sendable (Result<Void, PersistenceError>) -> Void) {
        lock.lock(); attempted.append(snapshot)
        let fail = failFirstSave && attempted.count == 1
        if !fail { saved = snapshot }; lock.unlock()
        completion(fail ? .failure(.busy) : .success(()))
    }
    func readIncidents(completion: @escaping @Sendable (Result<[IncidentSummary], PersistenceError>) -> Void) { completion(.success([])) }
    func readIncident(id: String, completion: @escaping @Sendable (Result<IncidentSnapshot?, PersistenceError>) -> Void) { completion(.success(snapshot)) }
    func deleteIncident(id: String, completion: @escaping @Sendable (Result<Void, PersistenceError>) -> Void) { completion(.failure(.unavailable)) }
    func deleteAllIncidents(completion: @escaping @Sendable (Result<Void, PersistenceError>) -> Void) { completion(.failure(.unavailable)) }
}
private final class ComposerHTTP: HTTPHistory, @unchecked Sendable {
    let body: HTTPBody
    var reads = 0
    var missing = false
    init(body: HTTPBody) { self.body = body }
    func snapshot() -> HTTPSnapshot? { XCTFail("Composer must not refresh metadata"); return nil }
    func body(id: String) -> HTTPBodyLookup { reads += 1; XCTAssertEqual(id, body.id); return missing ? .unavailable : .available(body) }
    func clear() { XCTFail("Composer must not clear live history") }
}
private final class ComposerWebSockets: WebSocketHistory, @unchecked Sendable {
    var reads = 0
    func snapshot() -> WebSocketSnapshot? { XCTFail("Composer must not refresh metadata"); return nil }
    func payload(id: String) -> WebSocketPayloadLookup { reads += 1; return .busy }
    func clear() { XCTFail("Composer must not clear live history") }
}
#endif
