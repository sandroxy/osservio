import Foundation
import OsservioCore
import OsservioStore
import XCTest

final class HTTPPipelineTests: XCTestCase {
    private final class Sink: HTTPSink, @unchecked Sendable {
        let writeBody: (HTTPUpdate) throws -> HTTPWriteResult
        init(_ write: @escaping (HTTPUpdate) throws -> HTTPWriteResult) { writeBody = write }
        func write(_ update: HTTPUpdate) throws -> HTTPWriteResult { try writeBody(update) }
    }
    private let textHeaders = [HTTPHeader(name: "Content-Type", value: "application/json; charset=utf-8")]

    func testInspectorMaintenanceDoesNotConsumeObservationSequences() throws {
        let capture = CaptureController()
        let logs = try MemoryLogStore()
        let logger = try LogRecorder(sink: logs, captureController: capture)
        let http = try HTTPRecorder(sink: MemoryHTTPStore(), captureController: capture, privacy: HTTPPrivacyPolicy())
        XCTAssertEqual(http.maintenance(), .inactive)
        try capture.startDetailed()
        XCTAssertEqual(logger.record(.info, category: "test", message: .publicText("before")), .recorded)
        for _ in 0..<20 { XCTAssertEqual(http.maintenance(), .recorded) }
        XCTAssertEqual(logger.record(.info, category: "test", message: .publicText("after")), .recorded)
        XCTAssertEqual(logs.snapshot()?.events.map(\.sequence), [1, 2])
    }

    private func fixture(_ name: String) throws -> [[String]] {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        return try String(contentsOf: root.appendingPathComponent("testing/" + name), encoding: .utf8)
            .split(separator: "\n").map { $0.split(separator: "\t", omittingEmptySubsequences: false).map(String.init) }
    }

    private func recorder(_ store: MemoryHTTPStore, capture: CaptureController? = nil,
                          policy: HTTPPrivacyPolicy? = nil, activeLimit: Int = 32,
                          requestsPerSecond: Int = 50, lifetime: UInt64 = 120_000) throws -> HTTPRecorder {
        let controller = capture ?? CaptureController(clock: LogPipelineTests.Clock())
        try controller.startDetailed()
        return try HTTPRecorder(sink: store, captureController: controller, privacy: policy ?? HTTPPrivacyPolicy(),
                                activeLimit: activeLimit, requestsPerSecond: requestsPerSecond, exchangeLifetimeMillis: lifetime)
    }

    private func response(_ recorder: HTTPRecorder, body: Data) throws -> HTTPExchangeToken {
        let token = try XCTUnwrap(recorder.begin(method: "GET", url: "https://example.com/api").token)
        XCTAssertEqual(recorder.receiveResponse(token, statusCode: 200, headers: textHeaders), .recorded)
        XCTAssertEqual(recorder.observeBody(token, direction: .response, byteCount: Int64(body.count)) { Data(body.prefix($0)) }, .recorded)
        XCTAssertEqual(recorder.finish(token, state: .completed), .recorded)
        return token
    }

    func testSharedURLPrivacyFixtures() throws {
        for row in try fixture("http-url-cases.tsv") {
            XCTAssertEqual(row.count, 4)
            let store = try MemoryHTTPStore()
            let capture = try recorder(store, policy: HTTPPrivacyPolicy(publicURLPath: row[1] == "true"))
            XCTAssertEqual(capture.begin(method: "GET", url: row[2]).result, .recorded, row[0])
            XCTAssertEqual(store.snapshot()?.events.first?.url, row[3], row[0])
            try EvidenceRestorationTests.check(XCTUnwrap(store.snapshot()?.events.first))
        }
    }

