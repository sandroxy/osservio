import Foundation
import OsservioCore
@testable import OsservioStore
import XCTest

final class DiagnosticPolicyTests: XCTestCase {
    private let headers = [HTTPHeader(name: "Content-Type", value: "application/json; charset=utf-8"),
                           HTTPHeader(name: "Authorization", value: "Bearer " + String(repeating: "a", count: 1_200)),
                           HTTPHeader(name: "X_Custom", value: "trace-value")]

    func testDefaultsPreserveUsefulContentAndExactRequestMetadata() throws {
        let capture = CaptureController(debugEnabled: true), store = try MemoryHTTPStore()
        let recorder = try HTTPRecorder(sink: store, captureController: capture, privacy: HTTPPrivacyPolicy())
        let url = "https://user:fixture@example.com/api?name=%E4%BD%A0&empty=&x=1&x=2#section"
        let token = try XCTUnwrap(recorder.begin(method: "PROPFIND", url: url, headers: headers).token)
        let bytes = Data("{\"token\":\"synthetic-only\",\"page\":2}".utf8)
        XCTAssertEqual(recorder.observeBody(token, direction: .request, byteCount: Int64(bytes.count)) { Data(bytes.prefix($0)) }, .recorded)
        XCTAssertEqual(recorder.receiveResponse(token, statusCode: 400, headers: headers), .recorded)
        XCTAssertEqual(recorder.finish(token, state: .completed), .recorded)
        let event = try XCTUnwrap(store.snapshot()?.events.first)
        XCTAssertEqual(event.url, url); XCTAssertEqual(event.method, "PROPFIND")
        XCTAssertEqual(event.requestHeaders.map(\.value), headers.map(\.value)); XCTAssertFalse(event.headersLimited)
        guard case .available(let body) = store.body(id: try XCTUnwrap(event.requestBody.blobID)) else { return XCTFail("Missing request") }
        XCTAssertEqual(body.text, String(decoding: bytes, as: UTF8.self)); XCTAssertFalse(event.requestBody.isProcessed)
        try EvidenceRestorationTests.check(event)
    }

    func testHostProcessingSurvivesSnapshotDatabaseAndOriginalByteAccounting() throws {
        let capture = CaptureController(debugEnabled: true), http = try MemoryHTTPStore(), sockets = try MemoryWebSocketStore()
        let policy = DiagnosticTextPolicy { input in
            switch input.field {
            case .header("authorization"): return nil
            case .requestBody, .webSocketMessage: return "{\"reviewed\":true}"
            default: return input.text
            }
        }
        let recorder = try HTTPRecorder(sink: http, captureController: capture, privacy: HTTPPrivacyPolicy(textPolicy: policy))
        let token = try XCTUnwrap(recorder.begin(method: "POST", url: "https://example.com?q=value", headers: headers).token)
        _ = recorder.observeBody(token, direction: .request, byteCount: 2) { _ in Data("{}".utf8) }
        _ = recorder.finish(token, state: .failed, failure: .connection)
        let socket = try WebSocketRecorder(sink: sockets, captureController: capture, privacy: WebSocketPrivacyPolicy(textPolicy: policy))
        let connection = try XCTUnwrap(socket.begin(url: "wss://example.com?token=fixture").token)
        _ = socket.text(connection, direction: .incoming, outcome: .received, value: "{}")
        let frozenHTTP = try IncidentHTTP.capture(XCTUnwrap(http.snapshot()), observedAtMillis: 1, history: http, includeBodies: true)
        let frozenSockets = try IncidentWebSockets.capture(XCTUnwrap(sockets.snapshot()), observedAtMillis: 1, history: sockets, includePayloads: true)
        let snapshot = try IncidentSnapshot.capture(http: frozenHTTP, webSockets: frozenSockets)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let database = try IncidentDatabase(url: directory.appendingPathComponent("scope.sqlite"), options: IncidentPersistenceOptions(), now: 1)
        try database.save(snapshot, now: 1)
        let restored = try XCTUnwrap(database.read(id: snapshot.id))
        database.close()
        let event = try XCTUnwrap(restored.http?.events.first)
        XCTAssertTrue(event.requestBody.isProcessed); XCTAssertEqual(event.requestBody.observedBytes, 2)
        XCTAssertEqual(event.requestHeaders.first { $0.name == "authorization" }?.value, "[redacted]")
        XCTAssertEqual(restored.http?.payloads[event.requestBody.blobID!]?.text, "{\"reviewed\":true}")
        let message = try XCTUnwrap(restored.webSockets?.events.last?.payload)
        XCTAssertTrue(message.isProcessed); XCTAssertEqual(message.byteCount, 2)
    }

