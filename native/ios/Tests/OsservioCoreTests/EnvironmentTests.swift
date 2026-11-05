import Foundation
import OsservioCore
import XCTest

final class EnvironmentTests: XCTestCase {
    private func emptyFacts() -> [EnvironmentKey: EnvironmentFact] {
        Dictionary(uniqueKeysWithValues: EnvironmentKey.allCases.map { ($0, .unsupported) })
    }
    func testSharedValueTypesRangesPrecisionAndUnicodeFixtures() throws {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let fixture = try String(contentsOf: root.appendingPathComponent("testing/environment-values.tsv"), encoding: .utf8)
        func decode(_ input: String) throws -> String {
            if input == "~" { return "" }
            return try XCTUnwrap(String(data: XCTUnwrap(Data(base64Encoded: input)), encoding: .utf8))
        }
        for line in fixture.split(separator: "\n") {
            let row = line.split(separator: "\t").map(String.init)
            let key = try XCTUnwrap(EnvironmentKey(rawValue: row[1]))
            let raw = try decode(row[3])
            var facts = emptyFacts()
            switch row[2] {
            case "text": facts[key] = .text(raw)
            case "number": facts[key] = .number(try XCTUnwrap(Int64(raw)))
            case "flag": facts[key] = .flag(raw == "true")
            case "unavailable": facts[key] = .unavailable
            case "unsupported": facts[key] = .unsupported
            default: return XCTFail("Unknown fixture type")
            }
            if row[4] == "invalid" {
                XCTAssertThrowsError(try EnvironmentSnapshot.capture(platform: .ios, publicFacts: facts), row[0]); continue
            }
            let snapshot = try EnvironmentSnapshot.capture(platform: .ios, publicFacts: facts, timestampMillis: 123)
            let value = try XCTUnwrap(snapshot.values[key])
            let actual: String
            switch value.fact {
            case .text(let text): actual = text
            case .number(let number): actual = String(number)
            case .flag(let flag): actual = String(flag)
            case .unavailable: actual = "unavailable"
            case .unsupported: actual = "unsupported"
            }
            XCTAssertEqual(actual, try decode(row[5]), row[0])
            XCTAssertEqual(value.isTruncated, row[6] == "true", row[0])
            XCTAssertEqual(snapshot.timestampMillis, 123)
        }
    }
    func testMissingKeysFailAndCapturedFactsAreImmutable() throws {
        var facts = emptyFacts()
        facts[.appVersion] = .text("1.0")
        let snapshot = try EnvironmentSnapshot.capture(platform: .ios, publicFacts: facts)
        facts[.appVersion] = .text("2.0")
        guard case .text(let version) = snapshot.values[.appVersion]?.fact else { return XCTFail("Missing version") }
        XCTAssertEqual(version, "1.0")
        facts.removeValue(forKey: .osVersion)
        XCTAssertThrowsError(try EnvironmentSnapshot.capture(platform: .ios, publicFacts: facts))
    }
}
