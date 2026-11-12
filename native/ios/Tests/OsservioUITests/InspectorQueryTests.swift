import Foundation
import OsservioCore
@testable import OsservioUI
import XCTest

final class InspectorQueryTests: XCTestCase {
    private let id = "00000000-0000-0000-0000-000000000001"

    func testUnionWithinFieldIntersectionAcrossFieldsAndText() throws {
        var query = InspectorQuery()
        query.values = [.level: ["warning", "error"], .category: ["checkout"]]
        query.text = "TIMEOUT"
        func log(_ level: LogLevel, _ category: String, _ message: String) throws -> LogEvent {
            try .restore(sessionID: id, sequence: 1, timestampMillis: 1, level: level,
                         category: category, message: message, isPrivate: false, isTruncated: false)
        }
        XCTAssertTrue(query.matches(try log(.error, "checkout", "request timeout"), now: 1))
        XCTAssertTrue(query.matches(try log(.warning, "checkout", "request timeout"), now: 1))
        XCTAssertFalse(query.matches(try log(.info, "checkout", "request timeout"), now: 1))
        XCTAssertFalse(query.matches(try log(.error, "profile", "request timeout"), now: 1))
        XCTAssertFalse(query.matches(try log(.error, "checkout", "completed"), now: 1))
        query.values[.level] = []
        XCTAssertTrue(query.matches(try log(.info, "checkout", "request timeout"), now: 1))
    }

    func testHTTPStatusAndTransportStateRemainIndependent() throws {
        let body = try HTTPBodyReference.restore(state: .omitted, blobID: nil, observedBytes: 0)
        let event = try HTTPEvent.restore(id: id, sessionID: id, sequence: 1, revision: 2, timestampMillis: 1,
            method: "POST", source: .host, url: "https://example.test/pay", responseURL: nil,
            requestHeaders: [], responseHeaders: [], headersLimited: false, statusCode: 503,
            state: .completed, failure: nil, durationMillis: 12, requestBody: body, responseBody: body)
        var query = InspectorQuery()
        query.values = [.status: ["5xx"], .state: ["completed"]]
        XCTAssertTrue(query.matches(event, now: 1))
        query.values[.state] = ["failed"]
        XCTAssertFalse(query.matches(event, now: 1))
        query.values = [.status: ["none"]]
        XCTAssertFalse(query.matches(event, now: 1))
        query.values = [.content: ["json"]]
        XCTAssertFalse(query.matches(event, now: 1), "Missing response headers cannot be inferred from request metadata or URL")
        query.values = [.content: ["json", "unknown"], .method: ["POST"]]
        XCTAssertTrue(query.matches(event, now: 1))
        query.values[.method] = ["GET"]
        XCTAssertFalse(query.matches(event, now: 1))
    }

    func testExactStatusAndFamiliesUseTheSameUnion() throws {
        let body = try HTTPBodyReference.restore(state: .omitted, blobID: nil, observedBytes: 0)
        let event = try HTTPEvent.restore(id: id, sessionID: id, sequence: 1, revision: 2, timestampMillis: 1,
            method: "POST", source: .host, url: "https://example.test/401", responseURL: nil,
            requestHeaders: [], responseHeaders: [], headersLimited: false, statusCode: 503,
            state: .completed, failure: nil, durationMillis: 12, requestBody: body, responseBody: body)
        var query = InspectorQuery(); query.select(.status, value: "401")
        XCTAssertFalse(query.matchesFacets(event, now: 1), "An address containing 401 is not a 401 response")
        query.select(.status, value: "503"); XCTAssertTrue(query.matchesFacets(event, now: 1))
        query.select(.status, value: "503"); query.select(.status, value: "5xx")
        XCTAssertTrue(query.matchesFacets(event, now: 1))
        query.select(.transport, value: "webSocket"); XCTAssertNil(query.values[.status])
    }
    func testSocketActivityKeepsMessagesSeparateFromConnectionEvents() throws {
        var query = InspectorQuery(); query.select(.transport, value: "webSocket")
        query.select(.activity, value: "messages")
        XCTAssertTrue(query.matchesFacets(try socket(sequence: 1, kind: .message), now: 10))
        XCTAssertFalse(query.matchesFacets(try socket(sequence: 2, kind: .opened), now: 10))
        query.select(.kind, value: "message"); query.select(.direction, value: "incoming"); query.select(.activity, value: "connections")
        XCTAssertTrue(query.values[.kind]?.isEmpty == true)
        XCTAssertNil(query.values[.direction])
        XCTAssertTrue(query.matchesFacets(try socket(sequence: 2, kind: .opened), now: 10))
        query.select(.searchScope, value: "headers"); query.select(.transport, value: "http")
        XCTAssertEqual(query.searchScope, "headers"); XCTAssertNil(query.values[.activity])
        query.select(.searchScope, value: "response"); query.select(.transport, value: "webSocket")
        XCTAssertEqual(query.searchScope, "all")
    }

