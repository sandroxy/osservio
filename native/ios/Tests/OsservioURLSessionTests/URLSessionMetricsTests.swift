import Foundation
import Network
import OsservioStore
import OsservioCore
@testable import OsservioURLSession
import XCTest

final class URLSessionMetricsTests: XCTestCase {
    private final class Host: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
        private let lock = NSLock()
        private var count = 0
        var calls: Int { lock.lock(); defer { lock.unlock() }; return count }
        func urlSession(_ session: URLSession, task: URLSessionTask, didFinishCollecting metrics: URLSessionTaskMetrics) { lock.lock(); count += 1; lock.unlock() }
        func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
    }
    @available(iOS 15.0, macOS 12.0, *)
    func testRealLoopbackMetricsReachBothHostAndTerminalRecordAndCanBeDisabled() throws {
        let server = try HTTPServer(); defer { server.stop() }
        let host = Host(), session = URLSession(configuration: .ephemeral, delegate: host, delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let history = try MemoryHTTPStore(), capture = CaptureController(); try capture.startDetailed()
        let recorder = try HTTPRecorder(sink: history, captureController: capture, privacy: HTTPPrivacyPolicy())
        for enabled in [true, false] {
            let completed = expectation(description: "Original HTTP response")
            let observer = ObservedURLSession(session: session, recorder: recorder, collectsMetrics: enabled)
            let task = observer.dataTask(with: URLRequest(url: server.url)) { data, response, error in
                XCTAssertEqual(data, Data("test".utf8)); XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200); XCTAssertNil(error)
                completed.fulfill()
            }
            XCTAssertEqual(task.state, .suspended); task.resume(); wait(for: [completed], timeout: 5)
            let event = try XCTUnwrap(history.snapshot()?.events.last)
            XCTAssertEqual(event.state, .completed)
            if enabled {
                let metrics = try XCTUnwrap(event.metrics)
                XCTAssertEqual(metrics.protocolName, "http/1.1"); XCTAssertEqual(metrics.cache, .network)
                XCTAssertTrue(metrics.phases.contains { $0.phase == .request && $0.endMillis != nil })
                XCTAssertTrue(metrics.phases.contains { $0.phase == .response && $0.endMillis != nil })
            } else { XCTAssertNil(event.metrics) }
        }
        XCTAssertEqual(host.calls, 2)
    }

    func testRepeatedMissingAndBoundedPhasesDoNotInventZero() {
        let origin = Date(timeIntervalSince1970: 1)
        var collector = URLSessionMetricsDelegate.PhaseCollector(origin: origin)
        for _ in 0..<2 {
            collector.add(.dns, origin, origin.addingTimeInterval(0.1))
            collector.add(.connect, origin.addingTimeInterval(0.1), nil)
        }
        XCTAssertEqual(collector.phases.map(\.phase), [.dns, .connect, .dns, .connect])
        XCTAssertNil(collector.phases[1].endMillis)
        collector.add(.dns, origin.addingTimeInterval(1), origin)
        XCTAssertEqual(collector.phases.count, 4); XCTAssertTrue(collector.limited)
        for _ in 0..<100 { collector.add(.dns, origin, origin) }
        XCTAssertEqual(collector.phases.count, 32); XCTAssertTrue(collector.limited)
    }
    func testUnrelatedHostSelectorsRemainForwarded() throws {
        let host = Host(), delegate = URLSessionMetricsDelegate(target: host)
        let selector = #selector(URLSessionTaskDelegate.urlSession(_:task:willPerformHTTPRedirection:newRequest:completionHandler:))
        XCTAssertTrue(delegate.responds(to: selector)); XCTAssertTrue(delegate.forwardingTarget(for: selector) as? Host === host)
    }
}

/// Fixed loopback response only; not a general HTTP server or SDK implementation.
private final class HTTPServer: @unchecked Sendable {
    let listener: NWListener
    let queue = DispatchQueue(label: "Osservio.test.http-metrics")
    var peers: [NWConnection] = []
    var url: URL { URL(string: "http://127.0.0.1:\(listener.port!.rawValue)/metrics")! }
    init() throws {
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port: .any)
        listener = try NWListener(using: parameters)
        let ready = DispatchSemaphore(value: 0)
        listener.stateUpdateHandler = { state in if case .ready = state { ready.signal() }; if case .failed = state { ready.signal() } }
        listener.newConnectionHandler = { [weak self] peer in
            guard let self, self.peers.count < 4 else { peer.cancel(); return }
            self.peers.append(peer); peer.start(queue: self.queue)
            peer.receive(minimumIncompleteLength: 1, maximumLength: 4_096) { _, _, _, error in
                guard error == nil else { peer.cancel(); return }
                peer.send(content: Data("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\ntest".utf8), completion: .contentProcessed { _ in peer.cancel() })
            }
        }
        listener.start(queue: queue)
        guard ready.wait(timeout: .now() + 3) == .success, let port = listener.port, port.rawValue > 0 else {
            listener.cancel(); throw URLError(.cannotConnectToHost)
        }
    }
    func stop() { queue.sync { listener.cancel(); peers.forEach { $0.cancel() }; peers.removeAll() } }
}
