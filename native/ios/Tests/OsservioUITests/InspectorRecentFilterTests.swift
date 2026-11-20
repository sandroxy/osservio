import Foundation
@testable import OsservioUI
import XCTest

final class InspectorRecentFilterTests: XCTestCase {
    @MainActor private func items(_ store: InspectorRecentFilters) async -> [InspectorQuery] {
        await withCheckedContinuation { continuation in store.load { continuation.resume(returning: $0) } }
    }

    @MainActor func testRememberedCriteriaAreBoundedAndBelongToOneOwnerAndFeed() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = InspectorRecentFilters("logs", directory: directory)
        let alice = UUID(), bob = UUID()
        var query = InspectorQuery()
        query.text = "private search"
        store.update(query)
        let empty = await items(store)
        XCTAssertTrue(empty.isEmpty)
        for index in 0...10 {
            query.values[.category] = ["category-\(index)"]
            store.update(query)
        }
        let anonymous = await items(store)
        XCTAssertEqual(anonymous.count, 10)
        XCTAssertEqual(anonymous.first?.values[.category], ["category-10"])
        XCTAssertTrue(anonymous.allSatisfy { $0.text.isEmpty })
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.path))

        store.owner = alice
        let initial = await items(store)
        XCTAssertTrue(initial.isEmpty)
        query.values[.category] = ["A"]; store.update(query)
        query.values[.category] = ["B"]; store.update(query)
        query.values[.category] = ["A"]; store.update(query)
        let relaunched = InspectorRecentFilters("logs", directory: directory); relaunched.owner = alice
        let restored = await items(relaunched)
        XCTAssertEqual(restored.compactMap { $0.values[.category]?.first }, ["A", "B"])
        XCTAssertTrue(restored.allSatisfy { $0.text.isEmpty })
        store.owner = bob
        let other = await items(store)
        XCTAssertTrue(other.isEmpty)
        let network = InspectorRecentFilters("network", directory: directory); network.owner = alice
        let otherFeed = await items(network)
        XCTAssertTrue(otherFeed.isEmpty)

        relaunched.update(query, removing: true)
        query.values[.category] = [String(repeating: "x", count: 4097)]; relaunched.update(query)
        store.owner = alice
        let final = await items(store)
        XCTAssertEqual(final.compactMap { $0.values[.category]?.first }, ["B"])
    }
}
