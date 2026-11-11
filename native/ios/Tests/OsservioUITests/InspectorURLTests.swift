import Foundation
@testable import OsservioUI
import XCTest

final class InspectorURLTests: XCTestCase {
    func testSharedReadableURLsKeepDelimitersAndPrivacy() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let fixture = try String(contentsOf: root.appendingPathComponent("testing/readable-urls.tsv"), encoding: .utf8)
        for line in fixture.split(separator: "\n") {
            let row = line.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
            let value = InspectorURL(row[1])
            XCTAssertTrue(value.available, row[0]); XCTAssertEqual(value.origin, row[2], row[0])
            XCTAssertEqual(value.path, row[3], row[0]); XCTAssertEqual(value.queryHidden, row[4] == "true", row[0])
            XCTAssertEqual(value.pathHidden, row[5] == "true", row[0])
        }
    }
    func testMalformedInputHasNoInventedOrigin() {
        XCTAssertFalse(InspectorURL("[unavailable]").available)
        XCTAssertEqual(InspectorURL("[unavailable]").origin, "")
        XCTAssertEqual(InspectorURL.readable("%Q0%"), "%Q0%")
    }
}
