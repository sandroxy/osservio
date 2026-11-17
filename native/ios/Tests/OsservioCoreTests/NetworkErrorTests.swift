import Foundation
import OsservioCore
@testable import OsservioStore
import XCTest

final class NetworkErrorTests: XCTestCase {
    func testErrorDetailsAreAdmittedBoundedAndIncludedInFrozenAccounting() throws {
        let capture = CaptureController(debugEnabled: true), history = try MemoryHTTPStore()
        let recorder = try HTTPRecorder(sink: history, captureController: capture, privacy: HTTPPrivacyPolicy())
        let token = try XCTUnwrap(recorder.begin(method: "GET", url: "https://example.com").token)
        var reads = 0
        let source: () -> NetworkErrorInput? = {
            reads += 1
            return NetworkErrorInput(domain: "fixture.domain", code: "-1009", type: "SyntheticFailure", message: String(repeating: "例", count: 2000))
        }
        XCTAssertEqual(recorder.finish(token, state: .completed, error: source), .invalid); XCTAssertEqual(reads, 0)
        XCTAssertEqual(recorder.finish(token, state: .failed, failure: .connection, error: source), .recorded)
        let event = try XCTUnwrap(history.snapshot()?.events.first), error = try XCTUnwrap(event.error)
        XCTAssertEqual(reads, 1); XCTAssertEqual(error.domain, "fixture.domain"); XCTAssertEqual(error.code, "-1009")
        XCTAssertEqual(error.type, "SyntheticFailure"); XCTAssertEqual(error.message?.utf8.count, 4095)
        XCTAssertTrue(error.isLimited); XCTAssertFalse(error.isProcessed)
        XCTAssertEqual(event.textByteCount, history.snapshot()?.retainedTextBytes)
        let frozen = try IncidentHTTP.capture(XCTUnwrap(history.snapshot()), observedAtMillis: 1, history: history, includeBodies: false)
        XCTAssertEqual(frozen.events.first?.error?.message, error.message); XCTAssertEqual(frozen.retainedTextBytes, event.textByteCount)
        XCTAssertEqual(recorder.finish(token, state: .failed, failure: .unknown, error: source), .stale); XCTAssertEqual(reads, 1)
        let next = try XCTUnwrap(recorder.begin(method: "GET", url: "https://example.com/next").token)
        capture.close()
        XCTAssertEqual(recorder.finish(next, state: .failed, failure: .unknown, error: source), .inactive); XCTAssertEqual(reads, 1)
    }

    func testPolicyFailureCannotExposeRawErrorOrCloseText() throws {
        let policy = DiagnosticTextPolicy { input in
            switch input.field {
            case .networkErrorMessage: throw NSError(domain: "synthetic.policy", code: 1)
            case .webSocketCloseReason: return nil
            case .networkErrorCode: return "reviewed"
            default: return input.text
            }
        }
        let capture = CaptureController(debugEnabled: true), history = try MemoryWebSocketStore()
        let recorder = try WebSocketRecorder(sink: history, captureController: capture, privacy: WebSocketPrivacyPolicy(textPolicy: policy))
        let token = try XCTUnwrap(recorder.begin(url: "wss://example.com").token)
        XCTAssertEqual(recorder.lifecycle(token, kind: .closing, closeCode: 1000, closeReason: { _ in Data("synthetic private reason".utf8) }), .recorded)
        XCTAssertEqual(recorder.lifecycle(token, kind: .failed, closeCode: 1000, failure: .transport,
            error: { NetworkErrorInput(domain: "domain", code: "42", type: "type", message: "synthetic private error") },
            closeReason: { _ in Data("synthetic private reason".utf8) }), .recorded)
        let event = try XCTUnwrap(history.snapshot()?.events.last)
        XCTAssertNil(event.error?.message); XCTAssertEqual(event.error?.code, "reviewed"); XCTAssertEqual(event.error?.isProcessed, true)
        XCTAssertNil(event.closeReason?.text); XCTAssertEqual(event.closeReason?.isProcessed, true); XCTAssertEqual(event.closeCode, 1000)
        XCTAssertEqual(recorder.lifecycle(token, kind: .closed, closeCode: 1000), .stale)
        XCTAssertEqual(history.snapshot()?.events.last?.kind, .failed)
    }

    func testCloseTextKeepsScalarBoundariesAndRejectsMalformedUTF8() throws {
        for (bytes, expected) in [(Data((String(repeating: "a", count: 511) + "😀").utf8), String(repeating: "a", count: 511)),
                                  (Data(repeating: 0xff, count: 513), nil)] {
            let history = try MemoryWebSocketStore()
            let recorder = try WebSocketRecorder(sink: history, captureController: CaptureController(debugEnabled: true), privacy: WebSocketPrivacyPolicy())
            let token = try XCTUnwrap(recorder.begin(url: "wss://example.com").token)
            XCTAssertEqual(recorder.lifecycle(token, kind: .closed, closeCode: 1000, closeReason: { limit in
                XCTAssertEqual(limit, 513); return Data(bytes.prefix(limit))
            }), .recorded)
            let reason = try XCTUnwrap(history.snapshot()?.events.last?.closeReason)
            XCTAssertEqual(reason.text, expected); XCTAssertTrue(reason.isLimited); XCTAssertFalse(reason.isProcessed)
        }
    }

    func testSourceFailureIsVisibleWithoutLosingTransportOutcome() throws {
        let history = try MemoryHTTPStore()
        let recorder = try HTTPRecorder(sink: history, captureController: CaptureController(debugEnabled: true), privacy: HTTPPrivacyPolicy())
        let token = try XCTUnwrap(recorder.begin(method: "GET", url: "https://example.com").token)
        XCTAssertEqual(recorder.finish(token, state: .cancelled, error: { throw NSError(domain: "source", code: 1) }), .recorded)
        let event = try XCTUnwrap(history.snapshot()?.events.first)
        XCTAssertEqual(event.state, .cancelled); XCTAssertEqual(event.error?.isLimited, true); XCTAssertEqual(event.error?.textByteCount, 0)
        XCTAssertThrowsError(try NetworkError.restore(domain: nil, code: nil, type: nil, message: nil, isLimited: false, isProcessed: false))
        XCTAssertThrowsError(try NetworkError.restore(domain: nil, code: String(repeating: "x", count: 65), type: nil, message: nil, isLimited: false, isProcessed: false))
        XCTAssertThrowsError(try WebSocketCloseReason.restore(text: nil, isLimited: false, isProcessed: false))
    }
}
