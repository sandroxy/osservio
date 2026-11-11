import Foundation
import OsservioCore
@testable import OsservioStore
import XCTest

final class DiagnosticRecoveryDatabaseTests: XCTestCase {
    private let now: Int64 = 1_000_000
    private func fixture(_ body: (URL) throws -> Void) throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("osservio-recovery-test-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: directory) }
        try body(directory.appendingPathComponent("incidents.sqlite"))
    }
    private func exit(_ time: Int64, reason: Int32 = 4) throws -> SystemDiagnostic {
        try .androidExit(timestampMillis: time, reasonCode: reason, status: 0, processID: 123, importance: 100, pssKiB: nil, rssKiB: nil)
    }
    private func batch(_ records: [SystemDiagnostic], source: SystemDiagnosticSource = .androidExitInfo) throws -> SystemDiagnosticBatch {
        try .init(source: source, observedAtMillis: 42, diagnostics: records)
    }
    func testRepeatedMaterialPreservesBatchMultiplicityAcrossDeletionAndReopen() throws {
        try fixture { url in
            let options = try IncidentPersistenceOptions(), diagnostic = try exit(now)
            let writer = try IncidentDatabase(url: url, options: options, now: now)
            let first = try writer.recover(batch([diagnostic, diagnostic]), now: now)
            XCTAssertEqual(first.savedCount, 2); XCTAssertEqual(first.alreadyProcessedCount, 0)
            let summaries = try writer.summaries()
            XCTAssertEqual(Set(summaries.map(\.id)).count, 2)
            for summary in summaries {
                let saved = try XCTUnwrap(writer.read(id: summary.id))
                XCTAssertEqual(saved.timestampMillis, 42); XCTAssertEqual(saved.systemDiagnostic?.intervalEndMillis, now)
                XCTAssertNil(saved.environment); XCTAssertNil(saved.breadcrumbs); XCTAssertNil(saved.logs)
            }
            try writer.deleteAll(); writer.close()
            let reader = try IncidentDatabase(url: url, options: options, now: now + 1); defer { reader.close() }
            let smaller = try reader.recover(batch([diagnostic]), now: now + 1)
            XCTAssertEqual(smaller.alreadyProcessedCount, 1); XCTAssertEqual(smaller.savedCount, 0); XCTAssertTrue(try reader.summaries().isEmpty)
            let increased = try reader.recover(batch([diagnostic, diagnostic, diagnostic]), now: now + 2)
            XCTAssertEqual(increased.alreadyProcessedCount, 2); XCTAssertEqual(increased.savedCount, 1)
            XCTAssertEqual(try reader.summaries().count, 1)
        }
    }
    func testFilteringAndBatchCapacityKeepNewestEligibleEvidenceAndManualMarks() throws {
        try fixture { url in
            let options = try IncidentPersistenceOptions(maximumAgeMillis: 60_000, retainedDiagnostics: 2)
            let store = try IncidentDatabase(url: url, options: options, now: now); defer { store.close() }
            let manual = try IncidentSnapshot.capture(note: .publicText("keep manual")); try store.save(manual, now: now)
            let records = try [exit(now - 60_001), exit(now + 1), exit(now, reason: 1), exit(now - 60_000), exit(now - 2), exit(now - 1), exit(now)]
            let result = try store.recover(batch(records), now: now)
            XCTAssertEqual(result.savedCount, 2); XCTAssertEqual(result.ignoredExitCount, 1)
            XCTAssertEqual(result.outsideWindowCount, 2); XCTAssertEqual(result.capacityExcludedCount, 2)
            let times = try store.summaries().filter { $0.diagnosticKind != nil }.map { try store.read(id: $0.id)?.systemDiagnostic?.intervalEndMillis }
            XCTAssertEqual(times, [now, now - 1]); XCTAssertNotNil(try store.read(id: manual.id))
            let repeated = try store.recover(batch(records), now: now)
            XCTAssertEqual(repeated.alreadyProcessedCount, 4); XCTAssertEqual(repeated.savedCount, 0); XCTAssertEqual(repeated.capacityExcludedCount, 0)
        }
    }
    func testTextCapacityAndExtremeClocksRemainBounded() throws {
        try fixture { url in
            let store = try IncidentDatabase(url: url, options: IncidentPersistenceOptions(textBytesPerIncident: 64), now: now)
            let large = try SystemDiagnostic.metricKit(intervalStartMillis: now, intervalEndMillis: now, kind: .crash,
                metadata: [.appVersion: .capture(publicValue: String(repeating: "x", count: 65))])
            let small = try SystemDiagnostic.metricKit(intervalStartMillis: now, intervalEndMillis: now, kind: .hang, metadata: [:], hangDurationMillis: 0)
            let result = try store.recover(batch([large, small], source: .metricKit), now: now)
            XCTAssertEqual(result.savedCount, 1); XCTAssertEqual(result.capacityExcludedCount, 1); store.close()
        }
        for time in [Int64.min, Int64.max] {
            try fixture { url in
                let store = try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: time); defer { store.close() }
                let diagnostic = try SystemDiagnostic.metricKit(intervalStartMillis: time, intervalEndMillis: time, kind: .hang, metadata: [:], hangDurationMillis: 0)
                XCTAssertEqual(try store.recover(batch([diagnostic], source: .metricKit), now: time).savedCount, 1)
            }
        }
    }
    func testAcknowledgementFailureRollsBackNewReportsAndRetention() throws {
        try fixture { url in
            let options = try IncidentPersistenceOptions(retainedDiagnostics: 1)
            let original = IncidentSnapshot.captureDiagnostic(try exit(now - 1), observedAtMillis: 12)
            let writer = try IncidentDatabase(url: url, options: options, now: now)
            try writer.save(original, now: now); writer.close()
            let raw = try SQLiteConnection(url: url)
            try raw.execute("CREATE TRIGGER reject_ack BEFORE INSERT ON diagnostic_acknowledgements BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
            raw.close()
            let store = try IncidentDatabase(url: url, options: options, now: now)
            XCTAssertThrowsError(try store.recover(batch([exit(now)]), now: now))
            XCTAssertEqual(try store.summaries().map(\.id), [original.id]); store.close()
            let check = try SQLiteConnection(url: url); defer { check.close() }
            try check.statement("SELECT count(*) FROM diagnostic_acknowledgements") { XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), 0) }
        }
    }
    func testAcknowledgementsStayBoundedAndExpiredCorruptionIsNotRepaired() throws {
        try fixture { url in
            let options = try IncidentPersistenceOptions(maximumAgeMillis: 60_000)
            let writer = try IncidentDatabase(url: url, options: options, now: 1); writer.close()
            let raw = try SQLiteConnection(url: url)
            for index in 1...256 {
                try raw.execute("INSERT INTO diagnostic_acknowledgements(fingerprint,occurrences,processed_at) VALUES (?,1,1)",
                    [.text(String(format: "%064x", index))])
            }
            raw.close()
            let store = try IncidentDatabase(url: url, options: options, now: 2)
            XCTAssertEqual(try store.recover(batch([exit(2)]), now: 2).savedCount, 1); store.close()
            let check = try SQLiteConnection(url: url)
            try check.statement("SELECT count(*),min(ordinal) FROM diagnostic_acknowledgements") {
                XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), 256); XCTAssertEqual(try $0.integer(1), 2)
            }
            try check.execute("UPDATE diagnostic_acknowledgements SET occurrences=33 WHERE ordinal=257"); check.close()
            let before = try Data(contentsOf: url)
            XCTAssertThrowsError(try IncidentDatabase(url: url, options: options, now: now)) { XCTAssertEqual($0 as? PersistenceError, .invalidRecord) }
            XCTAssertEqual(try Data(contentsOf: url), before)
        }
    }
    private func legacy(_ url: URL) throws {
        let raw = try SQLiteConnection(url: url); defer { raw.close() }
        for name in ["incident-v1.sql", "incident-v2.sql", "incident-v3.sql"] {
            for statement in try IncidentDetailFixture.shared(name).split(separator: ";") where !statement.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                try raw.execute(String(statement))
            }
        }
    }
    func testSharedAcknowledgementCorruptionIsRejectedEvenAfterExpiry() throws {
        for line in try IncidentDetailFixture.shared("diagnostic-acknowledgement-corruption.tsv").split(separator: "\n") {
            let fields = line.split(separator: "\t").map(String.init)
            try fixture { url in
                let options = try IncidentPersistenceOptions(maximumAgeMillis: 60_000)
                let writer = try IncidentDatabase(url: url, options: options, now: 1)
                _ = try writer.recover(batch([exit(1)]), now: 1); writer.close()
                let raw = try SQLiteConnection(url: url); try raw.execute(fields[1]); raw.close()
                let before = try Data(contentsOf: url)
                XCTAssertThrowsError(try IncidentDatabase(url: url, options: options, now: now), fields[0]) {
                    XCTAssertEqual($0 as? PersistenceError, .invalidRecord, fields[0])
                }
                XCTAssertEqual(try Data(contentsOf: url), before, fields[0])
            }
        }
    }
    func testRevisionThreeMigrationPreservesBothKindsAndRollsBackOnConflict() throws {
        let id = "eeeeeeee-eeee-eeee-eeee-000000000001"
        try fixture { url in
            try legacy(url)
            let store = try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 200)
            XCTAssertEqual(try store.summaries().count, 2)
            let existing = try XCTUnwrap(store.read(id: id))
            XCTAssertEqual(existing.timestampMillis, 123); XCTAssertEqual(existing.systemDiagnostic?.metricKit?.exceptionCode, "18446744073709551615")
            XCTAssertEqual(try store.recover(batch([exit(150)]), now: 200).savedCount, 1); store.close()
            let raw = try SQLiteConnection(url: url); defer { raw.close() }
            try raw.statement("PRAGMA user_version") { XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), 7) }
            try raw.statement("SELECT saved_at FROM incidents WHERE id=?", [.text(id)]) { XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), 100) }
        }
        try fixture { url in
            try legacy(url)
            let raw = try SQLiteConnection(url: url); try raw.execute("CREATE TABLE diagnostic_acknowledgements(sentinel TEXT)"); raw.close()
            XCTAssertThrowsError(try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 200))
            let check = try SQLiteConnection(url: url); defer { check.close() }
            try check.statement("PRAGMA user_version") { XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), 3) }
            try check.statement("SELECT count(*) FROM incidents") { XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), 2) }
        }
    }
}