    func testDefaultPrivacyDoesNotCopyBodiesAndSanitizesBeforeStorage() throws {
        let store = try MemoryHTTPStore()
        let capture = try recorder(store)
        let token = try XCTUnwrap(capture.begin(method: "post", url: "https://user:secret@example.com/private?token=secret", headers: [
            HTTPHeader(name: "Authorization", value: "Bearer secret"), HTTPHeader(name: "Content-Type", value: "application/json; boundary=secret"),
            HTTPHeader(name: "Content-Length", value: "123"), HTTPHeader(name: "secret-name", value: "secret")
        ]).token)
        XCTAssertEqual(capture.observeBody(token, direction: .request, byteCount: 123) { _ in XCTFail("Omitted body was read"); return Data() }, .recorded)
        XCTAssertEqual(capture.finish(token, state: .failed, failure: .connection, requestComplete: false, responseComplete: false), .recorded)
        let event = try XCTUnwrap(store.snapshot()?.events.first)
        try EvidenceRestorationTests.check(event)
        XCTAssertEqual(event.method, "POST")
        XCTAssertEqual(event.requestHeaders.map(\.value), ["[redacted]", "application/json", "123"])
        XCTAssertTrue(event.headersLimited)
        XCTAssertEqual(event.requestBody.state, .omitted)
        XCTAssertEqual(event.requestBody.observedBytes, 123)
        XCTAssertEqual(store.snapshot()?.retainedBodyBytes, 0)
        XCTAssertThrowsError(try HTTPPrivacyPolicy(publicHeaderNames: ["Authorization"]))
    }

    func testSharedBodyUTF8AndFormatFixtures() throws {
        for row in try fixture("http-body-cases.tsv") {
            XCTAssertEqual(row.count, 6)
            let store = try MemoryHTTPStore()
            let capture = try recorder(store, policy: HTTPPrivacyPolicy(bodyPolicy: .publicUTF8, bodyByteLimit: XCTUnwrap(Int(row[1]))))
            let token = try XCTUnwrap(capture.begin(method: "GET", url: "https://example.com").token)
            let bytes = try XCTUnwrap(Data(base64Encoded: row[2]))
            XCTAssertEqual(capture.receiveResponse(token, statusCode: 200, headers: [HTTPHeader(name: "Content-Type", value: row[3])]), .recorded)
            XCTAssertEqual(capture.observeBody(token, direction: .response, byteCount: Int64(bytes.count)) { Data(bytes.prefix($0)) }, .recorded)
            XCTAssertEqual(capture.finish(token, state: .completed), .recorded)
            let event = try XCTUnwrap(store.snapshot()?.events.first)
            try EvidenceRestorationTests.check(event)
            XCTAssertEqual(event.responseBody.state.rawValue, row[4], row[0])
            if let id = event.responseBody.blobID {
                guard case .available(let body) = store.body(id: id) else { XCTFail(row[0]); continue }
                XCTAssertEqual(Data(body.text.utf8).base64EncodedString(), row[5] == "~" ? "" : row[5], row[0])
                XCTAssertEqual(try HTTPBody.restore(id: body.id, text: body.text).byteCount, body.byteCount)
            } else { XCTAssertEqual(row[5], "-", row[0]) }
        }
    }

    func testClearAndEventEvictionRejectLateResponses() throws {
        let store = try MemoryHTTPStore(recordLimit: 1)
        let capture = try recorder(store)
        let first = try XCTUnwrap(capture.begin(method: "GET", url: "https://example.com/first").token)
        let before = try XCTUnwrap(store.snapshot())
        _ = try XCTUnwrap(capture.begin(method: "GET", url: "https://example.com/second").token)
        XCTAssertEqual(capture.receiveResponse(first, statusCode: 200, headers: textHeaders), .missing)
        XCTAssertEqual(capture.finish(first, state: .completed), .missing)
        XCTAssertEqual(before.events.count, 1)
        XCTAssertEqual(before.events.first?.state, .pending)
        let second = try XCTUnwrap(capture.begin(method: "GET", url: "https://example.com/third").token)
        store.clear()
        XCTAssertEqual(capture.receiveResponse(second, statusCode: 200, headers: textHeaders), .missing)
        XCTAssertEqual(capture.finish(second, state: .completed), .missing)
        XCTAssertEqual(store.snapshot()?.events.count, 0)
    }

    func testBodyEvictionPreservesMetadataAndSnapshot() throws {
        let store = try MemoryHTTPStore(bodyByteLimit: 3)
        let capture = try recorder(store, policy: HTTPPrivacyPolicy(bodyPolicy: .publicUTF8))
        let first = try response(capture, body: Data("abc".utf8))
        let before = try XCTUnwrap(store.snapshot())
        let second = try response(capture, body: Data("xyz".utf8))
        XCTAssertEqual(store.snapshot()?.events.count, 2)
        XCTAssertEqual(store.snapshot()?.retainedBodyBytes, 3)
        XCTAssertEqual(store.snapshot()?.evictedBodies, 1)
        guard case .unavailable = store.body(id: first.id + ".response") else { return XCTFail("Body must be evicted") }
        guard case .available(let body) = store.body(id: second.id + ".response") else { return XCTFail("Latest body missing") }
        XCTAssertEqual(body.text, "xyz")
        XCTAssertEqual(before.events.first?.responseBody.state, .available)
    }

