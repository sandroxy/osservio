import Foundation
import Network
import OsservioCore
import OsservioStore
import OsservioURLSession
import XCTest

final class ObservedWebSocketSessionTests: XCTestCase {
    func testNativeExchangePreservesMessagesAndDelegateWhileBoundingCapture() throws {
        let server = try SocketServer()
        defer { server.stop() }
        let store = try MemoryWebSocketStore()
        let capture = CaptureController()
        try capture.startDetailed()
        let recorder = try WebSocketRecorder(sink: store, captureController: capture,
            privacy: WebSocketPrivacyPolicy(publicMessageText: true, messageByteLimit: 4))
        let opened = expectation(description: "Host delegate receives open")
        let closed = expectation(description: "Host delegate receives completion")
        let delegate = SocketDelegate(opened: opened, completed: closed)
        let session = URLSession(configuration: .ephemeral, delegate: delegate, delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let observer = try ObservedWebSocketSession(session: session, recorder: recorder)
        let socket = observer.webSocketTask(with: URLRequest(url: server.url))
        delegate.socket = socket
        XCTAssertEqual(socket.task.state, .suspended)
        socket.task.maximumMessageSize = 16_384
        socket.resume()
        wait(for: [opened], timeout: 5)

        let text = String(repeating: "汉", count: 1_000)
        let sent = expectation(description: "Native text send completes")
        let received = expectation(description: "Host receives the complete text")
        socket.receive { result in
            guard case .success(.string(let value)) = result else { return XCTFail("Expected text: \(result)") }
            XCTAssertEqual(value, text)
            received.fulfill()
        }
        socket.send(.string(text)) { error in XCTAssertNil(error); sent.fulfill() }
        wait(for: [sent, received], timeout: 5)
        let bytes = Data([0, 1, 2, 255])
        let binarySent = expectation(description: "Native binary send completes")
        let binaryReceived = expectation(description: "Host receives original binary data")
        socket.receive { result in
            guard case .success(.data(let value)) = result else { return XCTFail("Expected binary: \(result)") }
            XCTAssertEqual(value, bytes)
            binaryReceived.fulfill()
        }
        socket.send(.data(bytes)) { error in XCTAssertNil(error); binarySent.fulfill() }
        wait(for: [binarySent, binaryReceived], timeout: 5)
        XCTAssertEqual(socket.task.maximumMessageSize, 16_384)
        let events = try XCTUnwrap(store.snapshot()).events
        XCTAssertEqual(events.first?.kind, .connecting)
        XCTAssertTrue(events.contains { $0.kind == .opened })
        let messages = events.filter { $0.kind == .message }
        XCTAssertEqual(messages.count, 4)
        XCTAssertEqual(messages.filter { $0.payload?.state == .truncated }.count, 2)
        XCTAssertEqual(messages.filter { $0.payload?.state == .binary && $0.payload?.byteCount == 4 }.count, 2)
        for result in events.filter({ $0.kind == .messageResult }) {
            XCTAssertEqual(result.outcome, .sent)
            XCTAssertTrue(messages.contains { $0.id == result.relatedMessageID && $0.outcome == .submitted })
            XCTAssertNil(result.payload)
        }
        XCTAssertEqual(events.filter { $0.kind == .messageResult }.count, 2)
        socket.cancel(with: .normalClosure, reason: Data("private close reason".utf8))
        wait(for: [closed], timeout: 5)
        XCTAssertTrue(store.snapshot()?.events.contains { $0.kind == .closeRequested } == true)
        XCTAssertTrue(store.snapshot()?.events.contains { $0.kind == .closed && $0.closeCode == 1000 } == true)
    }

    func testCallbackLimitPreservesHostReceivesAndReleasesBeforeReentry() throws {
        let server = try SocketServer()
        defer { server.stop() }
        let store = try MemoryWebSocketStore()
        let capture = CaptureController()
        try capture.startDetailed()
        let recorder = try WebSocketRecorder(sink: store, captureController: capture, privacy: WebSocketPrivacyPolicy())
        let opened = expectation(description: "Opened")
        let delegate = SocketDelegate(opened: opened)
        let session = URLSession(configuration: .ephemeral, delegate: delegate, delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let observer = try ObservedWebSocketSession(session: session, recorder: recorder, maximumPendingCallbacks: 1)
        let socket = observer.webSocketTask(with: URLRequest(url: server.url))
        delegate.socket = socket
        socket.resume()
        wait(for: [opened], timeout: 5)
        let first = expectation(description: "Observed receive")
        let second = expectation(description: "Unobserved receive still reaches host")
        let reentered = expectation(description: "Host reentry can acquire the released permit")
        socket.receive { result in
            if case .failure(let error) = result { XCTFail("\(error)") }
            socket.receive { result in
                if case .failure(let error) = result { XCTFail("\(error)") }
                reentered.fulfill()
            }
            first.fulfill()
        }
        socket.receive { result in
            if case .failure(let error) = result { XCTFail("\(error)") }
            second.fulfill()
        }
        let sent = expectation(description: "Send completes even with no observation permit")
        socket.send(.string("one")) { XCTAssertNil($0); sent.fulfill() }
        // Further sends bypass the wrapper so only the reentrant receive needs a permit.
        for text in ["two", "three"] { socket.task.send(.string(text)) { XCTAssertNil($0) } }
        wait(for: [first, second, reentered, sent], timeout: 5)
        XCTAssertEqual(store.snapshot()?.events.filter { $0.kind == .observationLimited }.count, 1)
        XCTAssertEqual(store.snapshot()?.events.filter { $0.kind == .message && $0.direction == .incoming }.count, 2)
        XCTAssertEqual(store.snapshot()?.events.filter { $0.kind == .messageResult && $0.outcome == .unobserved }.count, 1)
        socket.cancel()
    }

    func testLateCaptureAttachesWithoutInventingHandshakeAndCancellationReachesHost() throws {
        let server = try SocketServer()
        defer { server.stop() }
        let store = try MemoryWebSocketStore()
        let capture = CaptureController()
        let recorder = try WebSocketRecorder(sink: store, captureController: capture, privacy: WebSocketPrivacyPolicy())
        let opened = expectation(description: "Unobserved handshake reaches host")
        let completed = expectation(description: "Cancellation reaches host delegate")
        let delegate = SocketDelegate(opened: opened, completed: completed)
        let session = URLSession(configuration: .ephemeral, delegate: delegate, delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let observer = try ObservedWebSocketSession(session: session, recorder: recorder)
        let socket = observer.webSocketTask(with: URLRequest(url: server.url))
        delegate.socket = socket
        socket.resume()
        wait(for: [opened], timeout: 5)
        XCTAssertEqual(store.snapshot()?.events.count, 0)
        try capture.startDetailed()
        let sent = expectation(description: "Send after capture starts")
        socket.send(.string("private")) { XCTAssertNil($0); sent.fulfill() }
        wait(for: [sent], timeout: 5)
        XCTAssertEqual(store.snapshot()?.events.first?.kind, .attached)
        XCTAssertFalse(store.snapshot()?.events.contains { $0.kind == .opened } == true)
        let receive = expectation(description: "Pending receive reaches host on cancellation")
        // A queued server message may arrive before cancellation; either native result is preserved.
        socket.receive { _ in receive.fulfill() }
        socket.cancel()
        wait(for: [receive, completed], timeout: 5)
        XCTAssertTrue(store.snapshot()?.events.contains { $0.kind == .cancelRequested } == true)
        XCTAssertEqual(recorder.status()?.activeConnections, 0)
    }

    func testRejectedHandshakeIsFailureAndPreservesHostError() throws {
        let server = try SocketServer(accept: false)
        defer { server.stop() }
        let store = try MemoryWebSocketStore()
        let capture = CaptureController()
        try capture.startDetailed()
        let recorder = try WebSocketRecorder(sink: store, captureController: capture, privacy: WebSocketPrivacyPolicy())
        let completed = expectation(description: "Handshake failure reaches host")
        let delegate = SocketDelegate(completed: completed)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 1
        configuration.timeoutIntervalForResource = 2
        let session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        XCTAssertThrowsError(try ObservedWebSocketSession(session: session, recorder: recorder, maximumPendingCallbacks: 0))
        let socket = try ObservedWebSocketSession(session: session, recorder: recorder).webSocketTask(with: URLRequest(url: server.url))
        delegate.socket = socket
        socket.resume()
        wait(for: [completed], timeout: 5)
        XCTAssertNotNil(delegate.error)
        XCTAssertEqual(store.snapshot()?.events.last?.kind, .failed)
        XCTAssertNotNil(store.snapshot()?.events.last?.failure)
        XCTAssertFalse(store.snapshot()?.events.contains { $0.kind == .opened } == true)
        XCTAssertEqual(recorder.status()?.activeConnections, 0)
    }
}

private final class SocketDelegate: NSObject, URLSessionWebSocketDelegate, @unchecked Sendable {
    // Assigned while the task is suspended, before Foundation can issue callbacks.
    var socket: ObservedWebSocketTask?
    let opened: XCTestExpectation?
    let completed: XCTestExpectation?
    var error: Error?
    init(opened: XCTestExpectation? = nil, completed: XCTestExpectation? = nil) {
        self.opened = opened; self.completed = completed
    }
    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        XCTAssertTrue(webSocketTask === socket?.task)
        socket?.didOpen()
        opened?.fulfill()
    }
    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        socket?.didClose(with: closeCode)
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        socket?.didComplete(with: error)
        self.error = error
        completed?.fulfill()
    }
}

/// Uses Apple's protocol implementation; no handwritten handshake or frame parser is under test.
private final class SocketServer: @unchecked Sendable {
    private let queue = DispatchQueue(label: "Osservio.WebSocketTests.server")
    private let listener: NWListener
    private var connections: [NWConnection] = []
    var url: URL { URL(string: "ws://127.0.0.1:\(listener.port!.rawValue)/echo?token=private")! }