    func testConnectionUsesLifecycleAndCountsAllRetainedEvents() throws {
        let open = try socket(sequence: 1, kind: .opened)
        let close = try socket(sequence: 3, kind: .closed)
        let message = try socket(sequence: 2, kind: .message)
        let groups = InspectorConnection.groups([close, message, open])
        XCTAssertEqual(groups.count, 1)
        XCTAssertEqual(groups[0].count, 3)
        XCTAssertEqual(groups[0].state, "closed")
        XCTAssertEqual(groups[0].first.sequence, 1)
        XCTAssertEqual(InspectorConnection.groups([message])[0].state, "unknown")
        var query = InspectorQuery(); query.values = [.state: ["failed"]]
        XCTAssertFalse(query.matches(groups[0], now: 3))
    }

    func testTimeWindowAndURLDisplayKeepExactPathAndQuery() {
        var query = InspectorQuery(); query.recentMinutes = 5
        XCTAssertTrue(query.includesTime(0, now: 300_000))
        XCTAssertFalse(query.includesTime(-1, now: 300_000))
        XCTAssertFalse(query.includesTime(300_001, now: 300_000))
        XCTAssertTrue(query.includesTime(.min, now: .min))
        XCTAssertEqual(InspectorQuery.host("https://API.example.test/a%2Fb?q=a%26b"), "api.example.test")
        XCTAssertEqual(InspectorQuery.path("https://API.example.test/a%2Fb?q=a%26b"), "/a%2Fb?q=a%26b")
    }

    func testSameMillisecondTimelineKeepsCloseAfterMessage() throws {
        let open = try socket(sequence: 1, kind: .opened, timestamp: 123)
        let message = try socket(sequence: 2, kind: .message, timestamp: 123)
        let close = try socket(sequence: 3, kind: .closed, timestamp: 123)
        XCTAssertEqual([message, open, close].sorted(by: InspectorQuery.timelineNewestFirst).map(\.sequence), [3, 2, 1])
    }

    func testPreferencesDefaultAndPersistWithoutChangingHostPreferences() throws {
        let suite = "osservio.tests.preferences.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set(["ja"], forKey: "AppleLanguages")
        let preferences = InspectorPreferences(defaults: defaults)
        XCTAssertEqual(preferences.language, .chinese)
        XCTAssertEqual(preferences.appearance, .light)
        XCTAssertEqual(preferences.palette, .purple)
        preferences.language = .english; preferences.appearance = .dark; preferences.palette = .teal
        let reopened = InspectorPreferences(defaults: defaults)
        XCTAssertEqual(reopened.language, .english)
        XCTAssertEqual(reopened.appearance, .dark)
        XCTAssertEqual(reopened.palette, .teal)
        XCTAssertEqual(defaults.stringArray(forKey: "AppleLanguages"), ["ja"])
    }

    private func socket(sequence: Int64, kind: WebSocketKind, timestamp: Int64? = nil) throws -> WebSocketEvent {
        let message = kind == .message
        return try .restore(id: String(format: "00000000-0000-0000-0000-%012lld", sequence), connectionID: id,
            sessionID: id, sequence: sequence, timestampMillis: timestamp ?? sequence, url: "wss://example.test/ws", source: .host,
            kind: kind, headers: [], headersLimited: false, statusCode: nil, closeCode: nil, failure: nil,
            direction: message ? .incoming : nil, outcome: message ? .received : nil, relatedMessageID: nil,
            payload: message ? .restore(state: .omitted, blobID: nil, byteCount: nil) : nil)
    }
}
