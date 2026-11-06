import Foundation
import OsservioCore
import XCTest

final class SystemDiagnosticRecoveryTests: XCTestCase {
    // These controllable ports deliver synchronously on the test thread.
    private final class Source: SystemDiagnosticProvider, @unchecked Sendable {
        var deliveries: [@Sendable (Result<SystemDiagnosticBatch, SystemDiagnosticError>) -> Void] = []
        func read(completion: @escaping @Sendable (Result<SystemDiagnosticBatch, SystemDiagnosticError>) -> Void) { deliveries.append(completion) }
    }
    private final class Archive: DiagnosticRecoveryArchive, @unchecked Sendable {
        var batches: [SystemDiagnosticBatch] = []
        var deliveries: [@Sendable (Result<DiagnosticRecoveryReceipt, PersistenceError>) -> Void] = []
        func recoverDiagnostics(_ batch: SystemDiagnosticBatch, completion: @escaping @Sendable (Result<DiagnosticRecoveryReceipt, PersistenceError>) -> Void) {
            batches.append(batch); deliveries.append(completion)
        }
    }
    private final class Clock: LogClock, @unchecked Sendable {
        var time: UInt64 = 0
        func monotonicMillis() -> UInt64 { time }
        func wallTimeMillis() -> Int64 { 100 }
    }
    private final class ResultBox: @unchecked Sendable {
        var values: [Result<DiagnosticRecoveryReceipt, DiagnosticRecoveryError>] = []
        func complete(_ result: Result<DiagnosticRecoveryReceipt, DiagnosticRecoveryError>) { values.append(result) }
    }
    private func batch() throws -> SystemDiagnosticBatch {
        try .init(source: .metricKit, observedAtMillis: 42, diagnostics: [.metricKit(intervalStartMillis: 10, intervalEndMillis: 20, kind: .crash, metadata: [:])])
    }
    private func receipt(_ batch: SystemDiagnosticBatch) throws -> DiagnosticRecoveryReceipt {
        try .init(batch: batch, savedCount: 1, alreadyProcessedCount: 0, ignoredExitCount: 0, outsideWindowCount: 0, capacityExcludedCount: 0)
    }
    func testOffStopAndSessionReplacementRejectUnadmittedHistory() throws {
        let capture = CaptureController(), source = Source(), archive = Archive()
        let recovery = SystemDiagnosticRecovery(captureController: capture, archive: archive), result = ResultBox()
        recovery.recover(from: source, completion: { result.complete($0) })
        guard case .failure(.inactive) = result.values.last else { return XCTFail("Off must reject source reads") }
        XCTAssertTrue(source.deliveries.isEmpty)
        XCTAssertTrue(capture.setBaselineEnabled(true)); recovery.recover(from: source, completion: { result.complete($0) })
        XCTAssertTrue(capture.stopAll()); source.deliveries[0](.success(try batch()))
        guard case .failure(.inactive) = result.values.last else { return XCTFail("Stop must reject late source results") }
        XCTAssertTrue(capture.setBaselineEnabled(true)); recovery.recover(from: source, completion: { result.complete($0) })
        XCTAssertTrue(capture.stopAll()); XCTAssertTrue(capture.setBaselineEnabled(true))
        source.deliveries[1](.success(try batch()))
        guard case .failure(.captureChanged) = result.values.last else { return XCTFail("A new session cannot admit the old query") }
        XCTAssertTrue(archive.batches.isEmpty); XCTAssertEqual(result.values.count, 3)
    }
    func testBusyCloseAndLateCallbacksCannotReplayHistory() throws {
        let source = Source(), archive = Archive(), result = ResultBox(), rejected = ResultBox()
        let recovery = SystemDiagnosticRecovery(captureController: CaptureController(baselineEnabled: true), archive: archive)
        recovery.recover(from: source, completion: { result.complete($0) })
        recovery.recover(try batch(), completion: { rejected.complete($0) })
        guard case .failure(.busy) = rejected.values.last else { return XCTFail("No unbounded pending queue") }
        recovery.close(); recovery.close()
        guard case .failure(.closed) = result.values.last else { return XCTFail("Close invalidates pending reads") }
        source.deliveries[0](.success(try batch())); source.deliveries[0](.failure(.unavailable))
        XCTAssertTrue(archive.batches.isEmpty); XCTAssertEqual(result.values.count, 1)
        recovery.recover(from: source, completion: { rejected.complete($0) })
        guard case .failure(.closed) = rejected.values.last else { return XCTFail("Closed coordinator rejects future work") }
        XCTAssertEqual(source.deliveries.count, 1)
    }
    func testAcceptedPersistenceFinishesAfterStopAndCloseWithoutInventingContext() throws {
        let capture = CaptureController(baselineEnabled: true), archive = Archive(), result = ResultBox()
        let recovery = SystemDiagnosticRecovery(captureController: capture, archive: archive), records = try batch()
        recovery.recover(records, completion: { result.complete($0) })
        XCTAssertEqual(archive.batches.count, 1); XCTAssertEqual(archive.batches[0].observedAtMillis, 42)
        XCTAssertEqual(archive.batches[0].diagnostics[0].intervalEndMillis, 20)
        XCTAssertTrue(capture.stopAll()); recovery.close()
        XCTAssertTrue(result.values.isEmpty)
        let value = try receipt(records); archive.deliveries[0](.success(value)); archive.deliveries[0](.success(value))
        XCTAssertEqual(try result.values[0].get().savedCount, 1); XCTAssertEqual(result.values.count, 1)
    }
    func testTypedFailuresPermitExplicitRetryAndCompletionReentry() throws {
        let source = Source(), archive = Archive(), result = ResultBox()
        let recovery = SystemDiagnosticRecovery(captureController: CaptureController(baselineEnabled: true), archive: archive)
        recovery.recover(from: source, completion: { result.complete($0) }); source.deliveries[0](.failure(.unsupported))
        guard case .failure(.source(.unsupported)) = result.values.last else { return XCTFail("Unsupported is not empty success") }
        let records = try batch()
        recovery.recover(records) { outcome in result.complete(outcome); recovery.recover(from: source, completion: { result.complete($0) }) }
        archive.deliveries[0](.failure(.storageFull))
        guard case .failure(.persistence(.storageFull)) = result.values.last else { return XCTFail("Persistence failure must be preserved") }
        XCTAssertEqual(source.deliveries.count, 2)
        source.deliveries[1](.success(records)); source.deliveries[1](.failure(.unavailable))
        XCTAssertEqual(archive.batches.count, 2); XCTAssertEqual(result.values.count, 2)
        archive.deliveries[1](.success(try receipt(records)))
        XCTAssertEqual(try result.values.last?.get().savedCount, 1); XCTAssertEqual(result.values.count, 3)
    }
    func testDetailedExpiryAndReplacementInvalidateAnUnadmittedRead() throws {
        let clock = Clock(), capture = CaptureController(clock: clock), source = Source(), archive = Archive(), result = ResultBox()
        let recovery = SystemDiagnosticRecovery(captureController: capture, archive: archive)
        try capture.startDetailed(durationMillis: 1); recovery.recover(from: source, completion: { result.complete($0) })
        clock.time = 1; source.deliveries[0](.success(try batch()))
        guard case .failure(.inactive) = result.values.last else { return XCTFail("Expired detail must not persist") }
        XCTAssertTrue(capture.setBaselineEnabled(true)); recovery.recover(from: source, completion: { result.complete($0) })
        try capture.startDetailed(); source.deliveries[1](.success(try batch()))
        guard case .failure(.captureChanged) = result.values.last else { return XCTFail("Session replacement must be explicit") }
        XCTAssertTrue(archive.batches.isEmpty)
    }
}
