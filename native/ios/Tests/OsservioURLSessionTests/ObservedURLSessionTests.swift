import Foundation
import OsservioCore
import OsservioStore
import OsservioURLSession
import XCTest

final class ObservedURLSessionTests: XCTestCase {
    private final class StubProtocol: URLProtocol, @unchecked Sendable {
        override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "osservio.test" }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
        override func stopLoading() {}
        override func startLoading() {
            guard let url = request.url else { return }
            switch url.path {
            case "/hold": return
            case "/failure":
                client?.urlProtocol(self, didFailWithError: NSError(domain: NSURLErrorDomain, code: NSURLErrorNotConnectedToInternet,
                                                                  userInfo: [NSLocalizedDescriptionKey: "synthetic private detail"]))
            case "/redirect":
                let destination = URL(string: "https://osservio.test/destination?secret=value")!
                let response = HTTPURLResponse(url: url, statusCode: 302, httpVersion: "HTTP/1.1", headerFields: ["Location": destination.absoluteString])!
                client?.urlProtocol(self, wasRedirectedTo: URLRequest(url: destination), redirectResponse: response)
            default:
                let response: URLResponse
                if url.path == "/non-http-response" {
                    response = URLResponse(url: url, mimeType: "text/plain", expectedContentLength: 21, textEncodingName: "utf-8")
                } else {
                    response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1",
                                               headerFields: ["Content-Type": "text/plain; charset=utf-8", "Set-Cookie": "synthetic-secret"])!
                }
                client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
                client?.urlProtocol(self, didLoad: Data("response-from-fixture".utf8))
                client?.urlProtocolDidFinishLoading(self)
            }
        }
    }

    private final class RedirectDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
        private let lock = NSLock()
        private var count = 0
        var redirects: Int { lock.lock(); defer { lock.unlock() }; return count }
        func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
            lock.lock(); count += 1; lock.unlock()
            completionHandler(URLRequest(url: URL(string: "https://osservio.test/alternate?secret=value")!))
        }
    }

    private final class CountingInputStream: InputStream, @unchecked Sendable {
        private let lock = NSLock()
        private var count = 0
        var reads: Int { lock.lock(); defer { lock.unlock() }; return count }
        override func read(_ buffer: UnsafeMutablePointer<UInt8>, maxLength len: Int) -> Int {
            lock.lock(); count += 1; lock.unlock()
            return super.read(buffer, maxLength: len)
        }
    }

    private var sessions: [URLSession] = []
    private var store: MemoryHTTPStore!
    private var controller: CaptureController!
    private var recorder: HTTPRecorder!

    override func setUpWithError() throws {
        store = try MemoryHTTPStore()
        controller = CaptureController()
        try controller.startDetailed()
        recorder = try HTTPRecorder(sink: store, captureController: controller,
                                    privacy: HTTPPrivacyPolicy(publicURLPath: true, bodyPolicy: .publicUTF8, bodyByteLimit: 8))
    }
    override func tearDown() {
        sessions.forEach { $0.invalidateAndCancel() }
        sessions.removeAll()
    }
    private func observed(delegate: (any URLSessionDelegate)? = nil) -> ObservedURLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubProtocol.self]
        configuration.timeoutIntervalForRequest = 2
        let session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
        sessions.append(session)
        return ObservedURLSession(session: session, recorder: recorder)
    }
    private func request(_ path: String = "/success?token=synthetic") -> URLRequest {
        URLRequest(url: URL(string: "https://osservio.test" + path)!)
    }
    private func onlyEvent() throws -> HTTPEvent {
        let snapshot = try XCTUnwrap(store.snapshot())
        XCTAssertEqual(snapshot.events.count, 1)
        return try XCTUnwrap(snapshot.events.first)
    }

    func testTaskRemainsSuspendedAndCompletionReceivesFullOriginalData() throws {
        let observed = observed()
        var request = request()
        request.httpMethod = "POST"
        request.setValue("text/plain; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer synthetic-only", forHTTPHeaderField: "Authorization")
        request.httpBody = Data("request-from-host".utf8)
        let done = expectation(description: "completion")
        let task = observed.dataTask(with: request) { data, response, error in
            XCTAssertEqual(data, Data("response-from-fixture".utf8))
            XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
            XCTAssertNil(error)
            done.fulfill()
        }
        XCTAssertEqual(task.state, .suspended)
        XCTAssertEqual(try onlyEvent().state, .pending)
        task.resume()
        wait(for: [done], timeout: 3)
        let event = try onlyEvent()
        XCTAssertEqual(event.source, .urlSession)
        XCTAssertEqual(event.state, .completed)
        XCTAssertTrue(event.url.hasSuffix("/success?[redacted]"))
        XCTAssertTrue(try XCTUnwrap(event.responseURL).hasSuffix("/success?[redacted]"))
        XCTAssertEqual(event.requestBody.state, .truncated)
        XCTAssertEqual(event.responseBody.state, .truncated)
        guard case .available(let body) = store.body(id: try XCTUnwrap(event.responseBody.blobID)) else { return XCTFail("Missing body") }
        XCTAssertEqual(body.text, "response")
        XCTAssertEqual(event.requestHeaders.first { $0.name == "authorization" }?.value, "[redacted]")
        XCTAssertEqual(event.responseHeaders.first { $0.name == "set-cookie" }?.value, "[redacted]")
    }

    func testNetworkErrorReachesHostUnchangedWithoutRecordingItsDescription() throws {
        let done = expectation(description: "failure")
        observed().dataTask(with: request("/failure")) { data, _, error in
            XCTAssertNil(data)
            let native = error as NSError?
            XCTAssertEqual(native?.domain, NSURLErrorDomain)
            XCTAssertEqual(native?.code, NSURLErrorNotConnectedToInternet)
            XCTAssertEqual(native?.localizedDescription, "synthetic private detail")
            done.fulfill()
        }.resume()
        wait(for: [done], timeout: 3)
        XCTAssertEqual(try onlyEvent().state, .failed)
        XCTAssertEqual(try onlyEvent().failure, .connection)
        XCTAssertNil(try onlyEvent().responseBody.blobID)
    }

    func testHostCancellationStillInvokesCompletion() throws {
        let done = expectation(description: "cancellation")
        let task = observed().dataTask(with: request("/hold")) { _, _, error in
            XCTAssertEqual((error as NSError?)?.code, NSURLErrorCancelled)
            done.fulfill()
        }
        task.resume(); task.cancel()
        wait(for: [done], timeout: 3)
        XCTAssertEqual(try onlyEvent().state, .cancelled)
    }

    func testInputStreamIsNeverReadForInspection() throws {
        let stream = CountingInputStream(data: Data("streamed-request".utf8))
        var request = request()
        request.httpMethod = "POST"
        request.httpBodyStream = stream
        request.setValue("text/plain", forHTTPHeaderField: "Content-Type")
        let done = expectation(description: "stream request")
        let task = observed().dataTask(with: request) { data, _, error in
            XCTAssertEqual(data, Data("response-from-fixture".utf8)); XCTAssertNil(error)
            done.fulfill()
        }
        XCTAssertEqual(stream.reads, 0)
        task.resume()
        wait(for: [done], timeout: 3)
        XCTAssertEqual(try onlyEvent().requestBody.state, .incomplete)
        XCTAssertNil(try onlyEvent().requestBody.blobID)
    }

    func testStoppingCaptureDoesNotCancelTheTaskOrReplaceItsCallback() throws {
        let done = expectation(description: "result after capture stop")
        let task = observed().dataTask(with: request()) { data, _, error in
            XCTAssertEqual(data, Data("response-from-fixture".utf8)); XCTAssertNil(error)
            done.fulfill()
        }
        XCTAssertTrue(controller.stopDetailed())
        try controller.startDetailed()
        task.resume()
        wait(for: [done], timeout: 3)
        XCTAssertEqual(try onlyEvent().state, .captureEnded)
        XCTAssertNil(try onlyEvent().responseBody.blobID)
    }

    func testCaptureOffPreservesResultsWithoutCreatingRecords() {
        XCTAssertTrue(controller.stopAll())
        let done = expectation(description: "disabled capture")
        observed().dataTask(with: request()) { data, _, error in
            XCTAssertEqual(data, Data("response-from-fixture".utf8)); XCTAssertNil(error)
            done.fulfill()
        }.resume()
        wait(for: [done], timeout: 3)
        XCTAssertEqual(store.snapshot()?.events.count, 0)
    }

    func testNonHTTPResponseEndsObservationWithoutInventingATransportFailure() throws {
        let done = expectation(description: "non-HTTP response")
        observed().dataTask(with: request("/non-http-response")) { data, _, error in
            XCTAssertEqual(data, Data("response-from-fixture".utf8)); XCTAssertNil(error)
            done.fulfill()
        }.resume()
        wait(for: [done], timeout: 3)
        XCTAssertEqual(try onlyEvent().state, .captureEnded)
        XCTAssertNil(try onlyEvent().failure)
    }

    func testExistingDelegateKeepsControlOfRedirects() throws {
        let delegate = RedirectDelegate()
        let done = expectation(description: "redirect")
        observed(delegate: delegate).dataTask(with: request("/redirect")) { data, response, error in
            XCTAssertEqual(data, Data("response-from-fixture".utf8)); XCTAssertNil(error)
            XCTAssertEqual(response?.url?.path, "/alternate")
            done.fulfill()
        }.resume()
        wait(for: [done], timeout: 3)
        XCTAssertEqual(delegate.redirects, 1)
        XCTAssertTrue(try XCTUnwrap(try onlyEvent().responseURL).hasSuffix("/alternate?[redacted]"))
    }

    func testAsyncDataPreservesResultAndCancellation() async throws {
        if #available(macOS 12.0, iOS 15.0, *) {
            let observed = observed()
            let (data, response) = try await observed.data(for: request())
            XCTAssertEqual(data, Data("response-from-fixture".utf8))
            XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
            XCTAssertEqual(try onlyEvent().state, .completed)
            store.clear()
            let request = request("/hold")
            let pending = Task { try await observed.data(for: request) }
            pending.cancel()
            do { _ = try await pending.value; XCTFail("Cancelled request succeeded") }
            catch { XCTAssertEqual((error as NSError).code, NSURLErrorCancelled) }
            XCTAssertEqual(try onlyEvent().state, .cancelled)
        }
    }
}
