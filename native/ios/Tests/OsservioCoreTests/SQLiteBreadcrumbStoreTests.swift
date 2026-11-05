import Foundation
import OsservioCore
import OsservioStore
import XCTest

final class SQLiteBreadcrumbStoreTests: XCTestCase {
    private func fixture(_ body: (URL) async throws -> Void) async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("osservio-store-test-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: directory) }
        try await body(directory)
    }
    private func flush(_ store: SQLiteBreadcrumbStore) async throws {
        let result = await withCheckedContinuation { continuation in store.flush { continuation.resume(returning: $0) } }
        try result.get()
    }
    private func close(_ store: SQLiteBreadcrumbStore) async -> Result<Void, PersistenceError> {
        await withCheckedContinuation { continuation in store.close { continuation.resume(returning: $0) } }
    }
    private func runs(_ store: SQLiteBreadcrumbStore) async throws -> [RetainedRun] {
        try await withCheckedContinuation { continuation in store.readRuns { continuation.resume(returning: $0) } }.get()
    }
    private func recorder(_ store: SQLiteBreadcrumbStore) throws -> BreadcrumbRecorder {
        try BreadcrumbRecorder(sink: store, captureController: CaptureController(baselineEnabled: true))
    }
    func testLiveClearAndCapturePolicyStaySeparateFromCommittedEvidence() async throws {
        try await fixture { directory in
            let store = try SQLiteBreadcrumbStore(directory: directory)
            try await flush(store)
            let recorder = try recorder(store)
            XCTAssertEqual(recorder.record(.hostMarker, label: "live.only"), .recorded)
            XCTAssertEqual(recorder.record(.hostMarker, label: "keep", message: .privateText("never-written"), retention: .restartRelevant), .recorded)
            try await flush(store)
            let summaries = try await runs(store)
            XCTAssertEqual(summaries.count, 1); XCTAssertEqual(summaries.first?.eventCount, 1)
            let currentID = store.currentRunID
            store.clear()
            XCTAssertTrue(store.snapshot()?.events.isEmpty == true)
            try await close(store).get()
            XCTAssertEqual(recorder.record(.hostMarker, label: "closed"), .sinkFailure)
            let reopened = try SQLiteBreadcrumbStore(directory: directory)
            try await flush(reopened)
            let result = await withCheckedContinuation { continuation in reopened.readRun(id: currentID) { continuation.resume(returning: $0) } }
            let history = try XCTUnwrap(result.get())
            XCTAssertFalse(history.run.isCurrent)
            XCTAssertEqual(history.events.map(\.message), ["[private]"])
            XCTAssertTrue(reopened.snapshot()?.events.isEmpty == true)
            try await close(reopened).get()
        }
    }
    func testPendingEventsQueriesAndCallbackReentryStayBounded() async throws {
        try await fixture { directory in
            let options = try BreadcrumbPersistenceOptions(pendingEventLimit: 2, pendingTextByteLimit: 64, flushIntervalMillis: 5_000)
            let store = try SQLiteBreadcrumbStore(directory: directory, options: options)
            try await flush(store)
            let entered = expectation(description: "callback entered")
            let gate = DispatchSemaphore(value: 0)
            store.readRuns { result in
                if case .failure(let error) = result { XCTFail("Query failed: \(error)") }
                entered.fulfill()
                _ = gate.wait(timeout: .now() + 5)
            }
            await fulfillment(of: [entered], timeout: 5)
            let recorder = try recorder(store)
            for _ in 0..<4 { XCTAssertEqual(recorder.record(.hostMarker, label: "bounded", retention: .restartRelevant), .recorded) }
            XCTAssertEqual(store.snapshot()?.events.count, 4)
            XCTAssertEqual(store.persistenceStatus()?.queuedEvents, 2)
            XCTAssertEqual(store.persistenceStatus()?.discardedEvents, 2)
            let completed = expectation(description: "admitted queries")
            completed.expectedFulfillmentCount = 4
            for _ in 0..<4 { store.readRuns { result in
                if case .failure(let error) = result { XCTFail("Query failed: \(error)") }
                completed.fulfill()
            } }
            let busy = await withCheckedContinuation { continuation in store.readRuns { continuation.resume(returning: $0) } }
            XCTAssertThrowsError(try busy.get()) { XCTAssertEqual($0 as? PersistenceError, .busy) }
            gate.signal()
            await fulfillment(of: [completed], timeout: 5)
            // The next request is accepted after the callbacks returned their permits.
            try await flush(store)
            let summaries = try await runs(store)
            XCTAssertEqual(summaries.first?.eventCount, 2)
            XCTAssertEqual(store.persistenceStatus()?.queuedEvents, 0)
            XCTAssertEqual(store.persistenceStatus()?.committedEvents, 2)
            try await close(store).get()
        }
    }
    func testExclusiveOwnershipFailureKeepsCaptureUsableAndReleasesOnClose() async throws {
        try await fixture { directory in
            let owner = try SQLiteBreadcrumbStore(directory: directory)
            try await flush(owner)
            let contender = try SQLiteBreadcrumbStore(directory: directory)
            do { try await flush(contender); XCTFail("A second writer must not acquire the directory") }
            catch { XCTAssertEqual(error as? PersistenceError, .busy) }
            XCTAssertEqual(try recorder(contender).record(.hostMarker, label: "local", retention: .restartRelevant), .recorded)
            XCTAssertEqual(contender.snapshot()?.events.count, 1)
            XCTAssertEqual(contender.persistenceStatus()?.discardedEvents, 1)
            _ = await close(contender)
            try await close(owner).get()
            let replacement = try SQLiteBreadcrumbStore(directory: directory)
            try await flush(replacement)
            try await close(replacement).get()
        }
    }
    func testCorruptStorageDoesNotEraseTheFileOrBreakLiveRecording() async throws {
        try await fixture { directory in
            let file = directory.appendingPathComponent("breadcrumbs.sqlite")
            let invalid = Data("synthetic corrupt evidence".utf8)
            try invalid.write(to: file)
            let store = try SQLiteBreadcrumbStore(directory: directory)
            do { try await flush(store); XCTFail("Corruption must remain explicit") }
            catch { XCTAssertEqual(error as? PersistenceError, .corrupt) }
            XCTAssertEqual(try recorder(store).record(.hostMarker, label: "still.live", retention: .restartRelevant), .recorded)
            XCTAssertEqual(store.snapshot()?.events.count, 1)
            XCTAssertEqual(store.persistenceStatus()?.state, .failed)
            XCTAssertEqual(try Data(contentsOf: file), invalid)
            _ = await close(store)
        }
    }
    func testCloseCompletesAdmittedQueriesAndWritesBeforeReleasingOwnership() async throws {
        try await fixture { directory in
            let store = try SQLiteBreadcrumbStore(directory: directory)
            try await flush(store)
            XCTAssertEqual(try recorder(store).record(.hostMarker, label: "retained", retention: .restartRelevant), .recorded)
            let flushed = expectation(description: "admitted flush")
            store.flush { result in
                if case .failure(let error) = result { XCTFail("Flush failed: \(error)") }
                flushed.fulfill()
            }
            let queried = expectation(description: "admitted query")
            store.readRuns { result in
                switch result {
                case .success(let runs): XCTAssertEqual(runs.count, 1)
                case .failure(let error): XCTFail("Query failed: \(error)")
                }
                queried.fulfill()
            }
            try await close(store).get()
            await fulfillment(of: [flushed, queried], timeout: 5)
            let closed = await withCheckedContinuation { continuation in store.readRuns { continuation.resume(returning: $0) } }
            XCTAssertThrowsError(try closed.get()) { XCTAssertEqual($0 as? PersistenceError, .closed) }
            XCTAssertEqual(store.persistenceStatus()?.state, .closed)
            try await close(store).get()
        }
    }
    func testExplicitDeletionDrainsPendingWritesAndKeepsLiveHistory() async throws {
        try await fixture { directory in
            let options = try BreadcrumbPersistenceOptions(flushIntervalMillis: 5_000)
            let previous = try SQLiteBreadcrumbStore(directory: directory, options: options)
            try await flush(previous)
            XCTAssertEqual(try recorder(previous).record(.hostMarker, label: "previous", retention: .restartRelevant), .recorded)
            try await close(previous).get()
            let store = try SQLiteBreadcrumbStore(directory: directory, options: options)
            try await flush(store)
            let recording = try recorder(store)
            XCTAssertEqual(recording.record(.hostMarker, label: "current", retention: .restartRelevant), .recorded)
            let deleted: Result<Void, PersistenceError> = await withCheckedContinuation { continuation in
                store.deleteRun(id: previous.currentRunID) { continuation.resume(returning: $0) }
            }
            try deleted.get()
            let remaining = try await runs(store)
            XCTAssertEqual(remaining.map(\.id), [store.currentRunID])
            XCTAssertEqual(store.snapshot()?.events.count, 1)
            XCTAssertEqual(recording.record(.hostMarker, label: "pending", retention: .restartRelevant), .recorded)
            let cleared: Result<Void, PersistenceError> = await withCheckedContinuation { continuation in
                store.deleteAllRuns { continuation.resume(returning: $0) }
            }
            try cleared.get()
            let empty = try await runs(store)
            XCTAssertTrue(empty.isEmpty); XCTAssertEqual(store.snapshot()?.events.count, 2)
            XCTAssertEqual(recording.record(.hostMarker, label: "after.delete", retention: .restartRelevant), .recorded)
            try await flush(store)
            let recreated = await withCheckedContinuation { continuation in store.readRun(id: store.currentRunID) { continuation.resume(returning: $0) } }
            XCTAssertEqual(try recreated.get()?.events.map(\.label), ["after.delete"])
            try await close(store).get()
        }
    }
}
