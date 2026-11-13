import Foundation
import OsservioCore
@testable import OsservioStore
import XCTest

final class StructuredLogDatabaseTests: XCTestCase {
    private func fixture(_ body: (URL) throws -> Void) throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        try body(directory.appendingPathComponent("incidents.sqlite"))
    }
    func testDetailsSurviveReopenAndInconsistentReferenceIsRejected() throws {
        try fixture { url in
            let history = try MemoryLogStore(), logger = try LogRecorder(sink: history); try logger.start()
            logger.record(.error, category: "fixture", message: .publicText("Request failed"),
                details: .publicValues(error: LogError(type: "Timeout", domain: "transport", stackTrace: "Origin.run\nCaller.run")))
            let logs = try IncidentLogs.capture(XCTUnwrap(history.snapshot()), observedAtMillis: 3, history: history, includeDetails: true)
            let snapshot = try IncidentSnapshot.capture(logs: logs)
            let writer = try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 0)
            try writer.save(snapshot, now: 0); writer.close(); history.clear()
            let reader = try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 1)
            let saved = try XCTUnwrap(reader.read(id: snapshot.id))
            XCTAssertEqual(saved.logs?.events.first?.details?.blobID, logs.events.first?.details?.blobID)
            XCTAssertEqual(saved.logs?.payloads.values.first?.text, logs.payloads.values.first?.text)
            XCTAssertEqual(saved.logs?.retainedPayloadBytes, logs.retainedPayloadBytes)
            XCTAssertEqual(saved.textByteCount, snapshot.textByteCount); reader.close()
            let raw = try SQLiteConnection(url: url); try raw.execute("UPDATE incident_logs SET details_bytes=details_bytes+1"); raw.close()
            let corrupt = try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 2); defer { corrupt.close() }
            XCTAssertThrowsError(try corrupt.read(id: snapshot.id))
        }
    }
    func testRevisionSevenMigrationPreservesHistoryAndRollsBackOnConflict() throws {
        for conflict in [false, true] {
            try fixture { url in
                let raw = try SQLiteConnection(url: url)
                for revision in 1...7 {
                    for sql in try IncidentDetailFixture.shared("incident-v\(revision).sql").split(separator: ";") where !sql.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { try raw.execute(String(sql)) }
                }
                if conflict { try raw.execute("ALTER TABLE incident_logs ADD COLUMN details_blob TEXT") }; raw.close()
                if conflict { XCTAssertThrowsError(try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 2000)) }
                else {
                    let store = try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 2000)
                    XCTAssertEqual(try store.summaries().count, 4)
                    for summary in try store.summaries() { XCTAssertNotNil(try store.read(id: summary.id)) }
                    let old = try XCTUnwrap(store.read(id: "12345678-1234-1234-1234-123456789abc"))
                    XCTAssertEqual(old.logs?.events.first?.message, "[private]"); XCTAssertNil(old.logs?.events.first?.details)
                    XCTAssertEqual(old.logs?.sourceEvictedDetails, 0); store.close()
                }
                let check = try SQLiteConnection(url: url); defer { check.close() }
                try check.statement("PRAGMA user_version") { XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), conflict ? 7 : 8) }
                var column = false
                try check.statement("PRAGMA table_info(incident_logs)") { row in while try row.step() { if try row.text(1, byteLimit: 64) == "details_state" { column = true } } }
                XCTAssertEqual(column, !conflict)
            }
        }
    }
}
