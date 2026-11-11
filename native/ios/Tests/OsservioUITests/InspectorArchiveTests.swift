#if canImport(UIKit)
import OsservioCore
import OsservioStore
@testable import OsservioUI
import UIKit
import XCTest

final class InspectorArchiveTests: XCTestCase {
    @MainActor
    func testPrivacyDescriptionsResolveInBothLanguages() {
        let previous = InspectorPreferences.shared.language
        defer { InspectorPreferences.shared.language = previous }
        InspectorPreferences.shared.language = .chinese
        XCTAssertEqual(RuntimePresentation.message("[private]", isPrivate: true), "采集时已省略正文")
        XCTAssertEqual(RuntimePresentation.text("incident_private_note"), "采集时已省略说明")
        InspectorPreferences.shared.language = .english
        XCTAssertEqual(RuntimePresentation.message("[private]", isPrivate: true), "Content omitted at capture")
        XCTAssertEqual(RuntimePresentation.text("incident_private_note"), "Description omitted at capture")
    }

    @MainActor
    func testReadingAnIncidentDoesNotMoveExistingRows() async throws {
        let archive = ArchiveReadProbe()
        let viewer = IncidentInspectorViewController(archive: archive, environment: archive, breadcrumbs: try MemoryBreadcrumbStore())
        let window = show(viewer)
        defer { window.isHidden = true; window.rootViewController = nil }
        try await settle()
        let table = try XCTUnwrap(viewer.tableView)
        XCTAssertEqual(table.numberOfRows(inSection: 0), 30)
        table.scrollToRow(at: IndexPath(row: 10, section: 0), at: .top, animated: false); table.layoutIfNeeded()
        let before = table.contentOffset.y
        let rowBefore = table.rectForRow(at: IndexPath(row: 10, section: 0))
        viewer.tableView(table, didSelectRowAt: IndexPath(row: 10, section: 0))
        table.layoutIfNeeded()
        XCTAssertNotNil(archive.incidentRead)
        XCTAssertEqual(table.contentOffset.y, before, accuracy: 1)
        XCTAssertEqual(table.rectForRow(at: IndexPath(row: 10, section: 0)), rowBefore)
        XCTAssertEqual(table.numberOfRows(inSection: 0), 30)
        XCTAssertEqual(archive.mutations, 0)
    }

    @MainActor
    func testReadingARunKeepsItsRowAndReportsActivityInPlace() async throws {
        let archive = ArchiveReadProbe()
        let subject = BreadcrumbArchiveViewController(archive: archive)
        let window = show(subject)
        defer { window.isHidden = true; window.rootViewController = nil }
        try await settle()
        let table = try XCTUnwrap(subject.tableView), index = IndexPath(row: 10, section: 1)
        XCTAssertEqual(table.numberOfRows(inSection: 1), 30)
        table.scrollToRow(at: index, at: .top, animated: false); table.layoutIfNeeded()
        let before = table.contentOffset.y, position = table.rectForRow(at: index)
        subject.tableView(table, didSelectRowAt: index); table.layoutIfNeeded()
        XCTAssertNotNil(archive.runRead)
        XCTAssertEqual(table.contentOffset.y, before, accuracy: 1)
        XCTAssertEqual(table.rectForRow(at: index), position)
        XCTAssertTrue((table.cellForRow(at: index)?.accessoryView as? UIActivityIndicatorView)?.isAnimating == true)
        XCTAssertEqual(archive.mutations, 0)
    }

