import Foundation

public struct WebSocketPrivacyPolicy: Sendable {
    public let publicMessageText: Bool
    public let messageByteLimit: Int
    let metadata: HTTPPrivacyPolicy
    /// Applies to every text message on this recorder. Binary content and close reasons are never copied.
    public init(publicURLPath: Bool = false, publicHeaderNames: Set<String> = [],
                publicMessageText: Bool = false, messageByteLimit: Int = 4_096) throws {
        guard (1...65_536).contains(messageByteLimit) else { throw CaptureConfigurationError.invalidLimit }
        self.publicMessageText = publicMessageText; self.messageByteLimit = messageByteLimit
        metadata = try HTTPPrivacyPolicy(publicURLPath: publicURLPath, publicHeaderNames: publicHeaderNames)
    }
}
public struct WebSocketToken: Sendable {
    public let connectionID: String
    public let sessionID: String
}
public enum WebSocketRecordResult: Sendable {
    case recorded, inactive, busy, stale, invalid, connectionLimit, rateLimited, tooLarge, sinkFailure, sequenceExhausted
}
public struct WebSocketBeginResult: Sendable {
    public let result: WebSocketRecordResult
    public let token: WebSocketToken?
}
public struct WebSocketCaptureStatus: Sendable {
    public let activeConnections: Int
    public let rateLimitedCount: Int64
}

/// Captures explicit observations without owning sockets, queues, delegates, or transport retries.
public final class WebSocketRecorder: CaptureSessionObserver, @unchecked Sendable {
    public let captureController: CaptureController
    public let privacy: WebSocketPrivacyPolicy
    private let sink: any WebSocketSink
    private let connectionLimit: Int
    private let eventsPerSecond: Int
    private struct Connection {
        let url: String
        let source: WebSocketSource
        var opened = false
    }
    private var connections: [String: Connection] = [:]
    private var rateSession = ""
    private var windowStart: UInt64 = 0
    private var windowCount = 0
    private var rateLimited: Int64 = 0

    public init(sink: any WebSocketSink, captureController: CaptureController, privacy: WebSocketPrivacyPolicy,
                connectionLimit: Int = 16, eventsPerSecond: Int = 100) throws {
        guard (1...64).contains(connectionLimit), (1...1_000).contains(eventsPerSecond) else {
            throw CaptureConfigurationError.invalidLimit
        }
        self.sink = sink; self.captureController = captureController; self.privacy = privacy
        self.connectionLimit = connectionLimit; self.eventsPerSecond = eventsPerSecond
        try captureController.register(self)
    }

    /// `attached` means observation began after socket creation; it does not establish an open handshake.
    public func begin(url: String, headers: [HTTPHeader] = [], source: WebSocketSource = .host,
                      attached: Bool = false, connectionID: UUID = UUID()) -> WebSocketBeginResult {
        var token: WebSocketToken?
        let result = access { context in
            guard self.connections.count < self.connectionLimit else { return .connectionLimit }
            let id = connectionID.uuidString
            guard self.connections[id] == nil else { return .invalid }
            guard self.admit(context) else { return .rateLimited }
            let url = self.privacy.metadata.url(url)
            let headers = self.privacy.metadata.headers(headers)
            let event = WebSocketEvent(id: UUID().uuidString, connectionID: id, context: context,
                timestamp: self.captureController.clock.wallTimeMillis(), url: url, source: source,
                kind: attached ? .attached : .connecting, headers: headers.values, headersLimited: headers.limited)
            let result = self.write(event)
            if result == .recorded {
                self.connections[id] = Connection(url: url, source: source)
                token = WebSocketToken(connectionID: id, sessionID: context.sessionID)
            }
            return result
        }
        return WebSocketBeginResult(result: result, token: token)
    }

    /// Codes describe observed API evidence. Close reason strings and raw error descriptions are not accepted.
    @discardableResult
    public func lifecycle(_ token: WebSocketToken, kind: WebSocketKind, headers: [HTTPHeader] = [],
                          statusCode: Int? = nil, closeCode: Int? = nil, failure: WebSocketFailure? = nil) -> WebSocketRecordResult {
        access { context in
            guard token.sessionID == context.sessionID, let connection = self.connections[token.connectionID] else { return .stale }
            guard [.opened, .closeRequested, .closing, .closed, .failed, .cancelled, .cancelRequested, .observationEnded, .observationLimited].contains(kind),
                  statusCode == nil || ([.opened, .failed].contains(kind) && (100...599).contains(statusCode!)),
                  closeCode == nil || ([.closeRequested, .closing, .closed, .cancelRequested].contains(kind) && (1000...4999).contains(closeCode!)),
                  (kind == .failed) == (failure != nil), kind != .opened || !connection.opened,
                  headers.isEmpty || [.opened, .failed].contains(kind) else { return .invalid }
            let terminal = [.closed, .failed, .cancelled, .cancelRequested, .observationEnded].contains(kind)
            // A dropped terminal event must still release the observation slot.
            if terminal { self.connections.removeValue(forKey: token.connectionID) }
            if kind == .opened { self.connections[token.connectionID]?.opened = true }
            guard self.admit(context) else { return .rateLimited }
            let headers = self.privacy.metadata.headers(headers)
            return self.write(WebSocketEvent(id: UUID().uuidString, connectionID: token.connectionID,
                context: context, timestamp: self.captureController.clock.wallTimeMillis(), url: connection.url,
                source: connection.source, kind: kind, headers: headers.values, headersLimited: headers.limited,
                statusCode: statusCode, closeCode: closeCode, failure: failure))
        }
    }

