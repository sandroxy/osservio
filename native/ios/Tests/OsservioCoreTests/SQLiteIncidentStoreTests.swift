import Foundation
import OsservioCore
import OsservioStore
import XCTest

final class SQLiteIncidentStoreTests: XCTestCase {
    private func fixture(_ body: (URL) async throws -> Void) async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("osservio-incident-store-test-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: directory) }
        try await body(directory)
    }
    private func summaries(_ store: SQLiteIncidentStore) async throws -> [IncidentSummary] {
        try await withCheckedContinuation { continuation in store.readIncidents { continuation.resume(returning: $0) } }.get()
    }
    private func close(_ store: SQLiteIncidentStore) async -> Result<Void, PersistenceError> {
        await withCheckedContinuation { continuation in store.close { continuation.resume(returning: $0) } }
    }
    private func save(_ snapshot: IncidentSnapshot, to store: SQLiteIncidentStore) async throws {
        try await withCheckedContinuation { continuation in store.save(snapshot) { continuation.resume(returning: $0) } }.get()
    }
    func testSaveCloseReopenAndDeletionKeepIdentityAndPrivacy() async throws {
        try await fixture { directory in
            let store = try SQLiteIncidentStore(directory: directory)
            let snapshot = try IncidentDetailFixture.snapshot()
            try await save(snapshot, to: store)
            let ready = try await summaries(store)
            XCTAssertEqual(ready.map(\.id), [snapshot.id])
            XCTAssertEqual(store.persistenceStatus()?.committedEvents, 1)
            try await close(store).get()
            let reopened = try SQLiteIncidentStore(directory: directory)
            let restored = await withCheckedContinuation { continuation in reopened.readIncident(id: snapshot.id) { continuation.resume(returning: $0) } }
            XCTAssertEqual(try restored.get()?.note?.text, "[private]")
            XCTAssertEqual(try restored.get()?.http?.payloads[IncidentDetailFixture.id(10) + ".response"]?.text, IncidentDetailFixture.body)
            XCTAssertEqual(try restored.get()?.logs?.events.count, 2)
            XCTAssertEqual(try restored.get()?.webSockets?.events.count, 5)
            let deleted = await withCheckedContinuation { continuation in reopened.deleteIncident(id: snapshot.id) { continuation.resume(returning: $0) } }
            try deleted.get()
            let missing = await withCheckedContinuation { continuation in reopened.readIncident(id: snapshot.id) { continuation.resume(returning: $0) } }
            XCTAssertNil(try missing.get())
            try await save(snapshot, to: reopened)
            let cleared = await withCheckedContinuation { continuation in reopened.deleteAllIncidents { continuation.resume(returning: $0) } }
            try cleared.get()
            let empty = try await summaries(reopened); XCTAssertTrue(empty.isEmpty)
            try await close(reopened).get()
        }
    }
    func testAccountDirectoriesStayIndependentAndCanReopenWithoutDeletion() async throws {
        try await fixture { directory in
            let a = directory.appendingPathComponent("account-a"), b = directory.appendingPathComponent("account-b")
            let first = try IncidentSnapshot.capture(note: .publicText("account A"))
            let second = try IncidentSnapshot.capture(note: .publicText("account B"))
            let old = try SQLiteIncidentStore(directory: a), next = try SQLiteIncidentStore(directory: b)
            try await save(first, to: old); try await save(second, to: next)
            let aRecords = try await summaries(old), bRecords = try await summaries(next)
            XCTAssertEqual(aRecords.map(\.id), [first.id]); XCTAssertEqual(bRecords.map(\.id), [second.id])
            let missing = await withCheckedContinuation { continuation in next.readIncident(id: first.id) { continuation.resume(returning: $0) } }
            XCTAssertNil(try missing.get()); try await close(old).get(); try await close(next).get()
            let reopened = try SQLiteIncidentStore(directory: a)
            let retained = try await summaries(reopened); XCTAssertEqual(retained.map(\.id), [first.id])
            let late = await withCheckedContinuation { continuation in old.save(second) { continuation.resume(returning: $0) } }
            XCTAssertThrowsError(try late.get()) { XCTAssertEqual($0 as? PersistenceError, .closed) }
            try await close(reopened).get()
        }
    }
    func testPendingWorkIsBoundedAndCloseWaitsForAcceptedOperations() async throws {
        try await fixture { directory in
            let store = try SQLiteIncidentStore(directory: directory, options: IncidentPersistenceOptions(pendingTextByteLimit: 64))
            _ = try await summaries(store)
            let entered = expectation(description: "worker callback")
            let gate = DispatchSemaphore(value: 0)
            store.readIncidents { _ in entered.fulfill(); _ = gate.wait(timeout: .now() + 5) }
            await fulfillment(of: [entered], timeout: 5)
            let snapshot = try IncidentSnapshot.capture(note: .publicText(String(repeating: "x", count: 32)))
            let another = try IncidentSnapshot.capture(note: .publicText(String(repeating: "y", count: 32)))
            let writes = expectation(description: "accepted saves"); writes.expectedFulfillmentCount = 2
            for value in [snapshot, another] { store.save(value) { result in
                if case .failure(let error) = result { XCTFail("Save failed: \(error)") }; writes.fulfill()
            } }
            let busy = await withCheckedContinuation { continuation in store.save(snapshot) { continuation.resume(returning: $0) } }
            XCTAssertThrowsError(try busy.get()) { XCTAssertEqual($0 as? PersistenceError, .busy) }
            XCTAssertEqual(store.persistenceStatus()?.queuedEvents, 2)
            XCTAssertEqual(store.persistenceStatus()?.queuedTextBytes, 64)
            let queries = expectation(description: "accepted queries"); queries.expectedFulfillmentCount = 2
            for _ in 0..<2 { store.readIncidents { result in
                switch result {
                case .success(let values): XCTAssertEqual(values.count, 2)
                case .failure(let error): XCTFail("Read failed: \(error)")
                }
                queries.fulfill()
            } }
            let overLimit = await withCheckedContinuation { continuation in store.readIncidents { continuation.resume(returning: $0) } }
            XCTAssertThrowsError(try overLimit.get()) { XCTAssertEqual($0 as? PersistenceError, .busy) }
            let closed = expectation(description: "closed after jobs")
            store.close { result in if case .failure(let error) = result { XCTFail("Close failed: \(error)") }; closed.fulfill() }
            gate.signal()
            await fulfillment(of: [writes, queries, closed], timeout: 5)
            XCTAssertEqual(store.persistenceStatus()?.state, .closed)
            XCTAssertEqual(store.persistenceStatus()?.queuedEvents, 0)
            XCTAssertEqual(store.persistenceStatus()?.committedEvents, 2)
            let rejected = await withCheckedContinuation { continuation in store.save(snapshot) { continuation.resume(returning: $0) } }
            XCTAssertThrowsError(try rejected.get()) { XCTAssertEqual($0 as? PersistenceError, .closed) }
        }
    }
    func testOwnershipFailureAndCorruptEvidenceNeverResetTheArchive() async throws {
        try await fixture { directory in
            let owner = try SQLiteIncidentStore(directory: directory)
            _ = try await summaries(owner)
            let contender = try SQLiteIncidentStore(directory: directory)
            do { _ = try await summaries(contender); XCTFail("Duplicate owner admitted") }
            catch { XCTAssertEqual(error as? PersistenceError, .busy) }
            XCTAssertEqual(contender.persistenceStatus()?.state, .failed)
            _ = await close(contender)
            try await close(owner).get()
            let file = directory.appendingPathComponent("incidents.sqlite")
            let damaged = Data("synthetic damaged archive".utf8); try damaged.write(to: file)
            let reader = try SQLiteIncidentStore(directory: directory)
            do { _ = try await summaries(reader); XCTFail("Corrupt evidence accepted") }
            catch { XCTAssertEqual(error as? PersistenceError, .corrupt) }
            XCTAssertEqual(try Data(contentsOf: file), damaged)
            _ = await close(reader)
        }
    }
    func testDuplicateSaveFailureDoesNotDisableArchiveAndCallbacksCanReenter() async throws {
        try await fixture { directory in
            let store = try SQLiteIncidentStore(directory: directory)
            let snapshot = try IncidentSnapshot.capture()
            try await save(snapshot, to: store)
            do { try await save(snapshot, to: store); XCTFail("Duplicate identity accepted") }
            catch { XCTAssertEqual(error as? PersistenceError, .invalidRecord) }
            XCTAssertEqual(store.persistenceStatus()?.state, .ready)
            let reentered = expectation(description: "callback reentry")
            store.readIncidents { result in
                if case .failure(let error) = result { XCTFail("Read failed: \(error)") }
                store.readIncident(id: snapshot.id) { result in
                    switch result {
                    case .success(let value): XCTAssertEqual(value?.id, snapshot.id)
                    case .failure(let error): XCTFail("Reentry failed: \(error)")
                    }
                    reentered.fulfill()
                }
            }
            await fulfillment(of: [reentered], timeout: 5)
            try await close(store).get()
        }
    }
}