    @MainActor
    func testPrivatePresentationKeepsTypeAndAvailableEvidenceWithoutGuessingFromText() throws {
        let privateNote = try IncidentNote.capture(.privateText("not retained"))
        let summary = IncidentSummary(id: UUID().uuidString, timestampMillis: 1_000, note: privateNote,
            hasEnvironment: true, breadcrumbCount: 3, logCount: 0, httpCount: 2)
        XCTAssertEqual(privateNote.text, "[private]")
        XCTAssertEqual(IncidentPresentation.listTitle(summary), RuntimePresentation.text("incident_manual"))
        let subtitle = IncidentPresentation.listSubtitle(summary)
        XCTAssertTrue(subtitle.contains(RuntimePresentation.text("incident_private_note")))
        XCTAssertTrue(subtitle.contains("HTTP: 2")); XCTAssertFalse(subtitle.contains(": 0"))
        XCTAssertFalse(subtitle.contains("[private]")); XCTAssertFalse(subtitle.contains("not retained"))
        XCTAssertEqual(IncidentPresentation.note(privateNote), RuntimePresentation.text("incident_private_note"))
        let literal = IncidentSummary(id: UUID().uuidString, timestampMillis: 1_000,
            note: try IncidentNote.capture(.publicText("[private]")), hasEnvironment: false, breadcrumbCount: nil)
        XCTAssertEqual(IncidentPresentation.listTitle(literal), "[private]", "Privacy comes from the typed flag, not a text heuristic")
        XCTAssertTrue(IncidentPresentation.listSubtitle(literal).contains(RuntimePresentation.text("incident_no_attachments")))
        XCTAssertEqual(RuntimePresentation.message("[private]", isPrivate: false), "[private]")
        XCTAssertEqual(RuntimePresentation.message("[private]", isPrivate: true), RuntimePresentation.text("ui_private_content"))
    }

    @MainActor private func show(_ viewer: UIViewController) -> UIWindow {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = viewer; window.makeKeyAndVisible(); window.layoutIfNeeded()
        viewer.beginAppearanceTransition(true, animated: false); viewer.endAppearanceTransition()
        return window
    }
    @MainActor private func settle() async throws { try await Task.sleep(nanoseconds: 100_000_000) }
}

/// Callbacks are driven on the main actor by these controller tests; no persistent store is opened.
private final class ArchiveReadProbe: IncidentArchive, BreadcrumbArchive, EnvironmentProvider, @unchecked Sendable {
    var incidentRead: (@Sendable (Result<IncidentSnapshot?, PersistenceError>) -> Void)?
    var runRead: (@Sendable (Result<RetainedBreadcrumbs?, PersistenceError>) -> Void)?
    var mutations = 0
    private let ids = (0..<30).map { _ in UUID().uuidString }
    func persistenceStatus() -> PersistenceStatus? { nil }
    func readIncidents(completion: @escaping @Sendable (Result<[IncidentSummary], PersistenceError>) -> Void) {
        completion(.success(ids.enumerated().map { index, id in
            IncidentSummary(id: id, timestampMillis: Int64(1_000 + index), note: nil, hasEnvironment: false, breadcrumbCount: 3)
        }))
    }
    func readRuns(completion: @escaping @Sendable (Result<[RetainedRun], PersistenceError>) -> Void) {
        completion(.success(ids.enumerated().map { index, id in
            RetainedRun(id: id, platform: .ios, processID: 1, recordingStartedAtMillis: Int64(1_000 + index),
                lastRecordedAtMillis: Int64(2_000 + index), eventCount: 3, evictedEvents: 0, isCurrent: false)
        }))
    }
    func readIncident(id: String, completion: @escaping @Sendable (Result<IncidentSnapshot?, PersistenceError>) -> Void) { incidentRead = completion }
    func readRun(id: String, completion: @escaping @Sendable (Result<RetainedBreadcrumbs?, PersistenceError>) -> Void) { runRead = completion }
    func read(completion: @escaping @Sendable (Result<EnvironmentSnapshot, EnvironmentError>) -> Void) { XCTFail("Browsing must not capture environment") }
    func save(_ snapshot: IncidentSnapshot, completion: @escaping @Sendable (Result<Void, PersistenceError>) -> Void) { mutations += 1; XCTFail("Browsing must not save") }
    func deleteIncident(id: String, completion: @escaping @Sendable (Result<Void, PersistenceError>) -> Void) { mutations += 1; XCTFail("Browsing must not delete") }
    func deleteAllIncidents(completion: @escaping @Sendable (Result<Void, PersistenceError>) -> Void) { mutations += 1; XCTFail("Browsing must not delete") }
    func flush(completion: @escaping @Sendable (Result<Void, PersistenceError>) -> Void) { mutations += 1; XCTFail("Browsing must not flush") }
    func deleteRun(id: String, completion: @escaping @Sendable (Result<Void, PersistenceError>) -> Void) { mutations += 1; XCTFail("Browsing must not delete") }
    func deleteAllRuns(completion: @escaping @Sendable (Result<Void, PersistenceError>) -> Void) { mutations += 1; XCTFail("Browsing must not delete") }
}
#endif
