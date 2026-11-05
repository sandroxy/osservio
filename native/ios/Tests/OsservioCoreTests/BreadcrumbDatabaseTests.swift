import Foundation
import OsservioCore
@testable import OsservioStore
import XCTest

final class BreadcrumbDatabaseTests: XCTestCase {
    private func fixture(_ body: (URL) throws -> Void) throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("osservio-evidence-test-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: directory) }
        try body(directory.appendingPathComponent("evidence.sqlite"))
    }
    private func events(count: Int, text: String = "public fixture", retention: BreadcrumbRetention = .restartRelevant) throws -> [BreadcrumbEvent] {
        let history = try MemoryBreadcrumbStore(textByteLimit: 1_048_576)
        let recorder = try BreadcrumbRecorder(sink: history, captureController: CaptureController(baselineEnabled: true), messageByteLimit: 4_096, eventsPerSecond: 1_000)
        for _ in 0..<count { XCTAssertEqual(recorder.record(.hostMarker, label: "a", message: .publicText(text), retention: retention), .recorded) }
        return try XCTUnwrap(history.snapshot()?.events)
    }
    private func database(_ url: URL, id: String = UUID().uuidString, options: BreadcrumbPersistenceOptions? = nil,
                          now: Int64 = 1_000) throws -> BreadcrumbDatabase {
        try BreadcrumbDatabase(url: url, options: options ?? BreadcrumbPersistenceOptions(), runID: id, platform: .ios, processID: 123, startedAtMillis: now)
    }
    func testCommittedEvidenceSurvivesReopenWithoutCreatingAnEmptyRun() throws {
        try fixture { url in
            let id = UUID().uuidString
            let original = try events(count: 2, text: "public\u{0}例")
            let writer = try database(url, id: id)
            XCTAssertTrue(try writer.runs().isEmpty)
            try writer.write(original, now: 1_001)
            writer.close()
            let reader = try database(url, now: 1_002)
            defer { reader.close() }
            let restored = try XCTUnwrap(reader.read(id: id))
            XCTAssertFalse(restored.run.isCurrent)
            XCTAssertEqual(restored.events.map(\.id), original.map(\.id))
            XCTAssertEqual(restored.events.map(\.message), original.map(\.message))
            XCTAssertEqual(restored.events.map(\.sessionID), original.map(\.sessionID))
            XCTAssertEqual(restored.events.map(\.sequence), original.map(\.sequence))
            XCTAssertEqual(try reader.runs().count, 1)
        }
    }
    func testTextAndCountBudgetsEvictOldestAndCanBeLoweredOnReopen() throws {
        try fixture { url in
            let id = UUID().uuidString
            let options = try BreadcrumbPersistenceOptions(recordsPerRun: 3, textBytesPerRun: 64)
            let writer = try database(url, id: id, options: options)
            try writer.write(events(count: 5, text: String(repeating: "x", count: 31)), now: 1_001)
            let retained = try XCTUnwrap(writer.read(id: id))
            XCTAssertEqual(retained.events.map(\.sequence), [4, 5])
            XCTAssertEqual(retained.run.evictedEvents, 3)
            writer.close()
            let reader = try database(url, options: BreadcrumbPersistenceOptions(recordsPerRun: 1, textBytesPerRun: 64), now: 1_002)
            defer { reader.close() }
            XCTAssertEqual(try reader.read(id: id)?.events.map(\.sequence), [5])
            XCTAssertEqual(try reader.read(id: id)?.run.evictedEvents, 4)
        }
    }
    func testRunCountAndAgeLimitsDoNotInventAnExitCause() throws {
        try fixture { url in
            let options = try BreadcrumbPersistenceOptions(retainedRuns: 2, maximumAgeMillis: 60_000)
            var ids: [String] = []
            for index in 0..<3 {
                let id = UUID().uuidString; ids.append(id)
                let writer = try database(url, id: id, options: options, now: Int64(index))
                try writer.write(events(count: 1), now: Int64(index)); writer.close()
            }
            let reader = try database(url, options: options, now: 3)
            XCTAssertEqual(try reader.runs().map(\.id), Array(ids.suffix(2).reversed()))
            XCTAssertNil(try reader.read(id: ids[0])); reader.close()
            let expired = try database(url, options: options, now: 60_003)
            defer { expired.close() }
            XCTAssertTrue(try expired.runs().isEmpty)
        }
    }
    func testRejectedBatchLeavesPriorCommitIntact() throws {
        try fixture { url in
            let id = UUID().uuidString
            let writer = try database(url, id: id)
            defer { writer.close() }
            let initial = try events(count: 1)
            try writer.write(initial, now: 1_000)
            let next = try events(count: 1)
            XCTAssertThrowsError(try writer.write(next + initial, now: 1_001))
            XCTAssertEqual(try writer.read(id: id)?.events.map(\.id), initial.map(\.id))
            XCTAssertThrowsError(try writer.write(events(count: 1, retention: .memoryOnly), now: 1_001))
            XCTAssertEqual(try writer.read(id: id)?.events.count, 1)
        }
    }
    func testUnknownDatabaseAndNewerRevisionAreNotReset() throws {
        try fixture { url in
            let other = try SQLiteConnection(url: url)
            try other.execute("CREATE TABLE other_data (value TEXT)")
            try other.execute("INSERT INTO other_data VALUES ('keep')")
            other.close()
            let before = try Data(contentsOf: url)
            XCTAssertThrowsError(try database(url)) { XCTAssertEqual($0 as? PersistenceError, .unsupportedSchema) }
            XCTAssertEqual(try Data(contentsOf: url), before)
        }
        try fixture { url in
            let writer = try database(url); writer.close()
            let raw = try SQLiteConnection(url: url)
            try raw.execute("PRAGMA user_version=2"); raw.close()
            let before = try Data(contentsOf: url)
            XCTAssertThrowsError(try database(url)) { XCTAssertEqual($0 as? PersistenceError, .unsupportedSchema) }
            XCTAssertEqual(try Data(contentsOf: url), before)
        }
    }
    func testReaderIgnoresOptionalColumnsButRejectsInvalidPrivateEvidence() throws {
        try fixture { url in
            let id = UUID().uuidString
            let writer = try database(url, id: id)
            try writer.write(events(count: 1), now: 1_000); writer.close()
            let raw = try SQLiteConnection(url: url)
            try raw.execute("ALTER TABLE breadcrumbs ADD COLUMN extra_optional TEXT")
            raw.close()
            let compatible = try database(url)
            XCTAssertEqual(try compatible.read(id: id)?.events.count, 1); compatible.close()
            let corrupt = try SQLiteConnection(url: url)
            try corrupt.execute("UPDATE breadcrumbs SET private=1")
            corrupt.close()
            let reader = try database(url)
            defer { reader.close() }
            XCTAssertThrowsError(try reader.read(id: id)) { XCTAssertEqual($0 as? PersistenceError, .invalidRecord) }
        }
    }
    func testDatabasePageLimitFailsWithoutDiscardingThePriorCommit() throws {
        try fixture { url in
            let id = UUID().uuidString
            let options = try BreadcrumbPersistenceOptions(recordsPerRun: 2_000, textBytesPerRun: 1_048_576, databaseByteLimit: 1_048_576)
            let writer = try database(url, id: id, options: options)
            defer { writer.close() }
            var before: [String] = []
            var encounteredFull = false
            for _ in 0..<8 {
                do { try writer.write(events(count: 64, text: String(repeating: "x", count: 4_095)), now: 1_000) }
                catch {
                    XCTAssertEqual(error as? PersistenceError, .storageFull)
                    XCTAssertEqual(try writer.read(id: id)?.events.map(\.id), before)
                    encounteredFull = true; break
                }
                before = try XCTUnwrap(writer.read(id: id)?.events.map(\.id))
            }
            XCTAssertTrue(encounteredFull)
            let bytes = try XCTUnwrap(FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)
            XCTAssertLessThanOrEqual(bytes.intValue, options.databaseByteLimit)
        }
    }
    func testMalformedFileIsReportedWithoutAutomaticDeletion() throws {
        try fixture { url in
            let invalid = Data("synthetic damaged database".utf8)
            try invalid.write(to: url)
            XCTAssertThrowsError(try database(url)) { XCTAssertEqual($0 as? PersistenceError, .corrupt) }
            XCTAssertEqual(try Data(contentsOf: url), invalid)
        }
    }
}
