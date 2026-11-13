import Foundation
import OsservioCore
@testable import OsservioUI
import XCTest

final class InspectorFormDocumentTests: XCTestCase {
    func testSharedFieldsAndEncodingFixtures() throws {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let fixtures = try String(contentsOf: root.appendingPathComponent("testing/form-reader-cases.tsv"), encoding: .utf8)
        func decode(_ value: String) throws -> String { try XCTUnwrap(String(data: XCTUnwrap(Data(base64Encoded: value)), encoding: .utf8)) }
        for line in fixtures.split(separator: "\n") where !line.hasPrefix("#") {
            let row = line.split(separator: "\t", omittingEmptySubsequences: false).map(String.init), source = try decode(row[2])
            if row[1] == "invalid" {
                XCTAssertThrowsError(try InspectorFormDocument.parse(source), row[0]) { XCTAssertEqual($0 as? InspectorFormDocument.Failure, .invalid) }
                continue
            }
            let document = try InspectorFormDocument.parse(source)
            let expected = row[3] == "-" ? [] : row[3].split(separator: ",").map { $0.split(separator: ":", omittingEmptySubsequences: false).map(String.init) }
            XCTAssertEqual(document.fields.count, expected.count, row[0])
            for (field, value) in zip(document.fields, expected) {
                XCTAssertEqual(field.name, try decode(value[0]), row[0]); XCTAssertEqual(field.value, try decode(value[1]), row[0])
                XCTAssertEqual(field.hasEquals, value[2] == "1", row[0]); XCTAssertEqual(field.original, try decode(value[3]), row[0])
            }
        }
    }
    func testLimitsCancellationAndDecodedSearchMapping() throws {
        XCTAssertEqual(try InspectorFormDocument.parse(String(repeating: "k=v&", count: 256)).fields.count, 256)
        XCTAssertThrowsError(try InspectorFormDocument.parse(String(repeating: "k=v&", count: 257))) { XCTAssertEqual($0 as? InspectorFormDocument.Failure, .limited) }
        XCTAssertThrowsError(try InspectorFormDocument.parse(String(repeating: "x", count: 65_537))) { XCTAssertEqual($0 as? InspectorFormDocument.Failure, .limited) }
        XCTAssertThrowsError(try InspectorFormDocument.parse("a=b", cancelled: { true })) { XCTAssertEqual($0 as? InspectorFormDocument.Failure, .cancelled) }
        let document = try InspectorFormDocument.parse("a=%E4%BD%A0%E5%A5%BD&b=%F0%9F%99%82")
        let matches = InspectorTextMatches(content: document.searchText, query: "🙂")
        XCTAssertEqual(matches.ranges.count, 1)
        XCTAssertEqual(document.field(at: try XCTUnwrap(matches.ranges.first).location), 1)
        XCTAssertNil(document.field(at: -1))
    }
    func testFormRequiresUnambiguousObservedContentTypeAndSupportedCharset() {
        func header(_ value: String) -> HTTPHeader { HTTPHeader(name: "Content-Type", value: value) }
        XCTAssertTrue(InspectorHTTPContent.isForm([header("application/x-www-form-urlencoded; charset=\"UTF-8\"")]))
        XCTAssertFalse(InspectorHTTPContent.isForm([]))
        XCTAssertFalse(InspectorHTTPContent.isForm([header("application/x-www-form-urlencoded"), header("application/json")]))
        XCTAssertFalse(InspectorHTTPContent.isForm([header("application/x-www-form-urlencoded; charset=gbk")]))
        var content = InspectorReadContent(text: "a=1", allowsJSON: true); content.isForm = true
        XCTAssertTrue(content.withNoticePrefix { "Saved" }.isForm)
    }
}
