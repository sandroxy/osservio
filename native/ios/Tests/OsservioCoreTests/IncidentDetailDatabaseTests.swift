import Foundation
import OsservioCore
@testable import OsservioStore
import XCTest

final class IncidentDetailDatabaseTests: XCTestCase {
    private let tables = ["incident_windows", "incident_logs", "incident_http", "incident_websocket", "incident_headers", "incident_payloads"]
    private let legacyID = "12345678-1234-1234-1234-123456789abc"
    private func fixture(_ body: (URL) throws -> Void) throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("osservio-detail-db-test-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: directory) }
        try body(directory.appendingPathComponent("incidents.sqlite"))
    }
    func testRoundTripPreservesAttachmentsPrivacyPayloadStatesAndIndependentTimes() throws {
        try fixture { url in
            let options = try IncidentPersistenceOptions(), snapshot = try IncidentDetailFixture.snapshot()
            let writer = try IncidentDatabase(url: url, options: options, now: 0)
            try writer.save(snapshot, now: 0); writer.close()
            let reader = try IncidentDatabase(url: url, options: options, now: 1); defer { reader.close() }
            let restored = try XCTUnwrap(reader.read(id: snapshot.id))
            XCTAssertEqual(restored.textByteCount, snapshot.textByteCount); XCTAssertEqual(restored.note?.text, "[private]")
            let logs = try XCTUnwrap(restored.logs), http = try XCTUnwrap(restored.http), sockets = try XCTUnwrap(restored.webSockets)
            XCTAssertEqual(logs.observedAtMillis, -1); XCTAssertEqual(http.observedAtMillis, -2); XCTAssertEqual(sockets.observedAtMillis, -3)
            XCTAssertEqual(logs.events.map(\.sequence), [Int64.max - 1, Int64.max]); XCTAssertEqual(logs.events.first?.timestampMillis, Int64.min)
            XCTAssertEqual(logs.events.map(\.message), ["[private]", IncidentDetailFixture.body]); XCTAssertEqual(logs.events.map(\.isPrivate), [true, false])
            XCTAssertEqual(logs.events.map(\.isTruncated), [false, true]); XCTAssertEqual(logs.sourceEvictedCount, 9); XCTAssertEqual(logs.omittedEventCount, 3)
            let first = try XCTUnwrap(http.events.first)
            XCTAssertEqual(first.id, IncidentDetailFixture.id(10)); XCTAssertEqual(first.source, .okHttp); XCTAssertEqual(first.durationMillis, UInt64.max)
            XCTAssertEqual(first.timestampMillis, Int64.min); XCTAssertEqual(first.revision, 2); XCTAssertEqual(first.statusCode, 201)
            XCTAssertEqual(first.url, "https://example.com/path?[redacted]"); XCTAssertEqual(first.responseURL, "https://example.com/result")
            XCTAssertEqual(first.requestHeaders.map(\.value), ["[redacted]", "例\u{0}"]); XCTAssertEqual(first.responseHeaders.first?.value, "text/plain")
            XCTAssertTrue(first.headersLimited); XCTAssertEqual(http.payloads[first.id + ".request"]?.text, "")
            XCTAssertEqual(http.payloads[first.id + ".response"]?.text, IncidentDetailFixture.body)
            XCTAssertEqual(http.events.dropFirst().compactMap { http.payloads[$0.id + ".response"]?.state }, [.notSelected, .unavailable, .busy, .budgetExceeded])
            XCTAssertEqual(http.sourceEvictedEvents, 11); XCTAssertEqual(http.sourceEvictedBodies, 13); XCTAssertEqual(http.omittedEventCount, 2)
            XCTAssertEqual(sockets.events[0].statusCode, 101); XCTAssertEqual(sockets.events[0].headers.first?.value, "[redacted]")
            XCTAssertEqual(sockets.events[1].outcome, .submitted); XCTAssertEqual(sockets.events[2].relatedMessageID, sockets.events[1].id)
            XCTAssertEqual(sockets.events[2].outcome, .sent); XCTAssertNil(sockets.events[2].payload)
            XCTAssertEqual(sockets.events[3].payload?.byteCount, Int64.max); XCTAssertNil(sockets.events[4].payload?.byteCount)
            XCTAssertEqual(sockets.payloads[IncidentDetailFixture.id(21)]?.text, ""); XCTAssertEqual(sockets.payloads[IncidentDetailFixture.id(24)]?.text, "例")
            XCTAssertEqual(sockets.sourceEvictedEvents, 17); XCTAssertEqual(sockets.sourceEvictedPayloads, 19); XCTAssertEqual(sockets.omittedEventCount, 3)
            let summary = try XCTUnwrap(reader.summaries().first)
            XCTAssertEqual(summary.logCount, 2); XCTAssertEqual(summary.httpCount, 5); XCTAssertEqual(summary.webSocketCount, 5)
        }
    }
    func testAbsentAndEmptyGroupsRemainDistinctAndOptionalColumnsAreTolerated() throws {
        try fixture { url in
            let options = try IncidentPersistenceOptions(), snapshot = try IncidentDetailFixture.snapshot()
            let absent = try IncidentSnapshot.capture(), empty = try IncidentSnapshot.capture(
                logs: .restore(events: [], observedAtMillis: 1, sourceEventCount: 3, sourceEvictedCount: 4),
                http: .restore(events: [], payloads: [:], observedAtMillis: 2, sourceEventCount: 5, sourceEvictedEvents: 6, sourceEvictedBodies: 7),
                webSockets: .restore(events: [], payloads: [:], observedAtMillis: 3, sourceEventCount: 8, sourceEvictedEvents: 9, sourceEvictedPayloads: 10))
            let writer = try IncidentDatabase(url: url, options: options, now: 0)
            for value in [snapshot, absent, empty] { try writer.save(value, now: 0) }; writer.close()
            let raw = try SQLiteConnection(url: url)
            for table in tables { try raw.execute("ALTER TABLE \(table) ADD COLUMN future_optional TEXT") }; raw.close()
            let reader = try IncidentDatabase(url: url, options: options, now: 1); defer { reader.close() }
            let absentValue = try XCTUnwrap(reader.read(id: absent.id)), emptyValue = try XCTUnwrap(reader.read(id: empty.id))
            XCTAssertNil(absentValue.logs); XCTAssertNil(absentValue.http); XCTAssertNil(absentValue.webSockets)
            XCTAssertEqual(emptyValue.logs?.events.count, 0); XCTAssertEqual(emptyValue.http?.events.count, 0); XCTAssertEqual(emptyValue.webSockets?.events.count, 0)
            XCTAssertEqual(emptyValue.http?.sourceEvictedBodies, 7)
            let summaries = try reader.summaries(); XCTAssertEqual(summaries[0].httpCount, 0); XCTAssertNil(summaries[1].httpCount)
            XCTAssertEqual(try reader.read(id: snapshot.id)?.textByteCount, snapshot.textByteCount)
            try reader.save(IncidentDetailFixture.snapshot(), now: 1)
        }
    }
    func testSharedCorruptionCasesFailWithoutRepairingStoredEvidence() throws {
        for line in try IncidentDetailFixture.shared("incident-detail-corruption.tsv").split(separator: "\n") {
            let row = line.split(separator: "\t").map(String.init); XCTAssertEqual(row.count, 2)
            try fixture { url in
                let snapshot = try IncidentDetailFixture.snapshot(), options = try IncidentPersistenceOptions()
                let writer = try IncidentDatabase(url: url, options: options, now: 0); try writer.save(snapshot, now: 0); writer.close()
                let raw = try SQLiteConnection(url: url); try raw.execute(row[1]); raw.close()
                let before = try Data(contentsOf: url)
                let reader = try IncidentDatabase(url: url, options: options, now: 1)
                XCTAssertThrowsError(try reader.read(id: snapshot.id), row[0]) { XCTAssertEqual($0 as? PersistenceError, .invalidRecord, row[0]) }
                reader.close(); XCTAssertEqual(try Data(contentsOf: url), before, row[0])
            }
        }
    }
    private func legacy(_ url: URL) throws {
        let raw = try SQLiteConnection(url: url); defer { raw.close() }
        for sql in try IncidentDetailFixture.shared("incident-v1.sql").split(separator: ";") where !sql.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            try raw.execute(String(sql))
        }
    }
    func testMigrationPreservesLegacyEvidenceAndAllowsNewAttachments() throws {
        try fixture { url in
            try legacy(url)
            let store = try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 101)
            let restored = try XCTUnwrap(store.read(id: legacyID))
            XCTAssertEqual(restored.id, legacyID); XCTAssertEqual(restored.timestampMillis, -17); XCTAssertEqual(restored.note?.text, "legacy")
            XCTAssertEqual(restored.environment?.timestampMillis, -23); XCTAssertEqual(restored.environment?.platform, .android)
            guard case .text("com.example.fixture") = restored.environment?.values[.appIdentifier]?.fact else { return XCTFail("Environment lost") }
            XCTAssertEqual(restored.breadcrumbs?.events.first?.sequence, Int64.max); XCTAssertEqual(restored.breadcrumbs?.events.first?.timestampMillis, Int64.min)
            XCTAssertEqual(restored.breadcrumbs?.events.first?.message, "[private]"); XCTAssertEqual(restored.breadcrumbs?.sourceEvictedCount, 7)
            XCTAssertNil(restored.logs); XCTAssertNil(restored.http); XCTAssertNil(restored.webSockets)
            XCTAssertNil(try store.summaries().first?.logCount)
            let added = try IncidentDetailFixture.snapshot(); try store.save(added, now: 102)
            XCTAssertEqual(try store.read(id: added.id)?.textByteCount, added.textByteCount); store.close()
            let raw = try SQLiteConnection(url: url); defer { raw.close() }
            try raw.statement("PRAGMA user_version") { XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), 8) }
            try raw.statement("SELECT saved_at FROM incidents WHERE id=?", [.text(legacyID)]) { XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), 100) }
        }
    }
    func testFailedMigrationRollsBackAddedTablesAndRevision() throws {
        try fixture { url in
            try legacy(url)
            let conflict = try SQLiteConnection(url: url); try conflict.execute("CREATE TABLE incident_http (unrelated TEXT)"); conflict.close()
            XCTAssertThrowsError(try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 101))
            let raw = try SQLiteConnection(url: url); defer { raw.close() }
            try raw.statement("PRAGMA user_version") { XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), 1) }
            try raw.statement("SELECT count(*) FROM sqlite_master WHERE name IN ('incident_windows', 'incident_logs')") { XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), 0) }
            try raw.statement("SELECT note, text_bytes FROM incidents WHERE id=?", [.text(legacyID)]) {
                XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.text(0, byteLimit: 64), "legacy"); XCTAssertEqual(try $0.integer(1), 40)
            }
        }
    }
    func testCapacityRejectionAndRetentionDeleteCompleteAttachments() throws {
        try fixture { url in
            let first = try IncidentDetailFixture.snapshot(), second = try IncidentDetailFixture.snapshot()
            let options = try IncidentPersistenceOptions(retainedIncidents: 1, textBytesPerIncident: first.textByteCount)
            let store = try IncidentDatabase(url: url, options: options, now: 0)
            try store.save(first, now: 0)
            let oversized = try IncidentSnapshot.capture(note: .publicText(String(repeating: "x", count: 4096)), http: first.http)
            XCTAssertThrowsError(try store.save(oversized, now: 1)) { XCTAssertEqual($0 as? PersistenceError, .invalidRecord) }
            XCTAssertEqual(try store.read(id: first.id)?.textByteCount, first.textByteCount)
            try store.save(second, now: 2); XCTAssertNil(try store.read(id: first.id))
            XCTAssertEqual(try store.read(id: second.id)?.textByteCount, second.textByteCount)
            try store.delete(id: second.id); store.close()
            let raw = try SQLiteConnection(url: url); defer { raw.close() }
            for table in tables { try raw.statement("SELECT count(*) FROM \(table)") { XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), 0) } }
        }
    }
    func testDiskFullWhileSavingAttachmentsRollsBackPruningAndPartialRows() throws {
        try fixture { url in
            let first = try IncidentDetailFixture.snapshot(), second = try IncidentDetailFixture.snapshot()
            let options = try IncidentPersistenceOptions(retainedIncidents: 2, textBytesPerIncident: 2_097_152, databaseByteLimit: 1_048_576)
            let store = try IncidentDatabase(url: url, options: options, now: 0); defer { store.close() }
            try store.save(first, now: 0); try store.save(second, now: 1)
            let text = String(repeating: "x", count: 65_536)
            let events = try (0..<12).map { try LogEvent.restore(sessionID: IncidentDetailFixture.id(1), sequence: Int64($0 + 1), timestampMillis: 0,
                level: .info, category: "fixture", message: text, isPrivate: false, isTruncated: false) }
            let logs = try IncidentLogs.restore(events: events, observedAtMillis: 0, sourceEventCount: 12, sourceEvictedCount: 0)
            var http: [HTTPEvent] = [], payloads: [String: IncidentPayload] = [:]
            for index in 0..<4 {
                let id = IncidentDetailFixture.id(index + 30), blobID = id + ".response"
                http.append(try HTTPEvent.restore(id: id, sessionID: IncidentDetailFixture.id(1), sequence: Int64(index + 1), revision: 2,
                    timestampMillis: 0, method: "GET", source: .host, url: "https://example.com", responseURL: nil,
                    requestHeaders: [], responseHeaders: [], headersLimited: false, statusCode: 200, state: .completed, failure: nil, durationMillis: nil,
                    requestBody: .restore(state: .omitted, blobID: nil, observedBytes: 0), responseBody: .restore(state: .available, blobID: blobID, observedBytes: 65_536)))
                payloads[blobID] = try .restore(id: blobID, state: .available, text: text)
            }
            let large = try IncidentSnapshot.capture(logs: logs, http: .restore(events: http, payloads: payloads, observedAtMillis: 0,
                sourceEventCount: 4, sourceEvictedEvents: 0, sourceEvictedBodies: 0))
            XCTAssertThrowsError(try store.save(large, now: 2)) { XCTAssertEqual($0 as? PersistenceError, .storageFull) }
            XCTAssertEqual(try store.summaries().map(\.id), [second.id, first.id]); XCTAssertNil(try store.read(id: large.id))
            XCTAssertEqual(try store.read(id: first.id)?.textByteCount, first.textByteCount)
            let next = try IncidentDetailFixture.snapshot(); try store.save(next, now: 3)
            XCTAssertEqual(try store.read(id: next.id)?.textByteCount, next.textByteCount)
        }
    }
}
