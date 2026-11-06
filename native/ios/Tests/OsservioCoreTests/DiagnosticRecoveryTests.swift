import Foundation
import OsservioCore
@testable import OsservioStore
import XCTest

final class DiagnosticRecoveryTests: XCTestCase {
    func testFingerprintsMatchIndependentVectorsIncludingFramingAndExactNumbers() throws {
        for line in try IncidentDetailFixture.shared("diagnostic-fingerprints.tsv").split(separator: "\n") {
            let fields = line.split(separator: "\t").map(String.init)
            XCTAssertEqual(DiagnosticFingerprint.make(try evidence(fields[0])), fields[1], fields[0])
        }
    }
    private func evidence(_ name: String) throws -> SystemDiagnostic {
        switch name {
        case "diagnostic-tombstone-summary": return try TombstoneFixture.evidence(state: .notRequested)
        case "diagnostic-tombstone": return try TombstoneFixture.evidence()
        case "diagnostic-signal": return try SignalCrashFixture.evidence()
        case "diagnostic-android":
            return try .androidExit(timestampMillis: 9_007_199_254_740_993, reasonCode: 5, status: .min,
                processID: .max, importance: 100, pssKiB: .max, rssKiB: nil)
        case "diagnostic-crash":
            return try .metricKit(intervalStartMillis: .min, intervalEndMillis: 9_007_199_254_740_993, kind: .crash,
                metadata: [.appVersion: .restore(value: "测试1", isTruncated: true), .appBuild: .capture(publicValue: "42"),
                           .osVersion: .capture(publicValue: "26.5"), .deviceModel: .capture(publicValue: "iPhone17,1")],
                signal: 11, exceptionType: .max, exceptionCode: "18446744073709551615")
        case "diagnostic-hang":
            return try .metricKit(intervalStartMillis: -1, intervalEndMillis: 0, kind: .hang, metadata: [:], hangDurationMillis: 0)
        case "delimiters-and-unicode":
            return try .metricKit(intervalStartMillis: .min, intervalEndMillis: .max, kind: .crash,
                metadata: [.appVersion: .restore(value: "a:\n汉😀\0", isTruncated: true), .appBuild: .capture(publicValue: "-\n")],
                exceptionType: 0, exceptionCode: "-9223372036854775808")
        default: throw PersistenceError.invalidRecord
        }
    }
    func testRicherMaterialAcknowledgesItsUnchangedSummary() throws {
        let rich = try TombstoneFixture.evidence(), summary = try TombstoneFixture.evidence(state: .notRequested)
        XCTAssertEqual(DiagnosticFingerprint.summaryAlias(rich), DiagnosticFingerprint.make(summary))
        XCTAssertNotEqual(DiagnosticFingerprint.make(rich), DiagnosticFingerprint.make(summary))
        XCTAssertNil(DiagnosticFingerprint.summaryAlias(summary))
        for state in SystemDiagnostic.TombstoneState.allCases where state != .available && state != .notApplicable {
            XCTAssertEqual(DiagnosticFingerprint.make(try TombstoneFixture.evidence(state: state)), DiagnosticFingerprint.make(summary))
        }
    }
    func testReceiptAccountsForEveryCandidateAndPreservesSourceLosses() throws {
        let batch = try SystemDiagnosticBatch(source: .metricKit, observedAtMillis: .min,
            diagnostics: [evidence("diagnostic-crash"), evidence("diagnostic-hang")], rejectedCount: 3, excludedCount: 4, sourceLimitReached: true)
        let receipt = try DiagnosticRecoveryReceipt(batch: batch, savedCount: 1, alreadyProcessedCount: 1,
            ignoredExitCount: 0, outsideWindowCount: 0, capacityExcludedCount: 0)
        XCTAssertEqual(receipt.source, .metricKit); XCTAssertEqual(receipt.observedAtMillis, .min)
        XCTAssertEqual(receipt.sourceRejectedCount, 3); XCTAssertEqual(receipt.sourceExcludedCount, 4); XCTAssertTrue(receipt.sourceLimitReached)
        for count in [-1, 0, 3, 33] {
            XCTAssertThrowsError(try DiagnosticRecoveryReceipt(batch: batch, savedCount: count, alreadyProcessedCount: 0,
                ignoredExitCount: 0, outsideWindowCount: 0, capacityExcludedCount: 0))
        }
    }
}
