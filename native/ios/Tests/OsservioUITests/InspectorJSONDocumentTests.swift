import Foundation
@testable import OsservioUI
import XCTest

final class InspectorJSONDocumentTests: XCTestCase {
    func testSharedGrammarFixtures() throws {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let fixture = try String(contentsOf: root.appendingPathComponent("testing/json-reader-cases.tsv"), encoding: .utf8)
        for line in fixture.split(separator: "\n") where !line.hasPrefix("#") {
            let fields = line.split(separator: "\t").map(String.init)
            let source = try XCTUnwrap(String(data: XCTUnwrap(Data(base64Encoded: fields[2])), encoding: .utf8))
            if fields[1] == "ok" { XCTAssertEqual(try InspectorJSONDocument.parse(source).source as String, source, fields[0]) }
            else {
                XCTAssertThrowsError(try InspectorJSONDocument.parse(source), fields[0]) {
                    XCTAssertEqual($0 as? InspectorJSONDocument.Failure, fields[1] == "notJSON" ? .notJSON : .invalid, fields[0])
                }
            }
        }
    }
    func testExactTokensDuplicateKeysAndIndependentExpansion() throws {
        let source = #"{"id":900719925474099312345,"id":-0,"a":{"n":1.2300e+99},"b":[{"名字":"🙂"}]}"#
        let document = try InspectorJSONDocument.parse(source)
        XCTAssertEqual(document.nodes[0].children.count, 4)
        XCTAssertEqual(document.original(1), "900719925474099312345")
        XCTAssertEqual(document.original(2), "-0")
        XCTAssertEqual(document.original(4), "1.2300e+99")
        XCTAssertEqual(document.key(1), document.key(2))
        XCTAssertEqual(document.rows(expanded: []).count, 6)
        let opened = document.rows(expanded: [3, 5, 6])
        XCTAssertEqual(Set(opened.map(\.identity)).count, opened.count)
        let hit = (source as NSString).range(of: "名字").location
        XCTAssertEqual(document.node(at: hit), 7)
        XCTAssertEqual(document.ancestors(of: 7), [0, 5, 6])
        XCTAssertEqual(document.rows(expanded: [5, 6]).filter { $0.node == 4 }.count, 0)
        XCTAssertTrue(document.token(.init(node: 5, closing: false), expanded: []).contains("{…}"))
    }
    func testBudgetsAndCancellation() throws {
        _ = try InspectorJSONDocument.parse("[\"" + String(repeating: "a", count: 65_532) + "\"]")
        expect(.limited, "[\"" + String(repeating: "a", count: 65_533) + "\"]")
        _ = try InspectorJSONDocument.parse("[" + Array(repeating: "0", count: 4_095).joined(separator: ",") + "]")
        expect(.limited, "[" + Array(repeating: "0", count: 4_096).joined(separator: ",") + "]")
        _ = try InspectorJSONDocument.parse(String(repeating: "[", count: 48) + "0" + String(repeating: "]", count: 48))
        expect(.limited, String(repeating: "[", count: 49) + "0" + String(repeating: "]", count: 49))
        var checks = 0
        XCTAssertThrowsError(try InspectorJSONDocument.parse("[\"" + String(repeating: "x", count: 32_000) + "\"]", cancelled: {
            checks += 1; return checks > 3
        })) { XCTAssertEqual($0 as? InspectorJSONDocument.Failure, .cancelled) }
        XCTAssertLessThan(checks, 6)
    }
    private func expect(_ failure: InspectorJSONDocument.Failure, _ source: String) {
        XCTAssertThrowsError(try InspectorJSONDocument.parse(source)) { XCTAssertEqual($0 as? InspectorJSONDocument.Failure, failure) }
    }
}
