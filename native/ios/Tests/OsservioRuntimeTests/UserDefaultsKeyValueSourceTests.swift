import XCTest
import OsservioCore
import OsservioRuntime

final class UserDefaultsKeyValueSourceTests: XCTestCase {
    func testExactDomainScopeAndReadsDoNotChangeStoredValues() throws {
        let name = "osservio.storage.tests." + UUID().uuidString, defaults = try XCTUnwrap(UserDefaults(suiteName: "osservio.storage.tests." + UUID().uuidString))
        defer { defaults.removePersistentDomain(forName: name) }
        let original: [String: Any] = ["a:text": "{\"name\":\"示例🙂\"}", "a:empty": "", "a:flag": true,
            "a:count": 42, "a:list": ["one", "two"], "a:date": Date(timeIntervalSinceReferenceDate: 123456.123456789), "b:secret": "other account"]
        defaults.setPersistentDomain(original, forName: name)
        let source = try UserDefaultsKeyValueSource(domainName: name, keyPrefix: "a:")
        var keys = [String](); XCTAssertTrue(try source.visitKeys { keys.append($0); return true })
        XCTAssertEqual(Set(keys), Set(original.keys.filter { $0.hasPrefix("a:") }))
        XCTAssertEqual(try source.read(key: "b:secret").state, .missing)
        XCTAssertEqual(try source.read(key: "a:text").text, "{\"name\":\"示例🙂\"}")
        XCTAssertEqual(try source.read(key: "a:empty").state, .available)
        XCTAssertEqual(try source.read(key: "a:flag").kind, .boolean)
        XCTAssertEqual(try source.read(key: "a:count").kind, .integer)
        XCTAssertEqual(try source.read(key: "a:list").text, "[\"one\",\"two\"]")
        let date = try JSONSerialization.jsonObject(with: Data(source.read(key: "a:date").text.utf8)) as? [String: Any]
        XCTAssertEqual(date?["secondsSince2001UTC"] as? Double, 123456.123456789)
        XCTAssertEqual(defaults.persistentDomain(forName: name) as NSDictionary?, original as NSDictionary)
    }
    func testRegisteredDefaultsAreNotPretendedToBeStoredAndRemovedKeyIsMissing() throws {
        let name = "osservio.storage.tests." + UUID().uuidString, defaults = try XCTUnwrap(UserDefaults(suiteName: "osservio.storage.tests." + UUID().uuidString))
        defer { defaults.removePersistentDomain(forName: name) }
        defaults.register(defaults: ["fallback": "registration only"])
        let source = try UserDefaultsKeyValueSource(domainName: name, keyPrefix: "")
        XCTAssertEqual(try source.read(key: "fallback").state, .missing)
        defaults.setPersistentDomain(["key": "before"], forName: name)
        XCTAssertEqual(try source.read(key: "key").text, "before")
        defaults.removePersistentDomain(forName: name)
        XCTAssertEqual(try source.read(key: "key").state, .missing)
    }
    func testLargeCollectionsAndDataDoNotSerializeUnboundedContent() throws {
        let name = "osservio.storage.tests." + UUID().uuidString, defaults = UserDefaults.standard
        defer { defaults.removePersistentDomain(forName: name) }
        defaults.setPersistentDomain(["list": Array(repeating: "entry", count: 1025), "data": Data(count: 65_536),
            "text": String(repeating: "🙂", count: 20_000)], forName: name)
        let source = try UserDefaultsKeyValueSource(domainName: name, keyPrefix: "")
        XCTAssertEqual(try source.read(key: "list").state, .tooLarge)
        XCTAssertEqual(try source.read(key: "data").state, .tooLarge)
        let text = try source.read(key: "text"); XCTAssertTrue(text.truncated); XCTAssertEqual(text.text.utf8.count, 65_536)
    }
}
