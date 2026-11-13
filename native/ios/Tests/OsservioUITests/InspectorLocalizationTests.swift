import Foundation
@testable import OsservioUI
import XCTest

@MainActor
final class InspectorLocalizationTests: XCTestCase {
    func testPackagedTranslationsResolveThroughTheirDeclaredTable() throws {
        let original = InspectorPreferences.shared.language
        defer { InspectorPreferences.shared.language = original }
        var referenceKeys: Set<String>?
        for language in InspectorPreferences.Language.allCases {
            InspectorPreferences.shared.language = language
            var keys = Set<String>()
            for table in ["Inspector", "Localizable"] {
                let url = try XCTUnwrap(Bundle.module.url(forResource: table, withExtension: "strings", subdirectory: language.rawValue + ".lproj"))
                let values = try XCTUnwrap(PropertyListSerialization.propertyList(from: Data(contentsOf: url), format: nil) as? [String: String])
                for (key, value) in values {
                    XCTAssertTrue(keys.insert(key).inserted, "Duplicate resource: \(language.rawValue)/\(key)")
                    XCTAssertEqual(key.hasPrefix("ui_"), table == "Inspector", "Wrong table: \(language.rawValue)/\(key)")
                    XCTAssertFalse(value.isEmpty, "Empty translation: \(key)")
                    XCTAssertEqual(InspectorLocalization.text(key), value, "Lookup failed: \(language.rawValue)/\(key)")
                }
            }
            if let referenceKeys { XCTAssertEqual(keys, referenceKeys, "Language keys must match") }
            else { referenceKeys = keys }
        }
    }
}