    init(accept: Bool = true) throws {
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port: .any)
        let options = NWProtocolWebSocket.Options()
        options.autoReplyPing = true
        options.setClientRequestHandler(queue) { _, _ in
            NWProtocolWebSocket.Response(status: accept ? .accept : .reject, subprotocol: nil)
        }
        parameters.defaultProtocolStack.applicationProtocols.insert(options, at: 0)
        listener = try NWListener(using: parameters)
        let ready = DispatchSemaphore(value: 0)
        listener.stateUpdateHandler = { state in
            switch state { case .ready, .failed: ready.signal(); default: break }
        }
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { connection.cancel(); return }
            self.connections.append(connection)
            connection.stateUpdateHandler = { [weak self, weak connection] state in
                guard case .ready = state, let connection else { return }
                self?.receive(connection)
            }
            connection.start(queue: self.queue)
        }
        listener.start(queue: queue)
        guard ready.wait(timeout: .now() + 5) == .success, listener.port != nil else {
            listener.cancel()
            throw URLError(.cannotConnectToHost)
        }
    }
    private func receive(_ connection: NWConnection) {
        connection.receiveMessage { [weak self] data, context, _, error in
            guard error == nil, let metadata = context?.protocolMetadata(definition: NWProtocolWebSocket.definition)
                as? NWProtocolWebSocket.Metadata else { return }
            let reply = NWProtocolWebSocket.Metadata(opcode: metadata.opcode)
            if metadata.opcode == .close { reply.closeCode = metadata.closeCode }
            let context = NWConnection.ContentContext(identifier: "echo", metadata: [reply])
            connection.send(content: data, contentContext: context, isComplete: true, completion: .contentProcessed { _ in })
            if metadata.opcode != .close { self?.receive(connection) }
        }
    }
    func stop() {
        queue.sync {
            listener.cancel()
            connections.forEach { $0.cancel() }
            connections.removeAll()
        }
    }
}
