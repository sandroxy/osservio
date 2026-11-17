import Foundation
import OsservioCore
@testable import OsservioStore
import XCTest

final class NetworkErrorDatabaseTests: XCTestCase {
    func testFrozenErrorAndCloseReasonSurviveReopenAndCorruptionIsRejected() throws {
        let corruptions = [
            "UPDATE incident_http SET error_limited=NULL",
            "UPDATE incident_http SET error_processed=2",
            "UPDATE incident_http SET error_code='" + String(repeating: "x", count: 65) + "'",
            "UPDATE incident_http SET error_message=CAST(X'FF' AS TEXT)",
            "UPDATE incident_websocket SET close_reason_processed=NULL WHERE close_reason IS NOT NULL",
            "UPDATE incident_websocket SET close_reason='" + String(repeating: "x", count: 513) + "' WHERE close_reason IS NOT NULL"
        ]
        for sql in corruptions {
            try fixture { url in
                let capture = CaptureController(debugEnabled: true), http = try MemoryHTTPStore(), sockets = try MemoryWebSocketStore()
                let requests = try HTTPRecorder(sink: http, captureController: capture, privacy: HTTPPrivacyPolicy())
                let token = try XCTUnwrap(requests.begin(method: "GET", url: "https://example.com").token)
                requests.finish(token, state: .failed, failure: .connection,
                    error: { NetworkErrorInput(domain: "fixture", code: "-1009", type: "Failure", message: "连接失败😀") })
                let connections = try WebSocketRecorder(sink: sockets, captureController: capture, privacy: WebSocketPrivacyPolicy())
                let connection = try XCTUnwrap(connections.begin(url: "wss://example.com").token)
                connections.lifecycle(connection, kind: .failed, closeCode: 1000, failure: .transport,
                    error: { NetworkErrorInput(message: "failed after close") }, closeReason: { _ in Data("normal close".utf8) })
                let snapshot = try IncidentSnapshot.capture(http: .capture(XCTUnwrap(http.snapshot()), observedAtMillis: 20, history: http),
                    webSockets: .capture(XCTUnwrap(sockets.snapshot()), observedAtMillis: 21, history: sockets))
                let writer = try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 0)
                try writer.save(snapshot, now: 0); writer.close(); http.clear(); sockets.clear()
                let reader = try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 1)
                let saved = try XCTUnwrap(reader.read(id: snapshot.id)); reader.close()
                XCTAssertEqual(saved.http?.events.first?.error?.message, "连接失败😀")
                XCTAssertEqual(saved.http?.events.first?.error?.code, "-1009")
                XCTAssertEqual(saved.webSockets?.events.last?.error?.message, "failed after close")
                XCTAssertEqual(saved.webSockets?.events.last?.closeReason?.text, "normal close")
                XCTAssertEqual(saved.webSockets?.events.last?.closeCode, 1000)
                XCTAssertEqual(saved.textByteCount, snapshot.textByteCount)
                let raw = try SQLiteConnection(url: url); try raw.execute(sql); raw.close()
                let damaged = try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 2); defer { damaged.close() }
                XCTAssertThrowsError(try damaged.read(id: snapshot.id), sql)
            }
        }
    }

    func testRevisionTenDoesNotInventErrorTextAndMigrationRollsBackOnConflict() throws {
        for conflict in [false, true] {
            try fixture { url in
                let raw = try SQLiteConnection(url: url)
                for revision in 1...10 {
                    for sql in try IncidentDetailFixture.shared("incident-v\(revision).sql").split(separator: ";") where !sql.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { try raw.execute(String(sql)) }
                }
                if conflict { try raw.execute("ALTER TABLE incident_websocket ADD COLUMN close_reason TEXT") }
                raw.close()
                if conflict { XCTAssertThrowsError(try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 2000)) }
                else {
                    let store = try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 2000)
                    XCTAssertEqual(try store.summaries().count, 4)
                    for summary in try store.summaries() {
                        let saved = try XCTUnwrap(store.read(id: summary.id))
                        XCTAssertTrue(saved.http?.events.allSatisfy { $0.error == nil } ?? true)
                        XCTAssertTrue(saved.webSockets?.events.allSatisfy { $0.error == nil && $0.closeReason == nil } ?? true)
                    }
                    store.close()
                }
                let check = try SQLiteConnection(url: url); defer { check.close() }
                try check.statement("PRAGMA user_version") { XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), conflict ? 10 : 11) }
                if conflict { XCTAssertThrowsError(try check.execute("SELECT error_domain FROM incident_http")) }
            }
        }
    }

    private func fixture(_ body: (URL) throws -> Void) throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        try body(directory.appendingPathComponent("incidents.sqlite"))
    }
}
