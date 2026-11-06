#if canImport(UIKit) && canImport(MetricKit)
import MetricKit
import OsservioCore
@testable import OsservioRuntime
import XCTest

@available(iOS 14, *)
final class MetricDiagnosticTests: XCTestCase {
    private final class Metadata: MXMetaData {
        override var applicationBuildVersion: String { "historical-build" }
        override var osVersion: String { "historical-os" }
        override var deviceType: String { "historical-device" }
    }
    private final class Crash: MXCrashDiagnostic {
        var suppliedSignal: NSNumber? = NSNumber(value: 11)
        var suppliedType: NSNumber? = NSNumber(value: 1)
        var suppliedCode: NSNumber? = NSNumber(value: UInt64.max)
        var forbiddenReads = 0
        override var applicationVersion: String { String(repeating: "😀", count: 65) }
        override var metaData: MXMetaData { Metadata() }
        override var signal: NSNumber? { suppliedSignal }
        override var exceptionType: NSNumber? { suppliedType }
        override var exceptionCode: NSNumber? { suppliedCode }
        override var terminationReason: String? { forbiddenReads += 1; return "synthetic-secret" }
        override var virtualMemoryRegionInfo: String? { forbiddenReads += 1; return "synthetic-secret" }
        override var callStackTree: MXCallStackTree { forbiddenReads += 1; return MXCallStackTree() }
    }
    private final class Hang: MXHangDiagnostic {
        var seconds: Double = 0.25
        override var applicationVersion: String { "historical-version" }
        override var metaData: MXMetaData { Metadata() }
        override var hangDuration: Measurement<UnitDuration> { Measurement(value: seconds, unit: .seconds) }
    }
    private final class Payload: MXDiagnosticPayload {
        var crashes: [MXCrashDiagnostic]? = []
        var hangs: [MXHangDiagnostic]? = []
        var begin = Date(timeIntervalSince1970: 100.125)
        var end = Date(timeIntervalSince1970: 200.125)
        override var crashDiagnostics: [MXCrashDiagnostic]? { crashes }
        override var hangDiagnostics: [MXHangDiagnostic]? { hangs }
        override var timeStampBegin: Date { begin }
        override var timeStampEnd: Date { end }
    }
    func testAllowlistedFactsKeepReportingRangeAndHistoricalBuildWithoutReadingRawReasons() throws {
        let crash = Crash(), hang = Hang(), payload = Payload()
        payload.crashes = [crash]; payload.hangs = [hang]
        let batch = try MetricDiagnosticReader.read([payload])
        XCTAssertEqual(batch.diagnostics.count, 2); XCTAssertEqual(batch.rejectedCount, 0)
        let report = try XCTUnwrap(batch.diagnostics.first)
        XCTAssertEqual(report.kind, .crash); XCTAssertEqual(report.intervalStartMillis, 100_125)
        XCTAssertEqual(report.intervalEndMillis, 200_125)
        XCTAssertEqual(report.metricKit?.exceptionCode, "18446744073709551615")
        XCTAssertEqual(report.metricKit?.metadata[.appBuild]?.value, "historical-build")
        XCTAssertEqual(report.metricKit?.metadata[.appVersion]?.isTruncated, true)
        XCTAssertEqual(batch.diagnostics.last?.metricKit?.hangDurationMillis, 250)
        XCTAssertEqual(crash.forbiddenReads, 0)
    }
    func testBoundsInvalidNumbersAndUnavailableTimeNeverBecomeSuccessfulEvidence() throws {
        let payload = Payload(); payload.crashes = Array(repeating: Crash(), count: 40)
        let bounded = try MetricDiagnosticReader.read([payload])
        XCTAssertEqual(bounded.diagnostics.count, 32); XCTAssertTrue(bounded.sourceLimitReached)
        XCTAssertTrue(try MetricDiagnosticReader.read(Array(repeating: Payload(), count: 5)).sourceLimitReached)
        let boolean = Crash(); boolean.suppliedSignal = NSNumber(value: true)
        let floating = Crash(); floating.suppliedCode = NSNumber(value: 1.25)
        let invalidSignal = Crash(); invalidSignal.suppliedSignal = NSNumber(value: 0)
        let hang = Hang(); hang.seconds = .infinity
        payload.crashes = [boolean, floating, invalidSignal]; payload.hangs = [hang]
        let rejected = try MetricDiagnosticReader.read([payload])
        XCTAssertTrue(rejected.diagnostics.isEmpty); XCTAssertEqual(rejected.rejectedCount, 4)
        payload.crashes = [Crash()]; payload.hangs = []; payload.begin = Date(timeIntervalSince1970: 300)
        XCTAssertEqual(try MetricDiagnosticReader.read([payload]).rejectedCount, 1)
    }
    func testInvalidatedSubscriberRejectsLaterPayloads() {
        let delivered = expectation(description: "one sanitized delivery")
        delivered.assertForOverFulfill = true
        let subscriber = MetricDiagnosticSubscriber { result in
            XCTAssertEqual(try? result.get().source, .metricKit); delivered.fulfill()
        }
        subscriber.didReceive([]); subscriber.invalidate(); subscriber.didReceive([Payload()])
        wait(for: [delivered], timeout: 1)
    }
    @MainActor
    func testActualSystemQueryDoesNotRequireAnOwnedCrashHandler() async throws {
        let provider = SystemDiagnostics()
        let result = await withCheckedContinuation { continuation in provider.read { continuation.resume(returning: $0) } }
        let batch = try result.get()
        XCTAssertEqual(batch.source, .metricKit); XCTAssertLessThanOrEqual(batch.diagnostics.count, 32)
        provider.close()
        let observer = MetricDiagnosticObserver()
        observer.start { _ in }; observer.start { _ in }; observer.stop(); observer.stop()
    }
}
#endif