    func testSessionStopReleasesObservationsAndRejectsStaleTokens() throws {
        let store = try MemoryHTTPStore()
        let controller = CaptureController(baselineEnabled: true, clock: LogPipelineTests.Clock())
        let capture = try recorder(store, capture: controller, policy: HTTPPrivacyPolicy(bodyPolicy: .publicUTF8), activeLimit: 1)
        let token = try XCTUnwrap(capture.begin(method: "POST", url: "https://example.com", headers: textHeaders).token)
        _ = capture.observeBody(token, direction: .request, byteCount: 3) { _ in Data("abc".utf8) }
        XCTAssertTrue(controller.stopDetailed())
        XCTAssertEqual(store.snapshot()?.events.first?.state, .captureEnded)
        XCTAssertNil(store.snapshot()?.events.first?.durationMillis)
        XCTAssertEqual(store.snapshot()?.retainedBodyBytes, 0)
        try controller.startDetailed()
        XCTAssertEqual(capture.receiveResponse(token, statusCode: 200, headers: textHeaders), .stale)
        XCTAssertNotNil(capture.begin(method: "GET", url: "https://example.com").token)
    }

    func testAdmissionLimitsAndLazyTimeout() throws {
        let store = try MemoryHTTPStore()
        let clock = LogPipelineTests.Clock()
        let controller = CaptureController(clock: clock)
        let capture = try recorder(store, capture: controller, activeLimit: 1, requestsPerSecond: 2, lifetime: 10)
        let first = try XCTUnwrap(capture.begin(method: "GET", url: "https://example.com").token)
        XCTAssertEqual(capture.begin(method: "GET", url: "https://example.com").result, .activeLimit)
        XCTAssertEqual(capture.begin(method: "GET", url: "https://example.com").result, .rateLimited)
        clock.now += 1_000
        XCTAssertEqual(capture.maintenance(), .recorded)
        XCTAssertEqual(store.snapshot()?.events.first?.state, .captureEnded)
        XCTAssertEqual(capture.finish(first, state: .failed, failure: .timeout), .stale)
        XCTAssertNotNil(capture.begin(method: "GET", url: "https://example.com").token)
    }

    func testInvalidOrMissingChunksNeverBecomeCompleteBodies() throws {
        let store = try MemoryHTTPStore()
        let capture = try recorder(store, policy: HTTPPrivacyPolicy(bodyPolicy: .publicUTF8))
        let token = try XCTUnwrap(capture.begin(method: "GET", url: "https://example.com").token)
        XCTAssertEqual(capture.finish(token, state: .completed), .invalid)
        XCTAssertEqual(capture.receiveResponse(token, statusCode: 600, headers: textHeaders), .invalid)
        XCTAssertEqual(capture.receiveResponse(token, statusCode: 200, headers: textHeaders), .recorded)
        XCTAssertEqual(capture.observeBody(token, direction: .response, byteCount: 4) { _ in Data("ab".utf8) }, .invalid)
        XCTAssertEqual(capture.finish(token, state: .completed), .recorded)
        XCTAssertEqual(store.snapshot()?.events.first?.responseBody.state, .incomplete)
        XCTAssertNil(store.snapshot()?.events.first?.responseBody.blobID)
        XCTAssertEqual(capture.finish(token, state: .completed), .stale)
    }

    func testPublicHeadersRemainBoundedAndCompressionRequiresDecodedBytes() throws {
        let store = try MemoryHTTPStore()
        let policy = try HTTPPrivacyPolicy(publicHeaderNames: ["X-Trace"], bodyPolicy: .publicUTF8)
        let capture = try recorder(store, policy: policy)
        let token = try XCTUnwrap(capture.begin(method: "GET", url: "https://example.com", headers: [HTTPHeader(name: "X-Trace", value: String(repeating: "😀", count: 200))]).token)
        XCTAssertEqual(store.snapshot()?.events.first?.requestHeaders.first?.value.utf8.count, 512)
        _ = capture.receiveResponse(token, statusCode: 200, headers: textHeaders + [HTTPHeader(name: "Content-Encoding", value: "gzip")])
        _ = capture.observeBody(token, direction: .response, byteCount: 4) { _ in XCTFail("Encoded body must be omitted"); return Data() }
        _ = capture.finish(token, state: .completed)
        XCTAssertEqual(store.snapshot()?.events.first?.responseBody.state, .unsupported)
    }

