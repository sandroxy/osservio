import Foundation
import OsservioCore

/// Creates explicit socket wrappers on a host-owned session without replacing its delegate or configuration.
public final class ObservedWebSocketSession: @unchecked Sendable {
    public let session: URLSession
    public let recorder: WebSocketRecorder
    public let maximumPendingCallbacks: Int
    private let callbacks: DispatchSemaphore

    public init(session: URLSession, recorder: WebSocketRecorder, maximumPendingCallbacks: Int = 32) throws {
        guard (1...256).contains(maximumPendingCallbacks) else { throw CaptureConfigurationError.invalidLimit }
        self.session = session; self.recorder = recorder; self.maximumPendingCallbacks = maximumPendingCallbacks
        callbacks = DispatchSemaphore(value: maximumPendingCallbacks)
    }
    /// Returns a suspended task wrapper. The caller owns resume, cancellation, and the session lifetime.
    public func webSocketTask(with request: URLRequest) -> ObservedWebSocketTask {
        ObservedWebSocketTask(task: session.webSocketTask(with: request), recorder: recorder, callbacks: callbacks)
    }
}

/// Only operations made through this wrapper are observed. The native task remains available for host configuration.
public final class ObservedWebSocketTask: @unchecked Sendable {
    public let task: URLSessionWebSocketTask
    private let recorder: WebSocketRecorder
    private let callbacks: DispatchSemaphore
    private let lock = NSLock()
    private let connectionID = UUID()
    private var token: WebSocketToken?
    private var resumed = false
    private var ended = false

    fileprivate init(task: URLSessionWebSocketTask, recorder: WebSocketRecorder, callbacks: DispatchSemaphore) {
        self.task = task; self.recorder = recorder; self.callbacks = callbacks
    }
    public func resume() {
        observe(resuming: true) { _ in }
        task.resume()
    }
    public func suspend() { task.suspend() }
    public func cancel() {
        task.cancel()
        observe(terminal: true) { self.recorder.lifecycle($0, kind: .cancelRequested) }
    }
    /// Forwards the reason untouched; diagnostic capture never reads or stores it.
    public func cancel(with closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        task.cancel(with: closeCode, reason: reason)
        observe { self.recorder.lifecycle($0, kind: .closeRequested, closeCode: Self.code(closeCode)) }
    }
    public func send(_ message: URLSessionWebSocketTask.Message, completionHandler: @escaping @Sendable (Error?) -> Void) {
        let messageID = UUID()
        var submission: WebSocketToken?
        observe { token in
            let result: WebSocketRecordResult
            switch message {
            case .string(let text): result = self.recorder.text(token, direction: .outgoing, outcome: .submitted, value: text, messageID: messageID)
            case .data(let bytes): result = self.recorder.binary(token, direction: .outgoing, outcome: .submitted, byteCount: Int64(bytes.count), messageID: messageID)
            @unknown default: result = .invalid
            }
            if result == .recorded { submission = token }
        }
        guard let submission else { task.send(message, completionHandler: completionHandler); return }
        guard callbacks.wait(timeout: .now()) == .success else {
            recorder.sendResult(submission, messageID: messageID, outcome: .unobserved)
            task.send(message, completionHandler: completionHandler)
            return
        }
        // No original String/Data is captured here. Permits survive capture changes until the native callback.
        task.send(message) { [weak self, callbacks] error in
            if let self {
                self.recorder.sendResult(submission, messageID: messageID, outcome: error == nil ? .sent : .failed,
                                         failure: error.map(Self.failure))
                if let error { self.completed(error) }
            }
            callbacks.signal()
            completionHandler(error)
        }
    }
    /// Performs exactly one native receive. It never creates a receive loop or changes maximumMessageSize.
    public func receive(completionHandler: @escaping @Sendable (Result<URLSessionWebSocketTask.Message, Error>) -> Void) {
        guard callbacks.wait(timeout: .now()) == .success else {
            observe { self.recorder.lifecycle($0, kind: .observationLimited) }
            task.receive(completionHandler: completionHandler)
            return
        }
        task.receive { [weak self, callbacks] result in
            if let self {
                switch result {
                case .success(let message):
                    self.observe { token in
                        switch message {
                        case .string(let text): self.recorder.text(token, direction: .incoming, outcome: .received, value: text)
                        case .data(let bytes): self.recorder.binary(token, direction: .incoming, outcome: .received, byteCount: Int64(bytes.count))
                        @unknown default: self.recorder.lifecycle(token, kind: .observationLimited)
                        }
                    }
                case .failure(let error): self.completed(error)
                }
            }
            callbacks.signal()
            completionHandler(result)
        }
    }

