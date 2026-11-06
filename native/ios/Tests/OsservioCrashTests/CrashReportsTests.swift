import Foundation
import OsservioCore
@testable import OsservioCrash
import OsservioCrashSupport
import XCTest

final class CrashReportsTests: XCTestCase {
    private func fixture(_ body: (URL) async throws -> Void) async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("osservio-report-source-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: directory) }
        try await body(directory)
    }
    private func open(_ directory: URL) async throws -> CrashReports {
        try await withCheckedContinuation { continuation in CrashReports.open(directory: directory) { continuation.resume(returning: $0) } }.get()
    }
    private func read(_ source: CrashReports) async -> Result<SystemDiagnosticBatch, SystemDiagnosticError> {
        await withCheckedContinuation { continuation in source.read { continuation.resume(returning: $0) } }
    }
    func testOpeningReadingAndReleaseAreIndependentOfRecorderInstallation() async throws {
        try await fixture { directory in
            let permission = OsservioCrashCaptureAllowsRecording(), handlers = OsservioCrashInspectHandlers()
            var source: CrashReports? = try await open(directory)
            XCTAssertNotNil(source)
            let batch = try await read(XCTUnwrap(source)).get()
            XCTAssertEqual(batch.source, .appleSignal); XCTAssertTrue(batch.diagnostics.isEmpty)
            XCTAssertEqual(OsservioCrashCaptureAllowsRecording(), permission)
            XCTAssertEqual(OsservioCrashInspectHandlers(), handlers)
            do { _ = try await open(directory); XCTFail("A second owner was admitted") }
            catch { XCTAssertEqual(error as? PersistenceError, .busy) }
            source = nil
            let reopened = try await open(directory)
            let empty = try await read(reopened).get()
            XCTAssertTrue(empty.diagnostics.isEmpty)
        }
    }
    func testReadAdmissionIsBoundedAndCallbackCanReenter() async throws {
        try await fixture { directory in
            let source = try await open(directory)
            let entered = expectation(description: "worker occupied"), gate = DispatchSemaphore(value: 0)
            source.worker.async { entered.fulfill(); _ = gate.wait(timeout: .now() + 5) }
            await fulfillment(of: [entered], timeout: 5)
            defer { gate.signal() }
            let completed = expectation(description: "read and reentry")
            source.read { result in
                XCTAssertNotNil(try? result.get())
                source.read { again in XCTAssertNotNil(try? again.get()); completed.fulfill() }
            }
            let busy = await read(source)
            XCTAssertThrowsError(try busy.get()) { XCTAssertEqual($0 as? SystemDiagnosticError, .busy) }
            gate.signal()
            await fulfillment(of: [completed], timeout: 5)
        }
    }
}
