import Foundation
import OsservioCore
@testable import OsservioStore
import XCTest

final class HTTPMetricsTests: XCTestCase {
    private func metrics() throws -> HTTPMetrics {
        try HTTPMetrics(phases: [HTTPPhaseTiming(phase: .connect, startMillis: 0, endMillis: 7),
            HTTPPhaseTiming(phase: .tls, startMillis: 2, endMillis: 7),
            HTTPPhaseTiming(phase: .connect, startMillis: 10, endMillis: nil)], protocolName: "h2", cache: .network, connectionReused: false)
    }
    func testBoundariesAndFinishDoNotChangeHTTPRevisionOrRestoreClearedRecords() throws {
        XCTAssertThrowsError(try HTTPPhaseTiming(phase: .dns, startMillis: -1, endMillis: nil))
        XCTAssertThrowsError(try HTTPPhaseTiming(phase: .dns, startMillis: 2, endMillis: 1))
        XCTAssertThrowsError(try HTTPMetrics(phases: [], protocolName: String(repeating: "x", count: 33)))
        XCTAssertThrowsError(try HTTPMetrics(phases: [], protocolName: "h2\n"))
        XCTAssertThrowsError(try HTTPMetrics(phases: Array(repeating: metrics().phases[0], count: 33)))
        let store = try MemoryHTTPStore(), controller = CaptureController(); try controller.startDetailed()
        let recorder = try HTTPRecorder(sink: store, captureController: controller, privacy: HTTPPrivacyPolicy())
        let token = try XCTUnwrap(recorder.begin(method: "GET", url: "https://example.com").token)
        XCTAssertEqual(recorder.receiveResponse(token, statusCode: 200, headers: []), .recorded)
        XCTAssertEqual(recorder.finish(token, state: .completed, metrics: try metrics()), .recorded)
        let event = try XCTUnwrap(store.snapshot()?.events.first)
        XCTAssertEqual(event.revision, 2); XCTAssertEqual(event.metrics?.phases.count, 3)
        XCTAssertNil(event.metrics?.phases.last?.endMillis)
        let second = try XCTUnwrap(recorder.begin(method: "GET", url: "https://example.com").token)
        store.clear(); XCTAssertEqual(recorder.finish(second, state: .failed, failure: .connection, metrics: try metrics()), .missing)
        XCTAssertTrue(try XCTUnwrap(store.snapshot()).events.isEmpty)
    }
    func testFrozenMetricsSurviveReopenAndMissingOrCorruptRowsAreRejected() throws {
        for corruptSQL in ["DELETE FROM incident_http_metrics", "DELETE FROM incident_http_phases WHERE position=2", "UPDATE incident_http_phases SET ended=-1 WHERE position=0"] {
            try fixture { url in
                let history = try MemoryHTTPStore(), controller = CaptureController(); try controller.startDetailed()
                let recorder = try HTTPRecorder(sink: history, captureController: controller, privacy: HTTPPrivacyPolicy())
                let token = try XCTUnwrap(recorder.begin(method: "GET", url: "https://example.com").token)
                recorder.finish(token, state: .failed, failure: .connection, metrics: try metrics())
                let snapshot = try IncidentSnapshot.capture(http: .capture(XCTUnwrap(history.snapshot()), observedAtMillis: 20, history: history))
                let store = try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 0)
                try store.save(snapshot, now: 0); store.close(); history.clear()
                let reopened = try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 1)
                let value = try XCTUnwrap(reopened.read(id: snapshot.id)); reopened.close()
                XCTAssertEqual(value.http?.events.first?.metrics?.phases.count, 3)
                XCTAssertEqual(value.http?.events.first?.metrics?.protocolName, "h2")
                XCTAssertEqual(value.textByteCount, snapshot.textByteCount)
                let raw = try SQLiteConnection(url: url); try raw.execute(corruptSQL); raw.close()
                let reader = try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 2); defer { reader.close() }
                XCTAssertThrowsError(try reader.read(id: snapshot.id))
            }
        }
    }
    func testRevisionEightMigrationPreservesHistoryAndRollsBackOnConflict() throws {
        for conflict in [false, true] {
            try fixture { url in
                let raw = try SQLiteConnection(url: url)
                for revision in 1...8 {
                    for sql in try IncidentDetailFixture.shared("incident-v\(revision).sql").split(separator: ";") where !sql.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { try raw.execute(String(sql)) }
                }
                if conflict { try raw.execute("CREATE TABLE incident_http_metrics (collision INTEGER)") }; raw.close()
                if conflict { XCTAssertThrowsError(try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 2000)) }
                else {
                    let store = try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 2000)
                    XCTAssertEqual(try store.summaries().count, 4)
                    for summary in try store.summaries() {
                        let value = try XCTUnwrap(store.read(id: summary.id)); XCTAssertTrue(value.http?.events.allSatisfy { $0.metrics == nil } ?? true)
                    }
                    store.close()
                }
                let check = try SQLiteConnection(url: url); defer { check.close() }
                try check.statement("PRAGMA user_version") { XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), conflict ? 8 : 9) }
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