    /// Forward the host delegate's didOpen event explicitly; no handshake is inferred from resume or send.
    public func didOpen() {
        observe { token in
            let response = self.task.response as? HTTPURLResponse
            self.recorder.lifecycle(token, kind: .opened, headers: Self.headers(response?.allHeaderFields), statusCode: response?.statusCode)
        }
    }
    /// Foundation reports a close frame from the peer here. Final task completion is a separate callback.
    public func didClose(with code: URLSessionWebSocketTask.CloseCode) {
        observe { self.recorder.lifecycle($0, kind: .closing, closeCode: Self.code(code)) }
    }
    /// Forward the host task delegate's completion, including nil for normal completion.
    public func didComplete(with error: Error?) { completed(error) }

    private func completed(_ error: Error?) {
        observe(terminal: true) { token in
            if let code = Self.code(self.task.closeCode) {
                self.recorder.lifecycle(token, kind: .closed, closeCode: code)
            } else if let error {
                let native = error as NSError
                let cancelled = error is CancellationError || (native.domain == NSURLErrorDomain && native.code == NSURLErrorCancelled)
                let response = self.task.response as? HTTPURLResponse
                self.recorder.lifecycle(token, kind: cancelled ? .cancelled : .failed,
                    headers: cancelled ? [] : Self.headers(response?.allHeaderFields),
                    statusCode: cancelled ? nil : response?.statusCode, failure: cancelled ? nil : Self.failure(error))
            } else { self.recorder.lifecycle(token, kind: .observationEnded) }
        }
    }
    private func observe(resuming: Bool = false, terminal: Bool = false, _ operation: (WebSocketToken) -> Void) {
        guard lock.try() else { return }
        defer { if terminal { ended = true }; lock.unlock() }
        guard !ended else { return }
        let initial = resuming && !resumed && task.state == .suspended
        if resuming { resumed = true }
        guard let status = recorder.captureController.status() else { return }
        guard status.mode == .detailed else { token = nil; return }
        if token?.sessionID != status.sessionID {
            let request = task.originalRequest
            let headers = (request?.allHTTPHeaderFields ?? [:]).prefix(33).map { HTTPHeader(name: $0.key, value: $0.value) }
            token = recorder.begin(url: request?.url?.absoluteString ?? "", headers: headers, source: .urlSession,
                                   attached: !initial, connectionID: connectionID).token
        }
        if let token { operation(token) }
    }
    private static func code(_ value: URLSessionWebSocketTask.CloseCode) -> Int? {
        (1000...4999).contains(value.rawValue) ? value.rawValue : nil
    }
    private static func headers(_ values: [AnyHashable: Any]?) -> [HTTPHeader] {
        (values ?? [:]).prefix(33).map { key, value in
            guard let name = key as? String, let text = value as? String else { return HTTPHeader(name: "", value: "") }
            return HTTPHeader(name: name, value: text)
        }
    }
    private static func failure(_ error: Error) -> WebSocketFailure {
        let error = error as NSError
        guard error.domain == NSURLErrorDomain else { return .unknown }
        switch error.code {
        case NSURLErrorTimedOut: return .timeout
        case NSURLErrorCannotFindHost, NSURLErrorCannotConnectToHost, NSURLErrorDNSLookupFailed,
             NSURLErrorNotConnectedToInternet, NSURLErrorNetworkConnectionLost: return .connection
        case NSURLErrorSecureConnectionFailed, NSURLErrorServerCertificateHasBadDate,
             NSURLErrorServerCertificateUntrusted, NSURLErrorServerCertificateHasUnknownRoot,
             NSURLErrorServerCertificateNotYetValid, NSURLErrorClientCertificateRejected,
             NSURLErrorClientCertificateRequired: return .tls
        default: return .transport
        }
    }
}
