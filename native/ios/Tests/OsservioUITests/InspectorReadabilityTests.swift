import Foundation
import OsservioCore
@testable import OsservioUI
import XCTest

final class InspectorReadabilityTests: XCTestCase {
    private let id = "12345678-1234-1234-1234-123456789abc"
    func testHTTPShowsReadableAddressWithoutChangingEvidence() throws {
        let url = "https://example.test:8443/%E6%B6%88%E6%81%AF?[redacted]"
        let event = try HTTPEvent.restore(id: id, sessionID: id, sequence: 1, revision: 2, timestampMillis: 123,
            method: "POST", source: .host, url: url, responseURL: nil, requestHeaders: [], responseHeaders: [], headersLimited: false,
            statusCode: 503, state: .completed, failure: nil, durationMillis: 42,
            requestBody: .restore(state: .omitted, blobID: nil, observedBytes: 0), responseBody: .restore(state: .omitted, blobID: nil, observedBytes: 0))
        let row = InspectorRecordPresentation.http(event)
        XCTAssertEqual(row.title, "/消息"); XCTAssertEqual(row.badge, "POST · 503")
        XCTAssertTrue(row.subtitle.contains("example.test:8443")); XCTAssertTrue(row.subtitle.contains("42 ms"))
        XCTAssertFalse(row.subtitle.contains("503")); XCTAssertFalse(row.title.contains("redacted"))
        XCTAssertEqual(row.tone, .error); XCTAssertEqual(event.url, url)
        XCTAssertTrue(HTTPPresentation.overview(event).contains(url))
    }
    func testMessagePreviewKeepsConnectionReferenceAndSourcePrecision() throws {
        let event = try WebSocketEvent.restore(id: id, connectionID: id, sessionID: id, sequence: 3, timestampMillis: 123,
            url: "wss://example.test/stream?[redacted]", source: .host, kind: .message, headers: [], headersLimited: false,
            statusCode: nil, closeCode: nil, failure: nil, direction: .incoming, outcome: .received, relatedMessageID: nil,
            payload: .restore(state: .available, blobID: id, byteCount: 2_048))
        let row = InspectorRecordPresentation.socket(event, preview: "message content")
        XCTAssertEqual(row.title, "message content"); XCTAssertTrue(row.subtitle.contains("2.0 KiB"))
        XCTAssertTrue(row.subtitle.contains(InspectorRecordPresentation.connection(id)))
        XCTAssertFalse(row.subtitle.contains("#3")); XCTAssertTrue(InspectorRecordPresentation.context(event).contains(".123"))
        XCTAssertFalse(InspectorRecordPresentation.socket(event, preview: "message content", scoped: true).subtitle.contains("example.test"))
        XCTAssertNotEqual(InspectorRecordPresentation.identity(id), InspectorRecordPresentation.identity("12345678-1234-1234-1234-123456789abd"))
    }
    func testSavedUnavailableContentDoesNotClaimAvailableOrBecomeCopyText() throws {
        let reference = try HTTPBodyReference.restore(state: .available, blobID: id + ".response", observedBytes: 7)
        for state in [IncidentPayloadState.notSelected, .unavailable, .busy, .budgetExceeded] {
            let payload = try IncidentPayload.restore(id: id + ".response", state: state, text: nil)
            let value = HTTPPresentation.body(reference, savedPayloads: [payload.id: payload])
            XCTAssertFalse(value.isAvailable); XCTAssertEqual(value.text, ""); XCTAssertFalse(value.allowsJSON)
            XCTAssertEqual(value.notice, IncidentPayloadPresentation.content(payload))
        }
    }
}
