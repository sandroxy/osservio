import Foundation
import OsservioCore
@testable import OsservioUI
import XCTest

final class InspectorHTTPContentTests: XCTestCase {
    func testSharedResponseTypeEvidence() throws {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let fixture = try String(contentsOf: root.appendingPathComponent("testing/http-content-type.tsv"), encoding: .utf8)
        for line in fixture.split(separator: "\n") {
            let row = line.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
            let values = row[1] == "-" ? [] : row[1].components(separatedBy: "|")
            let headers = values.map { HTTPHeader(name: "Content-Type", value: $0) }
            XCTAssertEqual(InspectorHTTPContent.category(headers), row[2], row[0])
        }
    }
}
