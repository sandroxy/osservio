import Foundation
import OsservioCore
import XCTest

final class IncidentSnapshotTests: XCTestCase {
    func testSharedNotePrivacyAndUnicodeBoundaries() throws {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let fixture = try String(contentsOf: root.appendingPathComponent("testing/incident-note-cases.tsv"), encoding: .utf8)
        func decode(_ input: String) throws -> String {
            input == "~" ? "" : try XCTUnwrap(String(data: XCTUnwrap(Data(base64Encoded: input)), encoding: .utf8))
        }
        for line in fixture.split(separator: "\n") {
            let row = line.split(separator: "\t").map(String.init)
            let raw = String(repeating: try decode(row[2]), count: try XCTUnwrap(Int(row[3])))
            let expected = String(repeating: try decode(row[4]), count: try XCTUnwrap(Int(row[5]))) + (try decode(row[6]))
            let snapshot = try IncidentSnapshot.capture(note: row[1] == "private" ? .privateText(raw) : .publicText(raw))
            XCTAssertEqual(snapshot.note?.text, expected, row[0]); XCTAssertEqual(snapshot.note?.isPrivate, row[1] == "private")
            XCTAssertEqual(snapshot.note?.isTruncated, row[7] == "true"); XCTAssertLessThanOrEqual(snapshot.note?.text.utf8.count ?? 0, 4_096)
        }
    }
    private func event(_ sequence: Int64, text: String) throws -> BreadcrumbEvent {
        try BreadcrumbEvent.restore(id: UUID().uuidString, sessionID: "12345678-1234-1234-1234-123456789abc", sequence: sequence,
            timestampMillis: 100, sessionElapsedMillis: 1, kind: .hostMarker, label: "a", message: text,
            isPrivate: false, isTruncated: false, value: nil, retention: .memoryOnly)
    }
    func testSnapshotKeepsNewestBoundedContextAndOriginalTimes() throws {
        let events = try (1...5).map { try event(Int64($0), text: String(repeating: "x", count: 31)) }
        let source = BreadcrumbSnapshot(events: events, retainedTextBytes: 160, evictedCount: 7, revision: 1)
        let environment = try EnvironmentSnapshot.capture(platform: .ios,
            publicFacts: Dictionary(uniqueKeysWithValues: EnvironmentKey.allCases.map { ($0, EnvironmentFact.unavailable) }), timestampMillis: 42)
        let captured = try IncidentSnapshot.capture(environment: environment, breadcrumbs: source, breadcrumbLimit: 3, breadcrumbTextByteLimit: 64)
        XCTAssertEqual(captured.breadcrumbs?.events.map(\.id), events.suffix(2).map(\.id))
        XCTAssertEqual(captured.breadcrumbs?.events.map(\.sequence), [4, 5])
        XCTAssertEqual(captured.breadcrumbs?.omittedEventCount, 3); XCTAssertEqual(captured.breadcrumbs?.sourceEvictedCount, 7)
        XCTAssertEqual(captured.breadcrumbs?.retainedTextBytes, 64); XCTAssertEqual(captured.environment?.timestampMillis, 42)
        XCTAssertNil(captured.note); XCTAssertEqual(source.events.count, 5)
        let restored = try IncidentSnapshot.restore(id: captured.id, timestampMillis: captured.timestampMillis,
            note: captured.note, environment: captured.environment, breadcrumbs: captured.breadcrumbs)
        XCTAssertEqual(restored.id, captured.id); XCTAssertEqual(restored.timestampMillis, captured.timestampMillis)
        XCTAssertNil(try IncidentSnapshot.capture().breadcrumbs)
        let empty = try IncidentSnapshot.capture(breadcrumbs: BreadcrumbSnapshot(events: [], retainedTextBytes: 0, evictedCount: 0, revision: 0))
        XCTAssertEqual(empty.breadcrumbs?.sourceEventCount, 0)
    }
    func testInvalidRestorationAndConfigurationAreRejected() throws {
        XCTAssertThrowsError(try IncidentSnapshot.capture(breadcrumbLimit: 0))
        XCTAssertThrowsError(try IncidentNote.restore(text: "raw-secret", isPrivate: true, isTruncated: false))
        XCTAssertThrowsError(try IncidentNote.restore(text: "[private]", isPrivate: true, isTruncated: true))
        XCTAssertThrowsError(try IncidentNote.restore(text: String(repeating: "x", count: 4_097), isPrivate: false, isTruncated: true))
        XCTAssertThrowsError(try IncidentSnapshot.restore(id: "1-1-1-1-1", timestampMillis: 0, note: nil, environment: nil, breadcrumbs: nil))
        let value = try event(1, text: "value")
        XCTAssertThrowsError(try IncidentBreadcrumbs.restore(events: [value, value], sourceEventCount: 2, sourceEvictedCount: 0))
        XCTAssertThrowsError(try IncidentBreadcrumbs.restore(events: [value], sourceEventCount: 0, sourceEvictedCount: 0))
        XCTAssertThrowsError(try IncidentBreadcrumbs.restore(events: [], sourceEventCount: 0, sourceEvictedCount: -1))
    }
    func testEnvironmentRestorationPreservesFlagsAndRejectsRepair() throws {
        var facts = Dictionary(uniqueKeysWithValues: EnvironmentKey.allCases.map { ($0, EnvironmentFact.unavailable) })
        facts[.appVersion] = .text("version-prefix")
        facts[.physicalMemoryBytes] = .number(Int64.max)
        let restored = try EnvironmentSnapshot.restore(platform: .ios, publicFacts: facts, truncatedKeys: [.appVersion], timestampMillis: Int64.min)
        XCTAssertTrue(restored.values[.appVersion]?.isTruncated == true)
        XCTAssertEqual(restored.timestampMillis, Int64.min)
        if case .number(let number) = restored.values[.physicalMemoryBytes]?.fact { XCTAssertEqual(number, Int64.max) } else { XCTFail("Missing numeric fact") }
        XCTAssertThrowsError(try EnvironmentSnapshot.restore(platform: .ios, publicFacts: facts, truncatedKeys: [.physicalMemoryBytes], timestampMillis: 0))
        XCTAssertThrowsError(try EnvironmentSnapshot.restore(platform: .ios, publicFacts: facts, truncatedKeys: [.osVersion], timestampMillis: 0))
        facts[.appVersion] = .text(String(repeating: "x", count: 257))
        XCTAssertThrowsError(try EnvironmentSnapshot.restore(platform: .ios, publicFacts: facts, truncatedKeys: [.appVersion], timestampMillis: 0))
    }
}
