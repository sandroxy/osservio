import Foundation
import OsservioCore
@testable import OsservioStore
import XCTest

enum TombstoneFixture {
    static func evidence(state: SystemDiagnostic.TombstoneState = .available) throws -> SystemDiagnostic {
        try .androidExit(timestampMillis: 1000, reasonCode: state == .notApplicable ? 4 : 5, status: 6,
            processID: 123, importance: 100, pssKiB: nil, rssKiB: nil, tombstoneState: state,
            tombstone: state == .available ? .restore(architectureCode: 1, signalNumber: 6, signalCode: -6, threadID: 99, faultAddress: .max,
                frames: [.restore(programCounter: .max, relativeProgramCounter: .max, buildID: "0123456789abcdef0123456789abcdef01234567"),
                         .restore(programCounter: 0, relativeProgramCounter: 0, buildID: nil)]) : nil)
    }
    static func batch(_ values: [SystemDiagnostic]) throws -> SystemDiagnosticBatch {
        try .init(source: .androidExitInfo, observedAtMillis: 2000, diagnostics: values)
    }
}

final class TombstoneDatabaseTests: XCTestCase {
    private func fixture(_ body: (URL) throws -> Void) throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("osservio-tombstone-store-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: directory) }
        try body(directory.appendingPathComponent("incidents.sqlite"))
    }
    func testAllReadStatesRoundTripWithExactOriginalEvidence() throws {
        try fixture { url in
            let snapshots = try SystemDiagnostic.TombstoneState.allCases.map { IncidentSnapshot.captureDiagnostic(try TombstoneFixture.evidence(state: $0), observedAtMillis: 2000) }
            let writer = try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 2000)
            for snapshot in snapshots { try writer.save(snapshot, now: 2000) }; writer.close()
            let reader = try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 2001); defer { reader.close() }
            for original in snapshots {
                let saved = try XCTUnwrap(reader.read(id: original.id)), diagnostic = try XCTUnwrap(saved.systemDiagnostic)
                XCTAssertEqual(saved.timestampMillis, 2000); XCTAssertEqual(diagnostic.intervalStartMillis, 1000)
                XCTAssertEqual(diagnostic.androidExit?.tombstoneState, original.systemDiagnostic?.androidExit?.tombstoneState)
                XCTAssertEqual(saved.textByteCount, original.textByteCount)
                XCTAssertEqual(DiagnosticFingerprint.make(diagnostic), DiagnosticFingerprint.make(try XCTUnwrap(original.systemDiagnostic)))
                if let trace = diagnostic.androidExit?.tombstone {
                    XCTAssertEqual(trace.faultAddress, .max); XCTAssertEqual(trace.signalCode, -6); XCTAssertEqual(trace.threadID, 99)
                    XCTAssertEqual(trace.frames.map(\.programCounter), [.max, 0]); XCTAssertEqual(trace.frames[0].relativeProgramCounter, .max)
                    XCTAssertNil(trace.frames[1].buildID); XCTAssertNil(saved.environment); XCTAssertNil(saved.breadcrumbs)
                }
            }
        }
    }
    func testSharedCorruptionIsRejectedWithoutRepair() throws {
        for line in try IncidentDetailFixture.shared("tombstone-corruption.tsv").split(separator: "\n") {
            let row = line.split(separator: "\t").map(String.init)
            try fixture { url in
                let snapshot = IncidentSnapshot.captureDiagnostic(try TombstoneFixture.evidence(), observedAtMillis: 2000)
                let writer = try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 2000)
                try writer.save(snapshot, now: 2000); writer.close()
                let raw = try SQLiteConnection(url: url); try raw.execute(row[1]); raw.close()
                let before = try Data(contentsOf: url)
                let reader = try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 2001)
                XCTAssertThrowsError(try reader.read(id: snapshot.id), row[0]) { XCTAssertEqual($0 as? PersistenceError, .invalidRecord, row[0]) }
                reader.close(); XCTAssertEqual(try Data(contentsOf: url), before, row[0])
            }
        }
    }
    func testRecoveryEnrichmentAndEvictionDoNotRewriteOrDegradeSavedEvidence() throws {
        try fixture { url in
            let summary = try TombstoneFixture.evidence(state: .unavailable), rich = try TombstoneFixture.evidence()
            let writer = try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 2000)
            XCTAssertEqual(try writer.recover(TombstoneFixture.batch([summary]), now: 2000).savedCount, 1)
            let originalID = try XCTUnwrap(writer.summaries().first?.id)
            XCTAssertEqual(try writer.recover(TombstoneFixture.batch([rich]), now: 2001).savedCount, 1)
            XCTAssertEqual(try writer.read(id: originalID)?.systemDiagnostic?.androidExit?.tombstoneState, .unavailable)
            XCTAssertEqual(try writer.summaries().count, 2); writer.close()
            let reader = try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 2002); defer { reader.close() }
            XCTAssertEqual(try reader.recover(TombstoneFixture.batch([rich]), now: 2002).alreadyProcessedCount, 1)
            XCTAssertEqual(try reader.recover(TombstoneFixture.batch([summary]), now: 2003).alreadyProcessedCount, 1)
            try reader.deleteAll()
            XCTAssertEqual(try reader.recover(TombstoneFixture.batch([rich]), now: 2004).alreadyProcessedCount, 1)
            XCTAssertEqual(try reader.recover(TombstoneFixture.batch([summary]), now: 2005).alreadyProcessedCount, 1)
            XCTAssertTrue(try reader.summaries().isEmpty)
        }
        try fixture { url in
            let rich = try TombstoneFixture.evidence(), summary = try TombstoneFixture.evidence(state: .tooLarge)
            let store = try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 2000); defer { store.close() }
            XCTAssertEqual(try store.recover(TombstoneFixture.batch([rich]), now: 2000).savedCount, 1)
            XCTAssertEqual(try store.recover(TombstoneFixture.batch([summary]), now: 2001).alreadyProcessedCount, 1)
            XCTAssertEqual(try store.summaries().count, 1)
            // Two summary occurrences after one richer occurrence still retain the additional occurrence.
            let receipt = try store.recover(TombstoneFixture.batch([summary, summary]), now: 2002)
            XCTAssertEqual(receipt.savedCount, 1); XCTAssertEqual(receipt.alreadyProcessedCount, 1)
        }
    }
    func testMixedBatchAliasesKeepMultiplicityWithinBounds() throws {
        try fixture { url in
            let store = try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 2000); defer { store.close() }
            let rich = try TombstoneFixture.evidence(), summary = try TombstoneFixture.evidence(state: .invalid)
            let mixed = try TombstoneFixture.batch([rich, summary])
            XCTAssertEqual(try store.recover(mixed, now: 2000).savedCount, 2)
            XCTAssertEqual(try store.recover(mixed, now: 2001).alreadyProcessedCount, 2)
            XCTAssertEqual(try store.recover(TombstoneFixture.batch([summary, summary]), now: 2002).alreadyProcessedCount, 2)
        }
    }
    func testFrameWriteFailureRollsBackRetentionAcknowledgementsAndDeletionCascades() throws {
        try fixture { url in
            let options = try IncidentPersistenceOptions(retainedDiagnostics: 1)
            let summary = try TombstoneFixture.evidence(state: .unavailable), rich = try TombstoneFixture.evidence()
            let first = try IncidentDatabase(url: url, options: options, now: 2000)
            XCTAssertEqual(try first.recover(TombstoneFixture.batch([summary]), now: 2000).savedCount, 1)
            let originalID = try XCTUnwrap(first.summaries().first?.id); first.close()
            let raw = try SQLiteConnection(url: url)
            try raw.execute("CREATE TRIGGER reject_frame BEFORE INSERT ON android_tombstone_frames WHEN NEW.position=1 BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
            raw.close()
            let failed = try IncidentDatabase(url: url, options: options, now: 2001)
            XCTAssertThrowsError(try failed.recover(TombstoneFixture.batch([rich]), now: 2001))
            XCTAssertEqual(try failed.summaries().map(\.id), [originalID]); failed.close()
            let repair = try SQLiteConnection(url: url); try repair.execute("DROP TRIGGER reject_frame"); repair.close()
            let retry = try IncidentDatabase(url: url, options: options, now: 2002)
            XCTAssertEqual(try retry.recover(TombstoneFixture.batch([rich]), now: 2002).savedCount, 1)
            XCTAssertNil(try retry.read(id: originalID)); try retry.deleteAll(); retry.close()
            let check = try SQLiteConnection(url: url); defer { check.close() }
            for table in ["android_tombstones", "android_tombstone_frames", "incident_diagnostics"] {
                try check.statement("SELECT count(*) FROM \(table)") { XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), 0) }
            }
        }
    }
    private func legacy(_ url: URL) throws {
        let raw = try SQLiteConnection(url: url); defer { raw.close() }
        for name in ["incident-v1.sql", "incident-v2.sql", "incident-v3.sql", "incident-v4.sql", "incident-v5.sql"] {
            for sql in try IncidentDetailFixture.shared(name).split(separator: ";") where !sql.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                try raw.execute(String(sql))
            }
        }
    }
    func testRevisionFiveMigrationPreservesPriorSourcesAndRollsBackOnConflict() throws {
        try fixture { url in
            try legacy(url)
            let store = try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 2000)
            XCTAssertEqual(try store.summaries().count, 4)
            for summary in try store.summaries() { XCTAssertNotNil(try store.read(id: summary.id)) }
            let old = try XCTUnwrap(store.read(id: "eeeeeeee-eeee-eeee-eeee-000000000003")?.systemDiagnostic)
            XCTAssertEqual(old.androidExit?.tombstoneState, .notRequested)
            let metric = try XCTUnwrap(store.read(id: "eeeeeeee-eeee-eeee-eeee-000000000001")?.systemDiagnostic)
            XCTAssertEqual(try store.recover(.init(source: .metricKit, observedAtMillis: 2000, diagnostics: [metric]), now: 2000).alreadyProcessedCount, 1)
            try store.save(.captureDiagnostic(try TombstoneFixture.evidence(), observedAtMillis: 2000), now: 2000); store.close()
            let raw = try SQLiteConnection(url: url); defer { raw.close() }
            try raw.statement("PRAGMA user_version") { XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), 6) }
            try raw.statement("SELECT saved_at FROM incidents WHERE id='eeeeeeee-eeee-eeee-eeee-000000000003'") {
                XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), 100)
            }
        }
        try fixture { url in
            try legacy(url)
            let raw = try SQLiteConnection(url: url); try raw.execute("CREATE TABLE android_tombstone_frames(sentinel TEXT)"); raw.close()
            XCTAssertThrowsError(try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 2000))
            let check = try SQLiteConnection(url: url); defer { check.close() }
            try check.statement("PRAGMA user_version") { XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), 5) }
            try check.statement("SELECT count(*) FROM sqlite_master WHERE name='android_tombstones'") {
                XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), 0)
            }
            try check.statement("SELECT * FROM incident_diagnostics") { row in
                while try row.step() { /* All legacy rows remain readable after rollback. */ }
            }
        }
    }
}
