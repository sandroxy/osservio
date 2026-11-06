import Foundation
import OsservioCore
import XCTest

final class SystemDiagnosticTests: XCTestCase {
    func testSharedClassificationPrecisionAndInvalidEvidence() throws {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let fixture = try String(contentsOf: root.appendingPathComponent("testing/system-diagnostic-cases.tsv"), encoding: .utf8)
        for line in fixture.split(separator: "\n") {
            let row = line.split(separator: "\t").map(String.init)
            do {
                let value = try diagnostic(source: row[1], field: row[2], value: row[3])
                XCTAssertEqual(value.kind.rawValue, row[4], row[0])
                if row[2] == "code" { XCTAssertEqual(value.metricKit?.exceptionCode, row[3], row[0]) }
                if row[1] == "android", row[2] == "start" { XCTAssertEqual(value.intervalStartMillis, Int64(row[3]), row[0]) }
            } catch { XCTAssertEqual(row[4], "invalid", row[0]) }
        }
    }
    private func diagnostic(source: String, field: String, value: String) throws -> SystemDiagnostic {
        func number(_ key: String, _ fallback: Int64) -> Int64 { field == key ? Int64(value)! : fallback }
        func optional(_ key: String) -> Int64? { field == key ? Int64(value)! : nil }
        if source == "android" {
            return try .androidExit(timestampMillis: number("start", 100), reasonCode: Int32(number("reason", 4)), status: 0,
                processID: Int32(number("pid", 123)), importance: Int32(number("importance", 100)), pssKiB: optional("pss"), rssKiB: optional("rss"))
        }
        let hang = source == "hang"
        return try .metricKit(intervalStartMillis: number("start", 100), intervalEndMillis: 200,
            kind: field == "kind" ? SystemDiagnosticKind(rawValue: value)! : (hang ? .hang : .crash), metadata: [:],
            signal: optional("signal").map(Int32.init), exceptionType: optional("type"),
            exceptionCode: field == "code" ? value : nil, hangDurationMillis: field == "duration" ? Int64(value)! : (hang ? 150 : nil))
    }
    func testBoundedPublicMetadataAndStrictRestoration() throws {
        let raw = String(repeating: "😀", count: 65)
        let value = try SystemDiagnostic.Text.capture(publicValue: raw)
        XCTAssertEqual(value.value, String(repeating: "😀", count: 64)); XCTAssertTrue(value.isTruncated)
        let restored = try SystemDiagnostic.Text.restore(value: value.value, isTruncated: true)
        XCTAssertTrue(restored.isTruncated)
        XCTAssertThrowsError(try SystemDiagnostic.Text.restore(value: raw, isTruncated: true))
        XCTAssertThrowsError(try SystemDiagnostic.Text.capture(publicValue: ""))
        let item = try SystemDiagnostic.metricKit(intervalStartMillis: 100, intervalEndMillis: 200, kind: .crash,
            metadata: [.appVersion: restored], exceptionCode: "18446744073709551615")
        XCTAssertEqual(item.textByteCount, 276)
        XCTAssertEqual(item.metricKit?.metadata[.appVersion]?.value, value.value)
        XCTAssertNil(item.androidExit)
    }
    func testBatchPreservesRepeatedEvidenceWithoutInventingIdentityAndRejectsOverflow() throws {
        let exit = try diagnostic(source: "android", field: "reason", value: "4")
        let crash = try diagnostic(source: "metric", field: "kind", value: "crash")
        let batch = try SystemDiagnosticBatch(source: .androidExitInfo, observedAtMillis: 42,
            diagnostics: [exit, exit], rejectedCount: 3, excludedCount: 27, sourceLimitReached: true)
        XCTAssertEqual(batch.diagnostics.count, 2); XCTAssertEqual(batch.observedAtMillis, 42)
        XCTAssertTrue(batch.sourceLimitReached)
        XCTAssertThrowsError(try SystemDiagnosticBatch(source: .androidExitInfo, observedAtMillis: 0, diagnostics: [crash]))
        XCTAssertThrowsError(try SystemDiagnosticBatch(source: .androidExitInfo, observedAtMillis: 0, diagnostics: [exit], rejectedCount: 32))
        XCTAssertThrowsError(try SystemDiagnosticBatch(source: .androidExitInfo, observedAtMillis: 0, diagnostics: [], excludedCount: -1))
        XCTAssertThrowsError(try SystemDiagnosticBatch(source: .androidExitInfo, observedAtMillis: 0, diagnostics: Array(repeating: exit, count: 33)))
    }
}
