import Foundation
@testable import OsservioUI
import XCTest

final class InspectorTextMatchesTests: XCTestCase {
    func testLiteralUnicodeSearchKeepsUTF16Offsets() {
        let value = InspectorTextMatches(content: "🙂 [a.b] İSTANBUL [A.B]", query: "[a.b]")
        XCTAssertEqual(value.ranges, [NSRange(location: 3, length: 5), NSRange(location: 18, length: 5)])
        XCTAssertFalse(value.limited)
        XCTAssertTrue(InspectorTextMatches(content: "anything", query: "").ranges.isEmpty)
    }
    func testExactLimitAndOverflowRemainDistinct() {
        let exact = InspectorTextMatches(content: String(repeating: "x", count: 200), query: "X")
        XCTAssertEqual(exact.ranges.count, 200); XCTAssertFalse(exact.limited)
        let overflow = InspectorTextMatches(content: String(repeating: "x", count: 201), query: "X")
        XCTAssertEqual(overflow.ranges.count, 200); XCTAssertTrue(overflow.limited)
    }
}
