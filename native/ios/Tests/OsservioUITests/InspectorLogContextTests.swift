import OsservioCore
@testable import OsservioUI
import XCTest

final class InspectorLogContextTests: XCTestCase {
    private let session = "12345678-1234-1234-1234-123456789abc"
    private let other = "12345678-1234-1234-1234-123456789def"
    private func event(_ session: String, _ sequence: Int64, _ message: String) throws -> LogEvent {
        try .restore(sessionID: session, sequence: sequence, timestampMillis: 100, level: .info, category: "context", message: message, isPrivate: false, isTruncated: false)
    }
    func testNeighborsIgnoreFiltersButNeverCrossCaptureSessions() throws {
        var source: [LogEvent] = []
        for index in 1...100 { source += [try event(session, Int64(index), "line \(index)"), try event(other, Int64(index), "other")] }
        let window = try XCTUnwrap(InspectorLogContext(source: source, target: source[98]))
        XCTAssertEqual(window.events.count, 41); XCTAssertEqual(window.targetIndex, 20)
        XCTAssertEqual(window.events[window.targetIndex].sequence, 50)
        XCTAssertEqual(window.events.first?.sequence, 30); XCTAssertEqual(window.events.last?.sequence, 70)
        XCTAssertTrue(window.events.allSatisfy { $0.sessionID == session })
        source.removeAll(); XCTAssertEqual(window.events.count, 41)
    }
    func testAbsentAnchorDoesNotInventContext() throws {
        XCTAssertNil(InspectorLogContext(source: [try event(other, 1, "other")], target: try event(session, 1, "target")))
    }
    func testBoundsPreserveOriginalTextAndIdentity() throws {
        let large = String(repeating: "x", count: 60_000)
        let source = try (1...7).map { try event(session, Int64($0), large) }
        let window = try XCTUnwrap(InspectorLogContext(source: source, target: source[3]))
        XCTAssertLessThanOrEqual(window.events.reduce(0) { $0 + $1.textByteCount }, InspectorLogContext.textByteLimit)
        XCTAssertEqual(window.events[window.targetIndex].sequence, 4)
        XCTAssertTrue(window.events.allSatisfy { $0.message == large })
    }
    func testEdgeAnchorAndSessionCaseKeepRecordedOrder() throws {
        let source = [try event(session.uppercased(), 1, "first"), try event(session, 2, "second")]
        let window = try XCTUnwrap(InspectorLogContext(source: source, target: try event(session, 1, "first")))
        XCTAssertEqual(window.targetIndex, 0); XCTAssertEqual(window.events.map(\.sequence), [1, 2])
    }
}
