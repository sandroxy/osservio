import Foundation
import OsservioCore
@testable import OsservioStore
import XCTest

enum SignalCrashFixture {
    static func evidence() throws -> SystemDiagnostic {
        .appleSignal(try .restore(reportID: "12345678-1234-1234-1234-abcdef123456", timestampMillis: 1_700_000_000_000,
            signal: .abort, code: "#0", faultAddress: .max, threadNumber: .max, frames: [0, .max],
            images: [.restore(baseAddress: 4096, size: 4096, uuid: "12345678123412341234abcdef123456", cpuType: 16_777_228, cpuSubtype: .max),
                     .restore(baseAddress: .max, size: 0, uuid: nil, cpuType: nil, cpuSubtype: nil)],
            metadata: [.appVersion: .capture(publicValue: "😀"), .appBuild: .capture(publicValue: "42")], imageCacheIncomplete: true))
    }
}

final class SignalCrashDatabaseTests: XCTestCase {
    private func fixture(_ body: (URL) throws -> Void) throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("osservio-signal-store-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: directory) }
        try body(directory.appendingPathComponent("incidents.sqlite"))
    }
    func testRoundTripExactEvidenceAndMissingStackWithoutCurrentLaunchContext() throws {
        try fixture { url in
            let options = try IncidentPersistenceOptions(), diagnostic = try SignalCrashFixture.evidence()
            let snapshot = IncidentSnapshot.captureDiagnostic(diagnostic, observedAtMillis: 1_700_000_001_234)
            let absent = IncidentSnapshot.captureDiagnostic(.appleSignal(try .restore(
                reportID: "00000000-0000-0000-0000-000000000001", timestampMillis: 1000, signal: .abort, code: "#0",
                faultAddress: 0, threadNumber: nil, frames: [], images: [], metadata: [:], imageCacheIncomplete: false)), observedAtMillis: 42)
            let writer = try IncidentDatabase(url: url, options: options, now: 1)
            try writer.save(snapshot, now: 1); try writer.save(absent, now: 1); writer.close()
            let reader = try IncidentDatabase(url: url, options: options, now: 2); defer { reader.close() }
            let saved = try XCTUnwrap(reader.read(id: snapshot.id)), evidence = try XCTUnwrap(saved.systemDiagnostic?.appleSignal)
            XCTAssertEqual(saved.timestampMillis, 1_700_000_001_234); XCTAssertEqual(evidence.timestampMillis, 1_700_000_000_000)
            XCTAssertEqual(saved.systemDiagnostic?.kind, .nativeCrash); XCTAssertEqual(saved.systemDiagnostic?.source, .appleSignal)
            XCTAssertEqual(evidence.faultAddress, .max); XCTAssertEqual(evidence.threadNumber, .max); XCTAssertEqual(evidence.frames, [0, .max])
            XCTAssertEqual(evidence.images.count, 2); XCTAssertEqual(evidence.images[0].cpuSubtype, .max)
            XCTAssertEqual(evidence.images[1].baseAddress, .max); XCTAssertEqual(evidence.images[1].size, 0); XCTAssertNil(evidence.images[1].uuid)
            XCTAssertTrue(evidence.imageCacheIncomplete); XCTAssertEqual(evidence.metadata[.appVersion]?.value, "😀")
            XCTAssertEqual(saved.textByteCount, 83); XCTAssertNil(saved.environment); XCTAssertNil(saved.breadcrumbs); XCTAssertNil(saved.logs)
            XCTAssertEqual(DiagnosticFingerprint.make(try XCTUnwrap(saved.systemDiagnostic)), DiagnosticFingerprint.make(diagnostic))
            let missing = try XCTUnwrap(reader.read(id: absent.id)?.systemDiagnostic?.appleSignal)
            XCTAssertNil(missing.threadNumber); XCTAssertTrue(missing.frames.isEmpty); XCTAssertTrue(missing.images.isEmpty)
        }
    }
    func testSharedCorruptionIsRejectedWithoutRepair() throws {
        for line in try IncidentDetailFixture.shared("signal-crash-corruption.tsv").split(separator: "\n") {
            let fields = line.split(separator: "\t").map(String.init)
            try fixture { url in
                let options = try IncidentPersistenceOptions(), snapshot = IncidentSnapshot.captureDiagnostic(try SignalCrashFixture.evidence(), observedAtMillis: 42)
                let writer = try IncidentDatabase(url: url, options: options, now: 1)
                try writer.save(snapshot, now: 1); writer.close()
                let raw = try SQLiteConnection(url: url); try raw.execute(fields[1]); raw.close()
                let before = try Data(contentsOf: url)
                let reader = try IncidentDatabase(url: url, options: options, now: 2)
                XCTAssertThrowsError(try reader.read(id: snapshot.id), fields[0]) { XCTAssertEqual($0 as? PersistenceError, .invalidRecord, fields[0]) }
                reader.close(); XCTAssertEqual(try Data(contentsOf: url), before, fields[0])
            }
        }
    }
    func testChildWriteFailureRollsBackRetentionThenDeletionCascades() throws {
        try fixture { url in
            let options = try IncidentPersistenceOptions(retainedDiagnostics: 1)
            let original = IncidentSnapshot.captureDiagnostic(try SignalCrashFixture.evidence(), observedAtMillis: 42)
            let candidate = IncidentSnapshot.captureDiagnostic(try SignalCrashFixture.evidence(), observedAtMillis: 43)
            let first = try IncidentDatabase(url: url, options: options, now: 1); try first.save(original, now: 1); first.close()
            let raw = try SQLiteConnection(url: url)
            try raw.execute("CREATE TRIGGER reject_image BEFORE INSERT ON signal_crash_images WHEN NEW.position=1 BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
            raw.close()
            let store = try IncidentDatabase(url: url, options: options, now: 2)
            XCTAssertThrowsError(try store.save(candidate, now: 2))
            XCTAssertEqual(try store.summaries().map(\.id), [original.id]); XCTAssertNotNil(try store.read(id: original.id)); XCTAssertNil(try store.read(id: candidate.id))
            try store.delete(id: original.id); store.close()
            let check = try SQLiteConnection(url: url); defer { check.close() }
            for table in ["signal_crashes", "signal_crash_frames", "signal_crash_images", "diagnostic_metadata", "incident_diagnostics"] {
                try check.statement("SELECT count(*) FROM \(table)") { XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), 0) }
            }
        }
    }
    private func legacy(_ url: URL) throws {
        let raw = try SQLiteConnection(url: url); defer { raw.close() }
        for name in ["incident-v1.sql", "incident-v2.sql", "incident-v3.sql", "incident-v4.sql"] {
            for sql in try IncidentDetailFixture.shared(name).split(separator: ";") where !sql.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                try raw.execute(String(sql))
            }
        }
    }
    func testRevisionFourMigrationPreservesAcknowledgementsAndRollbackOnConflict() throws {
        try fixture { url in
            try legacy(url)
            let store = try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 2000)
            XCTAssertEqual(try store.summaries().count, 2)
            let old = try XCTUnwrap(store.read(id: "eeeeeeee-eeee-eeee-eeee-000000000001")?.systemDiagnostic)
            XCTAssertEqual(try store.recover(.init(source: .metricKit, observedAtMillis: 2000, diagnostics: [old]), now: 2000).alreadyProcessedCount, 1)
            try store.save(.captureDiagnostic(try SignalCrashFixture.evidence(), observedAtMillis: 42), now: 2000); store.close()
            let raw = try SQLiteConnection(url: url); defer { raw.close() }
            try raw.statement("PRAGMA user_version") { XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), 9) }
            try raw.statement("SELECT saved_at FROM incidents WHERE id='eeeeeeee-eeee-eeee-eeee-000000000001'") {
                XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), 100)
            }
        }
        try fixture { url in
            try legacy(url)
            let raw = try SQLiteConnection(url: url); try raw.execute("CREATE TABLE signal_crash_images(sentinel TEXT)"); raw.close()
            XCTAssertThrowsError(try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 2000))
            let check = try SQLiteConnection(url: url); defer { check.close() }
            try check.statement("PRAGMA user_version") { XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), 4) }
            try check.statement("SELECT count(*) FROM sqlite_master WHERE name IN ('signal_crashes','signal_crash_frames')") {
                XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), 0)
            }
            try check.statement("SELECT count(*) FROM diagnostic_acknowledgements") { XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), 1) }
        }
    }
    func testRecoverySurvivesReopenAndAcknowledgesDeletedEvidence() throws {
        try fixture { url in
            let time: Int64 = 1_700_000_001_000, options = try IncidentPersistenceOptions()
            let batch = try SystemDiagnosticBatch(source: .appleSignal, observedAtMillis: time, diagnostics: [SignalCrashFixture.evidence()])
            let writer = try IncidentDatabase(url: url, options: options, now: time)
            XCTAssertEqual(try writer.recover(batch, now: time).savedCount, 1); writer.close()
            let reader = try IncidentDatabase(url: url, options: options, now: time + 1); defer { reader.close() }
            XCTAssertEqual(try reader.recover(batch, now: time + 1).alreadyProcessedCount, 1)
            XCTAssertEqual(try reader.summaries().count, 1); try reader.deleteAll()
            XCTAssertEqual(try reader.recover(batch, now: time + 2).alreadyProcessedCount, 1); XCTAssertTrue(try reader.summaries().isEmpty)
        }
    }
}
