import Foundation
import OsservioCore
import OsservioExport
import XCTest

final class IncidentDocumentsTests: XCTestCase {
    func testDocumentsMatchIndependentSharedFixtures() throws {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        for name in ["absent", "empty", "complete", "diagnostic-android", "diagnostic-crash", "diagnostic-hang", "diagnostic-signal"] {
            let expected = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: root.appendingPathComponent("testing/incident-export/\(name).json"))) as? [String: Any])
            let documents = try IncidentDocuments.encode(IncidentDocumentFixture.make(name))
            let metadata = try XCTUnwrap(JSONSerialization.jsonObject(with: documents.metadataJSON) as? NSDictionary)
            let payloads = try XCTUnwrap(JSONSerialization.jsonObject(with: documents.payloadsJSON) as? NSDictionary)
            XCTAssertEqual(metadata, try XCTUnwrap(expected["metadata"] as? NSDictionary), name)
            XCTAssertEqual(payloads, try XCTUnwrap(expected["payloads"] as? NSDictionary), name)
            XCTAssertFalse(String(decoding: documents.metadataJSON, as: UTF8.self).contains("synthetic-secret"))
        }
    }
    func testUnsignedNativeDurationKeepsAllBits() throws {
        let crumb = try BreadcrumbEvent.restore(id: IncidentDocumentFixture.id, sessionID: IncidentDocumentFixture.session, sequence: 1,
            timestampMillis: 0, sessionElapsedMillis: .max, kind: .hostMarker, label: "fixture", message: nil,
            isPrivate: false, isTruncated: false, value: nil, retention: .memoryOnly)
        let snapshot = try IncidentSnapshot.restore(id: IncidentDocumentFixture.id, timestampMillis: 0, note: nil, environment: nil,
            breadcrumbs: .restore(events: [crumb], sourceEventCount: 1, sourceEvictedCount: 0))
        let documents = try IncidentDocuments.encode(snapshot)
        XCTAssertTrue(String(decoding: documents.metadataJSON, as: UTF8.self).contains("\"18446744073709551615\""))
    }
    func testOversizedCombinedEvidenceIsRejectedBeforeEncoding() throws {
        let message = String(repeating: "x", count: 65_529)
        let events = try (1...16).map { try LogEvent.restore(sessionID: IncidentDocumentFixture.session, sequence: Int64($0), timestampMillis: 0,
            level: .info, category: "fixture", message: message, isPrivate: false, isTruncated: false) }
        let logs = try IncidentLogs.restore(events: events, observedAtMillis: 0, sourceEventCount: 16, sourceEvictedCount: 0)
        let crumbs = try (1...256).map { index in try BreadcrumbEvent.restore(id: UUID().uuidString, sessionID: IncidentDocumentFixture.session,
            sequence: Int64(index), timestampMillis: 0, sessionElapsedMillis: nil, kind: .hostMarker, label: "a",
            message: String(repeating: "x", count: 4_095), isPrivate: false, isTruncated: false, value: nil, retention: .memoryOnly) }
        let detail = try IncidentDocumentFixture.make("complete")
        let snapshot = try IncidentSnapshot.restore(id: detail.id, timestampMillis: 0, note: .capture(.publicText(String(repeating: "x", count: 4_096))),
            environment: detail.environment, breadcrumbs: .restore(events: crumbs, sourceEventCount: 256, sourceEvictedCount: 0),
            logs: logs, http: detail.http, webSockets: detail.webSockets)
        XCTAssertGreaterThan(snapshot.textByteCount, IncidentDocuments.maximumTextBytes)
        XCTAssertThrowsError(try IncidentDocuments.encode(snapshot)) { error in
            guard case IncidentDocumentError.tooLarge = error else { return XCTFail("Unexpected error: \(error)") }
        }
    }
}
