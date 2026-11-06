import Foundation
import OsservioCore
@testable import OsservioStore
import XCTest

final class IncidentDiagnosticDatabaseTests: XCTestCase {
    private let legacyID = "12345678-1234-1234-1234-123456789abc"
    private func fixture(_ body: (URL) throws -> Void) throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("osservio-diagnostic-test-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: directory) }
        try body(directory.appendingPathComponent("incidents.sqlite"))
    }
    private func evidence(_ family: String = "metric") throws -> SystemDiagnostic {
        if family == "android" {
            return try .androidExit(timestampMillis: 100, reasonCode: 4, status: -1, processID: 123, importance: 100, pssKiB: .max, rssKiB: nil)
        }
        return try .metricKit(intervalStartMillis: -100, intervalEndMillis: 200, kind: .crash,
            metadata: [.appVersion: .capture(publicValue: String(repeating: "😀", count: 65)), .appBuild: .capture(publicValue: "build\u{0}例")],
            signal: 11, exceptionType: .max, exceptionCode: "18446744073709551615")
    }
    func testRoundTripKeepsRecoveryTimeSeparateAndPreservesExactFacts() throws {
        try fixture { url in
            let options = try IncidentPersistenceOptions()
            let metric = IncidentSnapshot.captureDiagnostic(try evidence(), observedAtMillis: 42)
            let android = IncidentSnapshot.captureDiagnostic(try evidence("android"), observedAtMillis: 43)
            let hang = IncidentSnapshot.captureDiagnostic(try .metricKit(intervalStartMillis: 0, intervalEndMillis: .max,
                kind: .hang, metadata: [:], hangDurationMillis: .max), observedAtMillis: .min)
            let writer = try IncidentDatabase(url: url, options: options, now: 0)
            for item in [metric, android, hang] { try writer.save(item, now: 1) }
            writer.close()
            let reader = try IncidentDatabase(url: url, options: options, now: 2); defer { reader.close() }
            let saved = try XCTUnwrap(reader.read(id: metric.id))
            XCTAssertEqual(saved.timestampMillis, 42); XCTAssertEqual(saved.systemDiagnostic?.intervalStartMillis, -100)
            XCTAssertEqual(saved.systemDiagnostic?.intervalEndMillis, 200)
            XCTAssertEqual(saved.systemDiagnostic?.metricKit?.exceptionCode, "18446744073709551615")
            XCTAssertEqual(saved.systemDiagnostic?.metricKit?.exceptionType, .max)
            XCTAssertEqual(saved.systemDiagnostic?.metricKit?.metadata[.appVersion]?.isTruncated, true)
            XCTAssertEqual(saved.systemDiagnostic?.metricKit?.metadata[.appBuild]?.value, "build\u{0}例")
            XCTAssertEqual(saved.textByteCount, metric.textByteCount)
            XCTAssertNil(saved.environment); XCTAssertNil(saved.breadcrumbs); XCTAssertNil(saved.logs)
            XCTAssertEqual(try reader.read(id: android.id)?.systemDiagnostic?.androidExit?.pssKiB, .max)
            XCTAssertNil(try reader.read(id: android.id)?.systemDiagnostic?.androidExit?.rssKiB)
            XCTAssertEqual(try reader.read(id: hang.id)?.systemDiagnostic?.metricKit?.hangDurationMillis, .max)
            XCTAssertEqual(try reader.summaries().map(\.diagnosticKind), [.hang, .managedCrash, .crash])
        }
    }
    func testSeparateCountLimitsAgeAndCascadingDeletion() throws {
        try fixture { url in
            let options = try IncidentPersistenceOptions(retainedIncidents: 1, maximumAgeMillis: 60_000, retainedDiagnostics: 2)
            let store = try IncidentDatabase(url: url, options: options, now: 0)
            let manual = try IncidentSnapshot.capture(note: .publicText("keep manual"))
            try store.save(manual, now: 1)
            let records = try (0..<3).map { IncidentSnapshot.captureDiagnostic(try evidence(), observedAtMillis: Int64($0)) }
            for (index, record) in records.enumerated() { try store.save(record, now: Int64(index + 2)) }
            XCTAssertNotNil(try store.read(id: manual.id)); XCTAssertNil(try store.read(id: records[0].id))
            XCTAssertEqual(try store.summaries().count, 3)
            try store.save(IncidentSnapshot.capture(), now: 5)
            XCTAssertNil(try store.read(id: manual.id)); XCTAssertNotNil(try store.read(id: records[1].id))
            try store.delete(id: records[1].id); store.close()
            let expired = try IncidentDatabase(url: url, options: options, now: 60_006)
            XCTAssertTrue(try expired.summaries().isEmpty); expired.close()
            let raw = try SQLiteConnection(url: url); defer { raw.close() }
            for table in ["incident_diagnostics", "diagnostic_metadata"] {
                try raw.statement("SELECT count(*) FROM \(table)") { XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), 0) }
            }
        }
        XCTAssertThrowsError(try IncidentPersistenceOptions(retainedDiagnostics: 0))
        XCTAssertThrowsError(try IncidentPersistenceOptions(retainedDiagnostics: 17))
    }
    func testMetadataWriteFailureRollsBackRetentionAndPartialEvidence() throws {
        try fixture { url in
            let options = try IncidentPersistenceOptions(retainedDiagnostics: 1)
            let original = IncidentSnapshot.captureDiagnostic(try evidence(), observedAtMillis: 1)
            let failed = IncidentSnapshot.captureDiagnostic(try evidence(), observedAtMillis: 2)
            let first = try IncidentDatabase(url: url, options: options, now: 0)
            try first.save(original, now: 1); first.close()
            let raw = try SQLiteConnection(url: url)
            for table in ["incident_diagnostics", "diagnostic_metadata"] { try raw.execute("ALTER TABLE \(table) ADD COLUMN optional_future TEXT") }
            try raw.execute("CREATE TRIGGER reject_metadata BEFORE INSERT ON diagnostic_metadata WHEN NEW.key='appVersion' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
            raw.close()
            let store = try IncidentDatabase(url: url, options: options, now: 2); defer { store.close() }
            XCTAssertThrowsError(try store.save(failed, now: 3)) { XCTAssertEqual($0 as? PersistenceError, .invalidRecord) }
            XCTAssertEqual(try store.summaries().map(\.id), [original.id])
            XCTAssertEqual(try store.read(id: original.id)?.systemDiagnostic?.metricKit?.exceptionCode, "18446744073709551615")
            XCTAssertNil(try store.read(id: failed.id))
        }
    }
    func testSharedCorruptionIsRejectedWithoutRepair() throws {
        for line in try IncidentDetailFixture.shared("incident-diagnostic-corruption.tsv").split(separator: "\n") {
            let row = line.split(separator: "\t").map(String.init)
            try fixture { url in
                let snapshot = IncidentSnapshot.captureDiagnostic(try evidence(row[1]), observedAtMillis: 500)
                let options = try IncidentPersistenceOptions()
                let writer = try IncidentDatabase(url: url, options: options, now: 0)
                try writer.save(snapshot, now: 1); writer.close()
                let raw = try SQLiteConnection(url: url); try raw.execute(row[2]); raw.close()
                let before = try Data(contentsOf: url)
                let reader = try IncidentDatabase(url: url, options: options, now: 2)
                XCTAssertThrowsError(try reader.read(id: snapshot.id), row[0]) { XCTAssertEqual($0 as? PersistenceError, .invalidRecord, row[0]) }
                reader.close(); XCTAssertEqual(try Data(contentsOf: url), before, row[0])
            }
        }
    }
    private func legacy(_ url: URL) throws {
        let raw = try SQLiteConnection(url: url); defer { raw.close() }
        for name in ["incident-v1.sql", "incident-v2.sql"] {
            for sql in try IncidentDetailFixture.shared(name).split(separator: ";") where !sql.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                try raw.execute(String(sql))
            }
        }
    }
    func testIndependentRevisionTwoMigrationPreservesAttachmentsAndSaveTime() throws {
        try fixture { url in
            try legacy(url)
            let store = try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 101)
            let original = try XCTUnwrap(store.read(id: legacyID))
            XCTAssertEqual(original.note?.text, "legacy"); XCTAssertEqual(original.textByteCount, 59)
            XCTAssertEqual(original.logs?.events.first?.sequence, 9_007_199_254_740_993)
            XCTAssertEqual(original.logs?.events.first?.message, "[private]"); XCTAssertNil(original.systemDiagnostic)
            let added = IncidentSnapshot.captureDiagnostic(try evidence(), observedAtMillis: 102)
            try store.save(added, now: 102); XCTAssertNotNil(try store.read(id: added.id)?.systemDiagnostic); store.close()
            let raw = try SQLiteConnection(url: url); defer { raw.close() }
            try raw.statement("PRAGMA user_version") { XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), 4) }
            try raw.statement("SELECT saved_at FROM incidents WHERE id=?", [.text(legacyID)]) { XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), 100) }
        }
    }
    func testFailedRevisionTwoMigrationRollsBackColumnAndTables() throws {
        try fixture { url in
            try legacy(url)
            let conflict = try SQLiteConnection(url: url); try conflict.execute("CREATE TABLE diagnostic_metadata(unrelated TEXT)"); conflict.close()
            XCTAssertThrowsError(try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 101))
            let raw = try SQLiteConnection(url: url); defer { raw.close() }
            try raw.statement("PRAGMA user_version") { XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), 2) }
            try raw.statement("SELECT count(*) FROM sqlite_master WHERE name='incident_diagnostics'") { XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), 0) }
            XCTAssertThrowsError(try raw.statement("SELECT diagnostic_kind FROM incidents") { _ in () })
            try raw.statement("SELECT message FROM incident_logs") { XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.text(0, byteLimit: 32), "[private]") }
        }
    }
    func testAsynchronousCommitAndClosePreserveDiagnosticAndAdmissionBudget() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("osservio-diagnostic-async-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: directory) }
        let snapshot = IncidentSnapshot.captureDiagnostic(try evidence(), observedAtMillis: 42)
        let store = try SQLiteIncidentStore(directory: directory)
        let saved = await withCheckedContinuation { continuation in store.save(snapshot) { continuation.resume(returning: $0) } }
        try saved.get()
        let closed = await withCheckedContinuation { continuation in store.close { continuation.resume(returning: $0) } }
        try closed.get()
        let reopened = try SQLiteIncidentStore(directory: directory, options: IncidentPersistenceOptions(textBytesPerIncident: 64))
        let read = await withCheckedContinuation { continuation in reopened.readIncident(id: snapshot.id) { continuation.resume(returning: $0) } }
        XCTAssertEqual(try read.get()?.systemDiagnostic?.metricKit?.exceptionCode, "18446744073709551615")
        let rejected = await withCheckedContinuation { continuation in reopened.save(snapshot) { continuation.resume(returning: $0) } }
        XCTAssertThrowsError(try rejected.get()) { XCTAssertEqual($0 as? PersistenceError, .invalidRecord) }
        let finished = await withCheckedContinuation { continuation in reopened.close { continuation.resume(returning: $0) } }
        try finished.get()
    }
}