    @discardableResult
    public func text(_ token: WebSocketToken, direction: WebSocketDirection, outcome: WebSocketMessageOutcome,
                     value: String, messageID: UUID = UUID()) -> WebSocketRecordResult {
        message(token, direction: direction, outcome: outcome, messageID: messageID) { id in
            guard self.privacy.publicMessageText else {
                return (WebSocketPayloadReference(state: .omitted, blobID: nil, byteCount: nil), nil)
            }
            guard let bounded = BoundedText.prefix(value, bytes: self.privacy.messageByteLimit) else {
                return (WebSocketPayloadReference(state: .invalidText, blobID: nil, byteCount: nil), nil)
            }
            let payload = WebSocketPayload(id: id, text: bounded.text)
            return (WebSocketPayloadReference(state: bounded.truncated ? .truncated : .available, blobID: id,
                byteCount: bounded.truncated ? nil : Int64(payload.byteCount)), payload)
        }
    }

    /// Binary bytes remain with the host; only their already-known count is accepted.
    @discardableResult
    public func binary(_ token: WebSocketToken, direction: WebSocketDirection, outcome: WebSocketMessageOutcome,
                       byteCount: Int64, messageID: UUID = UUID()) -> WebSocketRecordResult {
        guard byteCount >= 0 else { return .invalid }
        return message(token, direction: direction, outcome: outcome, messageID: messageID) { _ in
            (WebSocketPayloadReference(state: .binary, blobID: nil, byteCount: byteCount), nil)
        }
    }

    /// Correlates a client completion without retaining its original message until the callback.
    /// The referenced submission may already be evicted. IDs come from the same explicit host operation.
    @discardableResult
    public func sendResult(_ token: WebSocketToken, messageID: UUID, outcome: WebSocketMessageOutcome,
                           failure: WebSocketFailure? = nil) -> WebSocketRecordResult {
        access { context in
            guard token.sessionID == context.sessionID, let connection = self.connections[token.connectionID] else { return .stale }
            guard [.sent, .failed, .unobserved].contains(outcome), (outcome == .failed) == (failure != nil) else { return .invalid }
            guard self.admit(context) else { return .rateLimited }
            return self.write(WebSocketEvent(id: UUID().uuidString, connectionID: token.connectionID, context: context,
                timestamp: self.captureController.clock.wallTimeMillis(), url: connection.url, source: connection.source,
                kind: .messageResult, failure: failure, direction: .outgoing, outcome: outcome,
                relatedMessageID: messageID.uuidString))
        }
    }

    public func status() -> WebSocketCaptureStatus? {
        captureController.inspect { _ in WebSocketCaptureStatus(activeConnections: connections.count, rateLimitedCount: rateLimited) }
    }
    func captureSessionDidEnd() { connections.removeAll() }

    private func message(_ token: WebSocketToken, direction: WebSocketDirection, outcome: WebSocketMessageOutcome,
                         messageID: UUID, payload: (String) -> (WebSocketPayloadReference, WebSocketPayload?)) -> WebSocketRecordResult {
        access { context in
            guard token.sessionID == context.sessionID, let connection = self.connections[token.connectionID] else { return .stale }
            guard outcome != .unobserved, (direction == .incoming) == (outcome == .received) else { return .invalid }
            guard self.admit(context) else { return .rateLimited }
            let id = messageID.uuidString
            let body = payload(id)
            return self.write(WebSocketEvent(id: id, connectionID: token.connectionID, context: context,
                timestamp: self.captureController.clock.wallTimeMillis(), url: connection.url, source: connection.source,
                kind: .message, direction: direction, outcome: outcome, payload: body.0), payload: body.1)
        }
    }
    private func admit(_ context: CaptureContext) -> Bool {
        if rateSession != context.sessionID { rateSession = context.sessionID; windowStart = context.sessionStartMillis; windowCount = 0 }
        if context.monotonicMillis >= windowStart, context.monotonicMillis - windowStart >= 1_000 {
            windowStart = context.monotonicMillis; windowCount = 0
        }
        guard windowCount < eventsPerSecond else {
            if rateLimited < Int64.max { rateLimited += 1 }
            return false
        }
        windowCount += 1; return true
    }
    private func access(_ body: (CaptureContext) -> WebSocketRecordResult) -> WebSocketRecordResult {
        switch captureController.record(requiresDetailed: true, body) {
        case .value(let value): return value
        case .inactive: return .inactive
        case .busy: return .busy
        case .sequenceExhausted: return .sequenceExhausted
        }
    }
    private func write(_ event: WebSocketEvent, payload: WebSocketPayload? = nil) -> WebSocketRecordResult {
        do {
            switch try sink.append(event, payload: payload) {
            case .stored: return .recorded
            case .busy: return .busy
            case .tooLarge: return .tooLarge
            }
        } catch { return .sinkFailure }
    }
}
