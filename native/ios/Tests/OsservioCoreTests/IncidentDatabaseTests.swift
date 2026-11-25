import Foundation
import OsservioCore
@testable import OsservioStore
import XCTest

final class IncidentDatabaseTests: XCTestCase {
    private func fixture(_ body: (URL) throws -> Void) throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("osservio-incident-db-test-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: directory) }
        try body(directory.appendingPathComponent("incidents.sqlite"))
    }
    private func context(count: Int = 2, message: String = "public\u{0}例") throws -> IncidentSnapshot {
        var facts = Dictionary(uniqueKeysWithValues: EnvironmentKey.allCases.map { ($0, EnvironmentFact.unavailable) })
        facts[.osVersion] = .text(String(repeating: "x", count: 300))
        facts[.physicalMemoryBytes] = .number(Int64.max)
        facts[.lowPowerMode] = .flag(false)
        facts[.processProportionalBytes] = .unsupported
        facts[.androidTargetSDK] = .number(36)
        facts[.androidMinSDK] = .number(23)
        facts[.androidDebuggable] = .flag(false)
        let environment = try EnvironmentSnapshot.capture(platform: .ios, publicFacts: facts, timestampMillis: -123)
        let session = UUID().uuidString
        var events: [BreadcrumbEvent] = []
        for index in 0..<count {
            let retention: BreadcrumbRetention = index % 2 == 0 ? .memoryOnly : .restartRelevant
            let event = try BreadcrumbEvent.restore(id: UUID().uuidString, sessionID: session, sequence: Int64(index + 1),
                timestampMillis: Int64(index - 100), sessionElapsedMillis: UInt64.max, kind: .hostMarker,
                label: "fixture", message: message, isPrivate: false, isTruncated: false, value: Int64.min,
                retention: retention)
            events.append(event)
        }
        return try IncidentSnapshot.capture(note: .privateText("not written"), environment: environment,
            breadcrumbs: BreadcrumbSnapshot(events: events, retainedTextBytes: events.reduce(0) { $0 + $1.textByteCount }, evictedCount: 7, revision: 1),
            breadcrumbLimit: 2_000, breadcrumbTextByteLimit: 1_048_576)
    }
    func testRoundTripPreservesSanitizedContextAndIndependentObservationTimes() throws {
        try fixture { url in
            let options = try IncidentPersistenceOptions()
            let snapshot = try context()
            let missing = try IncidentSnapshot.capture()
            let empty = try IncidentSnapshot.capture(breadcrumbs: BreadcrumbSnapshot(events: [], retainedTextBytes: 0, evictedCount: 0, revision: 1))
            let writer = try IncidentDatabase(url: url, options: options, now: 1_000)
            for value in [snapshot, missing, empty] { try writer.save(value, now: 1_001) }
            writer.close()
            let reader = try IncidentDatabase(url: url, options: options, now: 1_002)
            defer { reader.close() }
            let restored = try XCTUnwrap(reader.read(id: snapshot.id.lowercased()))
            XCTAssertEqual(restored.id, snapshot.id); XCTAssertEqual(restored.timestampMillis, snapshot.timestampMillis)
            XCTAssertEqual(restored.note?.text, "[private]")
            XCTAssertEqual(restored.environment?.timestampMillis, -123)
            XCTAssertEqual(restored.environment?.values[.osVersion]?.isTruncated, true)
            guard case .number(36) = restored.environment?.values[.androidTargetSDK]?.fact,
                  case .number(23) = restored.environment?.values[.androidMinSDK]?.fact,
                  case .flag(false) = restored.environment?.values[.androidDebuggable]?.fact else { return XCTFail("Build facts changed") }
            guard case .number(Int64.max) = restored.environment?.values[.physicalMemoryBytes]?.fact else { return XCTFail("Integer precision lost") }
            XCTAssertEqual(restored.breadcrumbs?.events.map(\.id), snapshot.breadcrumbs?.events.map(\.id))
            XCTAssertEqual(restored.breadcrumbs?.events.map(\.message), ["public\u{0}例", "public\u{0}例"])
            XCTAssertEqual(restored.breadcrumbs?.events.map(\.sessionElapsedMillis), [UInt64.max, UInt64.max])
            XCTAssertEqual(restored.breadcrumbs?.events.map(\.retention), [.memoryOnly, .restartRelevant])
            XCTAssertEqual(restored.breadcrumbs?.sourceEvictedCount, 7)
            XCTAssertNil(try reader.read(id: missing.id)?.breadcrumbs)
            XCTAssertEqual(try reader.read(id: empty.id)?.breadcrumbs?.events.count, 0)
            XCTAssertEqual(try reader.summaries().map(\.id), [empty.id, missing.id, snapshot.id])
        }
    }
    func testCountAgeAndDeletionRetainWholeSnapshotsAndRejectDuplicateIdentity() throws {
        try fixture { url in
            let options = try IncidentPersistenceOptions(retainedIncidents: 2, maximumAgeMillis: 60_000)
            let store = try IncidentDatabase(url: url, options: options, now: 0)
            let snapshots = try (0..<3).map { _ in try context() }
            for (index, value) in snapshots.enumerated() { try store.save(value, now: Int64(index)) }
            XCTAssertNil(try store.read(id: snapshots[0].id))
            XCTAssertThrowsError(try store.save(snapshots[1], now: 100)) { XCTAssertEqual($0 as? PersistenceError, .invalidRecord) }
            XCTAssertEqual(try store.summaries().map(\.id), snapshots.suffix(2).reversed().map(\.id))
            try store.delete(id: snapshots[1].id.lowercased())
            XCTAssertNil(try store.read(id: snapshots[1].id))
            store.close()
            let expired = try IncidentDatabase(url: url, options: options, now: 60_003)
            XCTAssertTrue(try expired.summaries().isEmpty)
            try expired.save(snapshots[0], now: 60_003)
            try expired.deleteAll(); expired.close()
            let raw = try SQLiteConnection(url: url)
            defer { raw.close() }
            for table in ["environment_facts", "incident_breadcrumbs"] {
                try raw.statement("SELECT count(*) FROM \(table)") { row in XCTAssertTrue(try row.step()); XCTAssertEqual(try row.integer(0), 0) }
            }
        }
    }
    func testOptionalColumnsRemainCompatibleWithReadsAndNewWrites() throws {
        try fixture { url in
            let snapshot = try context(), options = try IncidentPersistenceOptions()
            let initial = try IncidentDatabase(url: url, options: options, now: 0)
            try initial.save(snapshot, now: 0); initial.close()
            let raw = try SQLiteConnection(url: url)
            for table in ["incidents", "environment_facts", "incident_breadcrumbs"] { try raw.execute("ALTER TABLE \(table) ADD COLUMN optional_future TEXT") }
            raw.close()
            let store = try IncidentDatabase(url: url, options: options, now: 1)
            defer { store.close() }
            XCTAssertEqual(try store.read(id: snapshot.id)?.textByteCount, snapshot.textByteCount)
            try store.save(context(), now: 1)
            XCTAssertEqual(try store.summaries().count, 2)
        }
    }
    func testInvalidStoredEvidenceIsRejectedWithoutRepair() throws {
        for sql in [
            "UPDATE incidents SET note='raw', private=1",
            "UPDATE incidents SET breadcrumb_count=3",
            "UPDATE incidents SET text_bytes=text_bytes+1",
            "UPDATE environment_facts SET truncated=1 WHERE key='physicalMemoryBytes'",
            "UPDATE environment_facts SET text_value=CAST(x'ff' AS TEXT) WHERE key='osVersion'",
            "DELETE FROM environment_facts WHERE key='lowPowerMode'",
            "DELETE FROM environment_facts WHERE key='androidTargetSDK'",
            "UPDATE environment_facts SET number_value=0 WHERE key='androidMinSDK'",
            "UPDATE incident_breadcrumbs SET elapsed='01'",
            "UPDATE incident_breadcrumbs SET private=1"
        ] {
            try fixture { url in
                let snapshot = try context(), options = try IncidentPersistenceOptions()
                let initial = try IncidentDatabase(url: url, options: options, now: 0)
                try initial.save(snapshot, now: 0); initial.close()
                let raw = try SQLiteConnection(url: url); try raw.execute(sql); raw.close()
                let store = try IncidentDatabase(url: url, options: options, now: 1)
                defer { store.close() }
                XCTAssertThrowsError(try store.read(id: snapshot.id), sql) { XCTAssertEqual($0 as? PersistenceError, .invalidRecord, sql) }
            }
        }
    }
    func testUnsupportedAndCorruptFilesRemainUntouched() throws {
        for revision in [0, 6] {
            try fixture { url in
                let options = try IncidentPersistenceOptions()
                if revision == 6 { let store = try IncidentDatabase(url: url, options: options, now: 0); store.close() }
                let raw = try SQLiteConnection(url: url)
                if revision == 0 { try raw.execute("CREATE TABLE other_data(value TEXT)") }
                else { try raw.execute("PRAGMA user_version=13") }
                raw.close()
                let before = try Data(contentsOf: url)
                XCTAssertThrowsError(try IncidentDatabase(url: url, options: options, now: 1)) { XCTAssertEqual($0 as? PersistenceError, .unsupportedSchema) }
                XCTAssertEqual(try Data(contentsOf: url), before)
            }
        }
        try fixture { url in
            let bytes = Data("synthetic corrupt incident file".utf8); try bytes.write(to: url)
            XCTAssertThrowsError(try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 0)) { XCTAssertEqual($0 as? PersistenceError, .corrupt) }
            XCTAssertEqual(try Data(contentsOf: url), bytes)
        }
    }
    func testBuildFactsMigrationPreservesHistoryAndRollsBackOnConflict() throws {
        for conflict in [false, true] {
            try fixture { url in
                let raw = try SQLiteConnection(url: url)
                for revision in 1...9 {
                    for sql in try IncidentDetailFixture.shared("incident-v\(revision).sql").split(separator: ";") where !sql.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { try raw.execute(String(sql)) }
                }
                try raw.execute("ALTER TABLE environment_facts ADD COLUMN optional_future TEXT")
                if conflict { try raw.execute("INSERT INTO environment_facts(incident_id,key,type,truncated) SELECT id,'androidDebuggable','unavailable',0 FROM incidents WHERE environment_platform IS NOT NULL") }
                raw.close()
                if conflict { XCTAssertThrowsError(try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 2000)) }
                else {
                    let store = try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 2000)
                    defer { store.close() }
                    XCTAssertEqual(try store.summaries().count, 4)
                    let snapshot = try XCTUnwrap(store.read(id: "12345678-1234-1234-1234-123456789abc"))
                    XCTAssertEqual(snapshot.timestampMillis, -17); XCTAssertEqual(snapshot.note?.text, "legacy")
                    XCTAssertEqual(snapshot.environment?.timestampMillis, -23)
                    for key in [EnvironmentKey.androidTargetSDK, .androidMinSDK, .androidDebuggable] {
                        guard case .unavailable = snapshot.environment?.values[key]?.fact else { return XCTFail("Historical build facts must remain unknown") }
                    }
                }
                let check = try SQLiteConnection(url: url); defer { check.close() }
                try check.statement("PRAGMA user_version") { XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), conflict ? 9 : 12) }
                if conflict {
                    try check.statement("SELECT count(*) FROM environment_facts WHERE key IN ('androidTargetSDK','androidMinSDK')") {
                        XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), 0)
                    }
                }
            }
        }
    }
    func testFullDatabaseRollsBackRetentionCleanupAndAllowsExplicitDeletion() throws {
        try fixture { url in
            let options = try IncidentPersistenceOptions(retainedIncidents: 2, textBytesPerIncident: 2_097_152, databaseByteLimit: 1_048_576)
            let store = try IncidentDatabase(url: url, options: options, now: 0)
            defer { store.close() }
            let first = try context(), second = try context(count: 64, message: String(repeating: "x", count: 4_095))
            try store.save(first, now: 0); try store.save(second, now: 1)
            let large = try context(count: 250, message: String(repeating: "x", count: 4_095))
            XCTAssertThrowsError(try store.save(large, now: 2)) { XCTAssertEqual($0 as? PersistenceError, .storageFull) }
            XCTAssertEqual(try store.summaries().map(\.id), [second.id, first.id])
            XCTAssertEqual(try store.read(id: first.id)?.textByteCount, first.textByteCount)
            try store.delete(id: second.id)
            try store.save(context(), now: 3)
            XCTAssertEqual(try store.summaries().count, 2)
        }
    }
}
