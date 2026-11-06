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
        let noteSwitch = try control(composer, row: 2)
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
    var snapshot: IncidentSnapshot? { lock.lock(); defer { lock.unlock() }; return saved }
    func persistenceStatus() -> PersistenceStatus? { nil }
    func save(_ snapshot: IncidentSnapshot, completion: @escaping @Sendable (Result<Void, PersistenceError>) -> Void) {
        lock.lock(); saved = snapshot; lock.unlock(); completion(.success(()))
    }
    func readIncidents(completion: @escaping @Sendable (Result<[IncidentSummary], PersistenceError>) -> Void) { completion(.success([])) }
    func readIncident(id: String, completion: @escaping @Sendable (Result<IncidentSnapshot?, PersistenceError>) -> Void) { completion(.success(snapshot)) }
    func deleteIncident(id: String, completion: @escaping @Sendable (Result<Void, PersistenceError>) -> Void) { completion(.failure(.unavailable)) }
    func deleteAllIncidents(completion: @escaping @Sendable (Result<Void, PersistenceError>) -> Void) { completion(.failure(.unavailable)) }
}
#endif
