import Foundation
import OsservioCore
import OsservioStore
import XCTest

final class WebSocketPipelineTests: XCTestCase {
    func testSharedLifecycleAndConnectionLimit() throws {
        let store = try MemoryWebSocketStore()
        let capture = CaptureController()
        try capture.startDetailed()
        let recorder = try WebSocketRecorder(sink: store, captureController: capture, privacy: WebSocketPrivacyPolicy(), connectionLimit: 1)
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let fixture = try String(contentsOf: root.appendingPathComponent("testing/websocket-lifecycle.tsv"), encoding: .utf8)
        var token: WebSocketToken?
        for line in fixture.split(separator: "\n") {
            let row = line.split(separator: "\t").map(String.init)
            let result: WebSocketRecordResult
            if row[1] == "connecting" {
                let begin = recorder.begin(url: "wss://example.com")
                token = begin.token; result = begin.result
                XCTAssertEqual(recorder.begin(url: "wss://example.com/other").result, .connectionLimit)
            } else {
                result = recorder.lifecycle(try XCTUnwrap(token), kind: try XCTUnwrap(WebSocketKind(rawValue: row[1])))
            }
            XCTAssertEqual(String(describing: result), row[2], row[0])
            XCTAssertEqual(store.snapshot()?.events.count, Int(row[3]), row[0])
            XCTAssertEqual(recorder.status()?.activeConnections, Int(row[4]), row[0])
        }
    }
    private func recorder(_ store: MemoryWebSocketStore, capture: CaptureController = CaptureController(),
                          publicText: Bool = false, limit: Int = 4_096, rate: Int = 100) throws -> WebSocketRecorder {
        try capture.startDetailed()
        return try WebSocketRecorder(sink: store, captureController: capture,
            privacy: WebSocketPrivacyPolicy(publicMessageText: publicText, messageByteLimit: limit), eventsPerSecond: rate)
    }
    private func begin(_ recorder: WebSocketRecorder) throws -> WebSocketToken {
        try XCTUnwrap(recorder.begin(url: "wss://user:secret@example.com/private?token=secret",
            headers: [HTTPHeader(name: "Authorization", value: "Bearer secret")]).token)
    }
    func testDefaultPrivacyAndBinaryCount() throws {
        let store = try MemoryWebSocketStore()
        let capture = try recorder(store)
        let token = try begin(capture)
        XCTAssertEqual(capture.text(token, direction: .outgoing, outcome: .queued, value: "secret"), .recorded)
        XCTAssertEqual(capture.binary(token, direction: .incoming, outcome: .received, byteCount: 1234), .recorded)
        let events = try XCTUnwrap(store.snapshot()).events
        XCTAssertEqual(events[0].headers.first?.value, "[redacted]")
        XCTAssertEqual(events[0].url, "wss://example.com/[redacted]?[redacted]")
        XCTAssertEqual(events[1].payload?.state, .omitted)
        XCTAssertNil(events[1].payload?.byteCount)
        XCTAssertNil(events[1].payload?.blobID)
        XCTAssertEqual(events[2].payload?.state, .binary)
        XCTAssertEqual(events[2].payload?.byteCount, 1234)
        XCTAssertEqual(store.snapshot()?.retainedPayloadBytes, 0)
    }
    func testSharedTextBoundaries() throws {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let fixture = try String(contentsOf: root.appendingPathComponent("testing/websocket-text-cases.tsv"), encoding: .utf8)
        func decode(_ value: String) throws -> String {
            if value == "~" { return "" }
            return try XCTUnwrap(String(data: XCTUnwrap(Data(base64Encoded: value)), encoding: .utf8))
        }
        for line in fixture.split(separator: "\n") {
            let row = line.split(separator: "\t").map(String.init)
            XCTAssertEqual(row.count, 5)
            let store = try MemoryWebSocketStore()
            let capture = try recorder(store, publicText: true, limit: XCTUnwrap(Int(row[1])))
            let token = try begin(capture)
            XCTAssertEqual(capture.text(token, direction: .incoming, outcome: .received, value: try decode(row[2])), .recorded)
            let event = try XCTUnwrap(store.snapshot()?.events.last)
            let ref = try XCTUnwrap(event.payload)
            XCTAssertEqual(ref.state, row[4] == "true" ? .truncated : .available, row[0])
            guard case .available(let payload) = store.payload(id: try XCTUnwrap(ref.blobID)) else { return XCTFail(row[0]) }
            XCTAssertEqual(payload.text, try decode(row[3]), row[0])
            XCTAssertEqual(ref.byteCount, row[4] == "true" ? nil : Int64(payload.byteCount))
        }
    }
    func testLifecycleValidationAndSessionBoundaries() throws {
        let store = try MemoryWebSocketStore()
        let capture = try recorder(store)
        let token = try begin(capture)
        XCTAssertEqual(capture.lifecycle(token, kind: .opened, statusCode: 101), .recorded)
        XCTAssertEqual(capture.lifecycle(token, kind: .opened), .invalid)
        XCTAssertEqual(capture.text(token, direction: .incoming, outcome: .queued, value: "x"), .invalid)
        XCTAssertEqual(capture.lifecycle(token, kind: .closed, closeCode: 1), .invalid)
        XCTAssertEqual(capture.lifecycle(token, kind: .failed), .invalid)
        XCTAssertTrue(capture.captureController.stopDetailed())
        XCTAssertEqual(capture.status()?.activeConnections, 0)
        XCTAssertEqual(store.snapshot()?.events.count, 2)
        try capture.captureController.startDetailed()
        XCTAssertEqual(capture.text(token, direction: .incoming, outcome: .received, value: "late"), .stale)
        let resumed = try XCTUnwrap(capture.begin(url: "wss://example.com", attached: true).token)
        XCTAssertEqual(store.snapshot()?.events.last?.kind, .attached)
        XCTAssertEqual(capture.lifecycle(resumed, kind: .closed, closeCode: 1000), .recorded)
        XCTAssertEqual(capture.status()?.activeConnections, 0)
    }
    func testRejectedHandshakeRetainsOnlySanitizedResponseMetadata() throws {
        let store = try MemoryWebSocketStore()
        let capture = try recorder(store)
        let token = try begin(capture)
        XCTAssertEqual(capture.lifecycle(token, kind: .failed, headers: [HTTPHeader(name: "Set-Cookie", value: "secret")],
            statusCode: 403, failure: .transport), .recorded)
        XCTAssertEqual(store.snapshot()?.events.last?.statusCode, 403)
        XCTAssertEqual(store.snapshot()?.events.last?.headers.first?.value, "[redacted]")
        XCTAssertEqual(capture.status()?.activeConnections, 0)
    }
    func testRateLimitedTerminalReleasesConnection() throws {
        let store = try MemoryWebSocketStore()
        let capture = try recorder(store, rate: 2)
        let token = try begin(capture)
        XCTAssertEqual(capture.text(token, direction: .outgoing, outcome: .queued, value: "x"), .recorded)
        XCTAssertEqual(capture.lifecycle(token, kind: .closed), .rateLimited)
        XCTAssertEqual(capture.status()?.activeConnections, 0)
        XCTAssertEqual(capture.status()?.rateLimitedCount, 1)
        XCTAssertEqual(capture.lifecycle(token, kind: .closed), .stale)
    }
    func testIndependentPayloadEvictionClearAndImmutableSnapshots() throws {
        let store = try MemoryWebSocketStore(recordLimit: 3, textByteLimit: 1_000, payloadByteLimit: 4)
        let capture = try recorder(store, publicText: true)
        let token = try begin(capture)
        capture.text(token, direction: .incoming, outcome: .received, value: "abcd")
        let before = try XCTUnwrap(store.snapshot())
        let firstID = try XCTUnwrap(before.events.last?.payload?.blobID)
        capture.text(token, direction: .incoming, outcome: .received, value: "efgh")
        XCTAssertEqual(store.snapshot()?.events.count, 3)
        if case .unavailable = store.payload(id: firstID) {} else { XCTFail("Payload must be independently evicted") }
        XCTAssertEqual(before.events.count, 2)
        store.clear()
        XCTAssertEqual(capture.status()?.activeConnections, 1)
        capture.text(token, direction: .incoming, outcome: .received, value: "new")
        XCTAssertEqual(store.snapshot()?.events.count, 1)
        XCTAssertEqual(store.snapshot()?.events.first?.kind, .message)
        XCTAssertEqual(before.events.count, 2)
    }
    private final class Sink: WebSocketSink, @unchecked Sendable {
        let action: (WebSocketEvent) -> WebSocketWriteResult
        init(_ action: @escaping (WebSocketEvent) -> WebSocketWriteResult) { self.action = action }
        func append(_ event: WebSocketEvent, payload: WebSocketPayload?) -> WebSocketWriteResult { action(event) }
    }
    func testContentionAndSinkFailureReleaseTerminalSlot() throws {
        let controller = CaptureController()
        try controller.startDetailed()
        let capture = try WebSocketRecorder(sink: Sink { event in
            XCTAssertNil(controller.status())
            return event.kind == .connecting ? .stored : .busy
        }, captureController: controller, privacy: WebSocketPrivacyPolicy())
        let token = try begin(capture)
        XCTAssertEqual(capture.lifecycle(token, kind: .failed, failure: .transport), .busy)
        XCTAssertEqual(capture.status()?.activeConnections, 0)
        let tiny = try recorder(MemoryWebSocketStore(textByteLimit: 1))
        XCTAssertEqual(tiny.begin(url: "wss://example.com").result, .tooLarge)
        XCTAssertEqual(tiny.status()?.activeConnections, 0)
    }
}
