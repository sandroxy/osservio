import Foundation
import OsservioCore
@testable import OsservioStore
import XCTest

final class SQLiteDiagnosticRecoveryTests: XCTestCase {
    private struct Clock: LogClock {
        func monotonicMillis() -> UInt64 { 0 }
        func wallTimeMillis() -> Int64 { 1_000_000 }
    }
    private func fixture(_ body: (URL) async throws -> Void) async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("osservio-recovery-async-test-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: directory) }
        try await body(directory)
    }
    private func batch() throws -> SystemDiagnosticBatch {
        let diagnostic = try SystemDiagnostic.metricKit(intervalStartMillis: 999_000, intervalEndMillis: 1_000_000, kind: .crash,
            metadata: [.appVersion: .capture(publicValue: String(repeating: "x", count: 32))])
        return try .init(source: .metricKit, observedAtMillis: 42, diagnostics: [diagnostic, diagnostic])
    }
    private func read(_ store: SQLiteIncidentStore) async throws -> [IncidentSummary] {
        try await withCheckedContinuation { continuation in store.readIncidents { continuation.resume(returning: $0) } }.get()
    }
    private func close(_ store: SQLiteIncidentStore) async -> Result<Void, PersistenceError> {
        await withCheckedContinuation { continuation in store.close { continuation.resume(returning: $0) } }
    }
    func testAdmissionAccountingCloseAndReentryPreserveAcceptedBatch() async throws {
        try await fixture { directory in
            let options = try IncidentPersistenceOptions(pendingTextByteLimit: 64)
            let store = try SQLiteIncidentStore(directory: directory, options: options, clock: Clock())
            _ = try await read(store)
            let entered = expectation(description: "worker callback"), gate = DispatchSemaphore(value: 0)
            store.readIncidents { _ in entered.fulfill(); _ = gate.wait(timeout: .now() + 5) }
            await fulfillment(of: [entered], timeout: 5)
            defer { gate.signal() }
            let records = try batch(), saved = expectation(description: "recovered")
            store.recoverDiagnostics(records) { result in
                switch result {
                case .success(let receipt): XCTAssertEqual(receipt.savedCount, 2)
                case .failure(let error): XCTFail("Recovery failed: \(error)")
                }
                saved.fulfill()
            }
            let busy = await withCheckedContinuation { continuation in store.recoverDiagnostics(records) { continuation.resume(returning: $0) } }
            XCTAssertThrowsError(try busy.get()) { XCTAssertEqual($0 as? PersistenceError, .busy) }
            XCTAssertEqual(store.persistenceStatus()?.queuedEvents, 2); XCTAssertEqual(store.persistenceStatus()?.queuedTextBytes, 64)
            let closed = expectation(description: "closed after recovery")
            store.close { result in if case .failure(let error) = result { XCTFail("Close failed: \(error)") }; closed.fulfill() }
            gate.signal()
            await fulfillment(of: [saved, closed], timeout: 5)
            XCTAssertEqual(store.persistenceStatus()?.state, .closed); XCTAssertEqual(store.persistenceStatus()?.queuedEvents, 0)
            XCTAssertEqual(store.persistenceStatus()?.queuedTextBytes, 0); XCTAssertEqual(store.persistenceStatus()?.committedEvents, 2)
            XCTAssertEqual(store.persistenceStatus()?.discardedEvents, 2)
            let rejected = await withCheckedContinuation { continuation in store.recoverDiagnostics(records) { continuation.resume(returning: $0) } }
            XCTAssertThrowsError(try rejected.get()) { XCTAssertEqual($0 as? PersistenceError, .closed) }
            let reopened = try SQLiteIncidentStore(directory: directory, options: options, clock: Clock())
            let summaries = try await read(reopened); XCTAssertEqual(summaries.count, 2)
            let reentered = expectation(description: "recovery callback reentry")
            reopened.recoverDiagnostics(records) { result in
                switch result {
                case .success(let receipt):
                    XCTAssertEqual(receipt.savedCount, 0); XCTAssertEqual(receipt.alreadyProcessedCount, 2)
                case .failure(let error): XCTFail("Repeat failed: \(error)")
                }
                reopened.readIncidents { result in
                    switch result {
                    case .success(let values): XCTAssertEqual(values.count, 2)
                    case .failure(let error): XCTFail("Reentry failed: \(error)")
                    }
                    reentered.fulfill()
                }
            }
            await fulfillment(of: [reentered], timeout: 5)
            XCTAssertEqual(reopened.persistenceStatus()?.committedEvents, 0); XCTAssertEqual(reopened.persistenceStatus()?.discardedEvents, 2)
            try await close(reopened).get()
        }
    }
    func testRecoveryFailureClosesStoreAndAccountsForWholeBatch() async throws {
        try await fixture { directory in
            let url = directory.appendingPathComponent("incidents.sqlite")
            let initial = try IncidentDatabase(url: url, options: IncidentPersistenceOptions(), now: 1_000_000); initial.close()
            let raw = try SQLiteConnection(url: url)
            try raw.execute("CREATE TRIGGER reject_ack BEFORE INSERT ON diagnostic_acknowledgements BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END"); raw.close()
            let store = try SQLiteIncidentStore(directory: directory, clock: Clock())
            _ = try await read(store)
            let records = try batch()
            let result = await withCheckedContinuation { continuation in store.recoverDiagnostics(records) { continuation.resume(returning: $0) } }
            XCTAssertThrowsError(try result.get()) { XCTAssertEqual($0 as? PersistenceError, .invalidRecord) }
            XCTAssertEqual(store.persistenceStatus()?.state, .failed); XCTAssertEqual(store.persistenceStatus()?.failedBatches, 1)
            XCTAssertEqual(store.persistenceStatus()?.committedEvents, 0); XCTAssertEqual(store.persistenceStatus()?.discardedEvents, 2)
            _ = await close(store)
            let check = try SQLiteConnection(url: url); defer { check.close() }
            for table in ["incidents", "diagnostic_acknowledgements"] {
                try check.statement("SELECT count(*) FROM " + table) { XCTAssertTrue(try $0.step()); XCTAssertEqual(try $0.integer(0), 0) }
            }
        }
    }
}
