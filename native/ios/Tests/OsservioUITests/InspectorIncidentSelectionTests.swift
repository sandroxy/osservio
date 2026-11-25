import OsservioCore
@testable import OsservioUI
import XCTest

final class InspectorIncidentSelectionTests: XCTestCase {
    private let id = "12345678-1234-1234-1234-123456789abc"
    func testByteLimitIncludesCategoryAndNeverTruncatesSelectedText() throws {
        let text = String(repeating: "é", count: 32_767)
        let event = try LogEvent.restore(sessionID: id, sequence: 1, timestampMillis: 100, level: .error,
            category: "ab", message: text, isPrivate: false, isTruncated: false)
        let mark = try InspectorIncidentSelection.log(event).mark(breadcrumbs: nil)
        XCTAssertEqual(mark.logs?.retainedTextBytes, 65_536)
        XCTAssertEqual(mark.logs?.events.first?.message, text)
        XCTAssertEqual(mark.focus?.matches(event), true)
        let oversized = try LogEvent.restore(sessionID: id, sequence: 2, timestampMillis: 101, level: .error,
            category: "abc", message: text, isPrivate: false, isTruncated: false)
        XCTAssertThrowsError(try InspectorIncidentSelection.log(oversized).mark(breadcrumbs: nil)) {
            guard case InspectorIncidentSelection.PreparationError.tooLarge = $0 else { return XCTFail("Unexpected error: \($0)") }
        }
    }
    func testSelectedLogPreservesPrivacyAndDefersDetailsCopy() throws {
        let event = try LogEvent.restore(sessionID: id, sequence: 1, timestampMillis: 100, level: .info,
            category: "fixture", message: "[private]", isPrivate: true, isTruncated: false,
            details: .restore(state: .available, blobID: id, textByteCount: 3))
        let mark = try InspectorIncidentSelection.log(event).mark(breadcrumbs: nil)
        XCTAssertEqual(mark.logs?.events.first?.isPrivate, true)
        XCTAssertEqual(mark.logs?.events.first?.message, "[private]")
        XCTAssertEqual(mark.logs?.payloads[id]?.state, .notSelected)
        XCTAssertNil(mark.http); XCTAssertNil(mark.webSockets)
    }
}