    func testFailedAndOversizedPolicyNeverFallsBackToRawContent() throws {
        for policy in [DiagnosticTextPolicy { _ in throw NSError(domain: "fixture", code: 1) },
                       DiagnosticTextPolicy { _ in String(repeating: "x", count: 70_000) }] {
            let store = try MemoryHTTPStore(), capture = CaptureController(debugEnabled: true)
            let recorder = try HTTPRecorder(sink: store, captureController: capture, privacy: HTTPPrivacyPolicy(textPolicy: policy))
            let token = try XCTUnwrap(recorder.begin(method: "POST", url: "https://example.com?secret=fixture", headers: headers).token)
            _ = recorder.observeBody(token, direction: .request, byteCount: 2) { _ in Data("{}".utf8) }
            _ = recorder.finish(token, state: .failed, failure: .connection)
            let event = try XCTUnwrap(store.snapshot()?.events.first)
            XCTAssertEqual(event.url, "[unavailable]"); XCTAssertEqual(event.requestBody.state, .omitted)
            XCTAssertTrue(event.requestHeaders.allSatisfy { $0.value == "[redacted]" })
            XCTAssertEqual(store.snapshot()?.retainedBodyBytes, 0)
        }
    }

    func testClosedAccountOwnerCannotResumeOrAcceptLateCallbacks() throws {
        let old = CaptureController(baselineEnabled: true, debugEnabled: true), oldStore = try MemoryHTTPStore()
        let recorder = try HTTPRecorder(sink: oldStore, captureController: old, privacy: HTTPPrivacyPolicy())
        let token = try XCTUnwrap(recorder.begin(method: "GET", url: "https://example.com/account-a").token)
        let logs = try MemoryLogStore(), crumbs = try MemoryBreadcrumbStore(), sockets = try MemoryWebSocketStore()
        let logger = try LogRecorder(sink: logs, captureController: old)
        let breadcrumb = try BreadcrumbRecorder(sink: crumbs, captureController: old)
        let socket = try WebSocketRecorder(sink: sockets, captureController: old, privacy: WebSocketPrivacyPolicy())
        let connection = try XCTUnwrap(socket.begin(url: "wss://example.com/account-a").token)
        XCTAssertTrue(old.close()); XCTAssertTrue(old.close()); XCTAssertEqual(old.status()?.isClosed, true)
        XCTAssertThrowsError(try old.setDebugEnabled(true)); XCTAssertThrowsError(try old.startDetailed())
        XCTAssertFalse(old.setBaselineEnabled(true)); XCTAssertEqual(recorder.receiveResponse(token, statusCode: 200, headers: []), .inactive)
        XCTAssertEqual(logger.record(.info, category: "account", message: .publicText("late A")), .inactive)
        XCTAssertEqual(breadcrumb.record(.hostMarker, label: "account.late"), .inactive)
        XCTAssertEqual(socket.text(connection, direction: .incoming, outcome: .received, value: "late A"), .inactive)
        XCTAssertEqual(socket.status()?.activeConnections, 0)
        let new = CaptureController(debugEnabled: true), newStore = try MemoryHTTPStore()
        let next = try HTTPRecorder(sink: newStore, captureController: new, privacy: HTTPPrivacyPolicy())
        XCTAssertEqual(next.begin(method: "GET", url: "https://example.com/account-b").result, .recorded)
        XCTAssertEqual(oldStore.snapshot()?.events.first?.state, .captureEnded)
        XCTAssertEqual(newStore.snapshot()?.events.count, 1)
        XCTAssertEqual(newStore.snapshot()?.events.first?.url, "https://example.com/account-b")
        XCTAssertEqual(next.receiveResponse(token, statusCode: 200, headers: []), .stale)
    }
    func testRevisionSixMigrationPreservesEvidenceAndRollsBackPartialDDL() throws {
        for conflict in [false, true] {
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            defer { try? FileManager.default.removeItem(at: directory) }
            let url = directory.appendingPathComponent("legacy.sqlite")
            let raw = try SQLiteConnection(url: url)
            for revision in 1...6 {
                for sql in try IncidentDetailFixture.shared("incident-v\(revision).sql").split(separator: ";") where !sql.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    try raw.execute(String(sql))
                }
            }
            if conflict { try raw.execute("ALTER TABLE incident_http ADD COLUMN response_processed INTEGER NOT NULL DEFAULT 0") }
            raw.close()
            if conflict {
                XCTAssertThrowsError(try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 2000))
            } else {
                let store = try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 2000)
                XCTAssertEqual(try store.summaries().count, 4)
                for summary in try store.summaries() { XCTAssertNotNil(try store.read(id: summary.id)) }
                XCTAssertEqual(try store.read(id: "12345678-1234-1234-1234-123456789abc")?.logs?.events.first?.message, "[private]")
                store.close()
            }
            let check = try SQLiteConnection(url: url); defer { check.close() }
            try check.statement("PRAGMA user_version") { XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), conflict ? 6 : 9) }
            var requestColumn = false
            try check.statement("PRAGMA table_info(incident_http)") { row in
                while try row.step() { if try row.text(1, byteLimit: 64) == "request_processed" { requestColumn = true } }
            }
            XCTAssertEqual(requestColumn, !conflict, "A failed migration rolls back earlier ALTER statements")
        }
    }

}
