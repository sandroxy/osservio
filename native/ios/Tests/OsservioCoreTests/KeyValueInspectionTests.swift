import XCTest
import OsservioCore

final class KeyValueInspectionTests: XCTestCase {
    private final class Source: KeyValueSource {
        var list: ((String) -> Bool) throws -> Bool = { visit in
            for key in ["b", "a", "a", ""] { if !visit(key) { return false } }; return true
        }
        var value: (String) throws -> KeyValueInspection.Value = { _ in .text(.string, "value") }
        func visitKeys(_ visitor: (String) -> Bool) throws -> Bool { try list(visitor) }
        func read(key: String) throws -> KeyValueInspection.Value { try value(key) }
    }
    private func inspection(_ source: Source) throws -> KeyValueInspection {
        try KeyValueInspection(owner: UUID(), registrations: [.init(domain: .init(id: "settings", name: "Settings", backend: "Preferences", scope: "account-A"), source: source)])
    }
    func testKeysAreSortedUniqueWithoutReadingValues() throws {
        let source = Source(); source.value = { _ in XCTFail("Listing keys must not read values"); return .unavailable(.missing) }
        let storage = try inspection(source); defer { storage.close() }
        let done = expectation(description: "keys")
        storage.keys(domain: "settings") { result in
            guard case .success(let keys) = result else { XCTFail(); done.fulfill(); return }
            XCTAssertEqual(keys.keys, ["", "a", "b"]); XCTAssertFalse(keys.limited); done.fulfill()
        }
        wait(for: [done], timeout: 3)
    }
    func testEnumerationStopsAtBudgetWithoutRewritingKeys() throws {
        let source = Source(); source.list = { visit in
            for index in 0..<100_000 { if !visit("key\(index)") { XCTAssertEqual(index, 2048); return false } }; return true
        }
        let storage = try inspection(source); defer { storage.close() }; let done = expectation(description: "limited")
        storage.keys(domain: "settings") { result in
            guard case .success(let keys) = result else { XCTFail(); done.fulfill(); return }
            XCTAssertEqual(keys.keys.count, 2048); XCTAssertTrue(keys.limited); done.fulfill()
        }
        wait(for: [done], timeout: 3)
        storage.read(domain: "settings", key: String(repeating: "x", count: 1025)) { result in
            guard case .failure(.invalidKey) = result else { return XCTFail() }
        }
    }
    func testUTF8BoundaryAndEmptyValue() {
        let value = KeyValueInspection.Value.text(.string, String(repeating: "a", count: 65_535) + "🙂")
        XCTAssertTrue(value.truncated); XCTAssertEqual(value.text.utf8.count, 65_535)
        XCTAssertEqual(KeyValueInspection.Value.text(.string, "").state, .available)
    }
    func testCanonicallyEquivalentKeysKeepTheirExactIdentities() throws {
        let source = Source(), expected = ["e\u{301}:key", "\u{e9}:key"]
        source.list = { visit in for key in expected { if !visit(key) { return false } }; return true }
        let storage = try inspection(source); defer { storage.close() }
        let done = expectation(description: "exact keys")
        storage.keys(domain: "settings") { result in
            guard case .success(let keys) = result else { XCTFail(); done.fulfill(); return }
            XCTAssertEqual(keys.keys.map { Data($0.utf8) }, expected.map { Data($0.utf8) }); done.fulfill()
        }
        wait(for: [done], timeout: 3)
    }
    func testCloseRejectsLateAccountResultAndBusyDoesNotQueue() throws {
        let source = Source(), entered = DispatchSemaphore(value: 0), release = DispatchSemaphore(value: 0)
        source.value = { _ in entered.signal(); XCTAssertEqual(release.wait(timeout: .now() + 3), .success); return .text(.string, "old secret") }
        let storage = try inspection(source); let done = expectation(description: "closed")
        storage.read(domain: "settings", key: "a") { result in
            if case .failure(.closed) = result {} else { XCTFail("Old owner result escaped") }; done.fulfill()
        }
        XCTAssertEqual(entered.wait(timeout: .now() + 3), .success)
        storage.read(domain: "settings", key: "b") { result in if case .failure(.busy) = result {} else { XCTFail() } }
        storage.close(); release.signal(); wait(for: [done], timeout: 3)
        storage.keys(domain: "settings") { result in if case .failure(.closed) = result {} else { XCTFail() } }
    }
    func testCancelledReadStopsAtVisitorAndDropsDelivery() throws {
        let source = Source(), entered = DispatchSemaphore(value: 0), release = DispatchSemaphore(value: 0)
        let exited = expectation(description: "stopped")
        source.list = { visit in
            entered.signal(); XCTAssertEqual(release.wait(timeout: .now() + 3), .success)
            XCTAssertFalse(visit("key")); exited.fulfill(); return false
        }
        let storage = try inspection(source); defer { storage.close() }
        let request = storage.keys(domain: "settings") { _ in XCTFail("Cancelled delivery") }
        XCTAssertEqual(entered.wait(timeout: .now() + 3), .success); request.cancel(); release.signal(); wait(for: [exited], timeout: 3)
    }
    func testFailureAndUnknownDomainRemainExplicit() throws {
        let source = Source(); source.value = { _ in throw CocoaError(.fileReadUnknown) }
        let storage = try inspection(source); defer { storage.close() }; let done = expectation(description: "failure")
        storage.read(domain: "settings", key: "a") { result in
            if case .failure(.unavailable) = result {} else { XCTFail() }; done.fulfill()
        }
        wait(for: [done], timeout: 3)
        storage.keys(domain: "other") { result in if case .failure(.unknownDomain) = result {} else { XCTFail() } }
    }
    func testCloseObservationIsCancellableAndReentrant() throws {
        let storage = try inspection(Source())
        let cancelled = storage.observeClose { XCTFail("Cancelled observer") }; cancelled.cancel()
        let done = expectation(description: "invalidated"); done.assertForOverFulfill = true
        _ = storage.observeClose { XCTAssertTrue(storage.isClosed); storage.close(); done.fulfill() }
        storage.close(); storage.close(); wait(for: [done], timeout: 1)
        let immediate = expectation(description: "already closed")
        _ = storage.observeClose { immediate.fulfill() }; wait(for: [immediate], timeout: 1)
    }
}
