import Foundation
import OsservioCore
@testable import OsservioStore
import XCTest

final class IncidentFocusTests: XCTestCase {
    func testFocusRequiresExactAttachedIdentityAndDoesNotChangeEvidence() throws {
        let snapshot = try IncidentDetailFixture.snapshot()
        let references: [IncidentFocus] = [.log(snapshot.logs!.events[0]), .http(snapshot.http!.events[1]), .webSocket(snapshot.webSockets!.events[2])]
        for focus in references {
            let selected = try snapshot.withFocus(focus)
            XCTAssertEqual(selected.id, snapshot.id); XCTAssertEqual(selected.textByteCount, snapshot.textByteCount)
            XCTAssertEqual(selected.logs?.events.first?.message, "[private]")
            XCTAssertThrowsError(try IncidentSnapshot.capture().withFocus(focus))
            let wrongSequence = try IncidentFocus.restore(kind: focus.kind, sessionID: focus.sessionID, sequence: 789, eventID: focus.eventID)
            XCTAssertThrowsError(try snapshot.withFocus(wrongSequence))
            let wrongSession = try IncidentFocus.restore(kind: focus.kind, sessionID: UUID().uuidString, sequence: focus.sequence, eventID: focus.eventID)
            XCTAssertThrowsError(try snapshot.withFocus(wrongSession))
        }
        XCTAssertThrowsError(try IncidentFocus.restore(kind: .log, sessionID: IncidentDetailFixture.id(1), sequence: 1, eventID: IncidentDetailFixture.id(2)))
        XCTAssertThrowsError(try IncidentFocus.restore(kind: .http, sessionID: IncidentDetailFixture.id(1), sequence: 1, eventID: nil))
    }
    func testMigrationRoundTripAndDeletionKeepOriginalRecords() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("incidents.sqlite"), options = try IncidentPersistenceOptions()
        let legacy = try IncidentDetailFixture.snapshot()
        let first = try IncidentDatabase(url: url, options: options, now: 0)
        try first.save(legacy, now: 0); first.close()
        let raw = try SQLiteConnection(url: url)
        try raw.execute("DROP TABLE incident_focus"); try raw.execute("PRAGMA user_version=11"); raw.close()
        let writer = try IncidentDatabase(url: url, options: options, now: 1)
        XCTAssertNil(try writer.read(id: legacy.id)?.focus)
        let base = try IncidentDetailFixture.snapshot()
        let selected = try base.withFocus(.http(base.http!.events[1]))
        try writer.save(selected, now: 1); writer.close()
        let reader = try IncidentDatabase(url: url, options: options, now: 2)
        let restored = try XCTUnwrap(reader.read(id: selected.id))
        XCTAssertEqual(restored.focus?.kind, .http); XCTAssertTrue(restored.focus!.matches(restored.http!.events[1]))
        XCTAssertEqual(restored.http?.payloads.values.filter { $0.state == .notSelected }.count, 1)
        XCTAssertEqual(try reader.read(id: legacy.id)?.textByteCount, legacy.textByteCount)
        try reader.delete(id: selected.id); reader.close()
        let check = try SQLiteConnection(url: url); defer { check.close() }
        try check.statement("SELECT count(*) FROM incident_focus") { row in XCTAssertTrue(try row.step()); XCTAssertEqual(try row.integer(0), 0) }
    }
    func testCorruptFocusIsRejectedWithoutReplacingAttachedRecords() throws {
        for update in ["kind='unknown'", "sequence=999", "event_id=NULL", "session_id='broken'"] {
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
            defer { try? FileManager.default.removeItem(at: directory) }
            let url = directory.appendingPathComponent("incidents.sqlite"), options = try IncidentPersistenceOptions()
            let base = try IncidentDetailFixture.snapshot(), selected = try base.withFocus(.http(base.http!.events[0]))
            let writer = try IncidentDatabase(url: url, options: options, now: 0); try writer.save(selected, now: 0); writer.close()
            let raw = try SQLiteConnection(url: url); try raw.execute("UPDATE incident_focus SET " + update); raw.close()
            let reader = try IncidentDatabase(url: url, options: options, now: 1); defer { reader.close() }
            XCTAssertThrowsError(try reader.read(id: selected.id), update)
            XCTAssertEqual(try reader.summaries().count, 1)
        }
    }
}