    func testOversizedMetadataPreservesHistory() throws {
        let store = try MemoryHTTPStore(textByteLimit: 100)
        let capture = try recorder(store, policy: HTTPPrivacyPolicy(publicURLPath: true))
        XCTAssertEqual(capture.begin(method: "GET", url: "https://example.com/a").result, .recorded)
        XCTAssertEqual(capture.begin(method: "GET", url: "https://example.com/" + String(repeating: "x", count: 150)).result, .tooLarge)
        XCTAssertEqual(store.snapshot()?.events.count, 1)
        XCTAssertEqual(store.snapshot()?.evictedEvents, 0)
    }

    func testReentrantAndConcurrentAdmissionsAreBusyWithoutReadingBody() throws {
        let controller = CaptureController(clock: LogPipelineTests.Clock())
        try controller.startDetailed()
        let entered = DispatchSemaphore(value: 0)
        let release = DispatchSemaphore(value: 0)
        let done = expectation(description: "admission completed")
        let capture = try HTTPRecorder(sink: Sink { _ in
            XCTAssertNil(controller.status())
            entered.signal(); _ = release.wait(timeout: .now() + 5)
            return .stored
        }, captureController: controller, privacy: HTTPPrivacyPolicy())
        DispatchQueue.global().async {
            XCTAssertEqual(capture.begin(method: "GET", url: "https://example.com").result, .recorded)
            done.fulfill()
        }
        XCTAssertEqual(entered.wait(timeout: .now() + 2), .success)
        XCTAssertEqual(capture.begin(method: "GET", url: "https://example.com").result, .busy)
        XCTAssertFalse(controller.stopDetailed())
        release.signal(); wait(for: [done], timeout: 2)
    }

    func testSinkFailuresAndInvalidBodyOrderingAreExplicit() throws {
        enum Failure: Error { case synthetic }
        let controller = CaptureController(clock: LogPipelineTests.Clock())
        try controller.startDetailed()
        let failing = try HTTPRecorder(sink: Sink { _ in throw Failure.synthetic }, captureController: controller, privacy: HTTPPrivacyPolicy())
        XCTAssertEqual(failing.begin(method: "GET", url: "https://example.com").result, .sinkFailure)
        let store = try MemoryHTTPStore()
        let capture = try HTTPRecorder(sink: store, captureController: controller, privacy: HTTPPrivacyPolicy(bodyPolicy: .publicUTF8))
        let token = try XCTUnwrap(capture.begin(method: "GET", url: "https://example.com").token)
        XCTAssertEqual(capture.observeBody(token, direction: .response, byteCount: 2) { _ in XCTFail("Out-of-order copy"); return Data() }, .invalid)
        XCTAssertEqual(capture.receiveResponse(token, statusCode: 200, headers: textHeaders), .recorded)
        XCTAssertEqual(capture.finish(token, state: .completed), .recorded)
        XCTAssertEqual(store.snapshot()?.events.first?.responseBody.state, .incomplete)
    }

    func testGrowingMetadataEvictsOtherRecordsWithoutRemovingTheUpdatedOne() throws {
        let store = try MemoryHTTPStore(textByteLimit: 150)
        let capture = try recorder(store, policy: HTTPPrivacyPolicy(publicHeaderNames: ["X-Trace"]))
        let first = try XCTUnwrap(capture.begin(method: "GET", url: "https://example.com").token)
        _ = capture.begin(method: "GET", url: "https://example.com")
        XCTAssertEqual(capture.receiveResponse(first, statusCode: 200, headers: [HTTPHeader(name: "X-Trace", value: String(repeating: "a", count: 80))]), .recorded)
        XCTAssertEqual(store.snapshot()?.events.map(\.id), [first.id])
        XCTAssertLessThanOrEqual(try XCTUnwrap(store.snapshot()).retainedTextBytes, 150)
    }
}
