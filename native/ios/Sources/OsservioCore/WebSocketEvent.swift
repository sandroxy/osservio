import Foundation

public enum WebSocketKind: String, Sendable {
    case connecting, attached, opened, message, messageResult, closeRequested, closing, closed, failed, cancelled, cancelRequested, observationEnded, observationLimited
}
public enum WebSocketSource: String, Sendable { case host, okHttp, urlSession }
public enum WebSocketDirection: String, Sendable { case incoming, outgoing }
/// Queued and sent describe client API results, never proof of peer receipt.
public enum WebSocketMessageOutcome: String, Sendable { case received, submitted, queued, sent, rejected, failed, unobserved }
public enum WebSocketPayloadState: String, Sendable { case omitted, binary, available, truncated, invalidText }
public enum WebSocketFailure: String, Sendable { case transport, timeout, tls, connection, unknown }

public struct WebSocketPayloadReference: Sendable {
    public let state: WebSocketPayloadState
    public let blobID: String?
    /// Nil means the byte count was not available without inspecting more content.
    public let byteCount: Int64?
    public static func restore(state: WebSocketPayloadState, blobID: String?, byteCount: Int64?) throws -> WebSocketPayloadReference {
        let hasContent = state == .available || state == .truncated
        guard hasContent == (blobID != nil), blobID.map(EvidenceValidation.id) ?? true,
              byteCount.map({ $0 >= 0 }) ?? true else { throw PersistenceError.invalidRecord }
        switch state {
        case .available: guard let byteCount, byteCount <= 65_536 else { throw PersistenceError.invalidRecord }
        case .binary: guard byteCount != nil else { throw PersistenceError.invalidRecord }
        case .omitted, .invalidText, .truncated: guard byteCount == nil else { throw PersistenceError.invalidRecord }
        }
        return WebSocketPayloadReference(state: state, blobID: blobID, byteCount: byteCount)
    }
}
public struct WebSocketPayload: Sendable {
    public let id: String
    public let text: String
    public let byteCount: Int
    init(id: String, text: String) { self.id = id; self.text = text; byteCount = text.utf8.count }
    public static func restore(id: String, text: String) throws -> WebSocketPayload {
        guard EvidenceValidation.id(id), EvidenceValidation.text(text, limit: 65_536) else { throw PersistenceError.invalidRecord }
        return WebSocketPayload(id: id, text: text)
    }
}

/// An immutable, sanitized observation, not a reconstructed connection state or wire frame.
public struct WebSocketEvent: Sendable {
    public let id: String
    public let connectionID: String
    public let sessionID: String
    public let sequence: Int64
    public let timestampMillis: Int64
    public let url: String
    public let source: WebSocketSource
    public let kind: WebSocketKind
    public let headers: [HTTPHeader]
    public let headersLimited: Bool
    public let statusCode: Int?
    public let closeCode: Int?
    public let failure: WebSocketFailure?
    public let direction: WebSocketDirection?
    public let outcome: WebSocketMessageOutcome?
    public let relatedMessageID: String?
    public let payload: WebSocketPayloadReference?
    public let textByteCount: Int

    init(id: String, connectionID: String, context: CaptureContext, timestamp: Int64, url: String,
         source: WebSocketSource, kind: WebSocketKind, headers: [HTTPHeader] = [], headersLimited: Bool = false,
         statusCode: Int? = nil, closeCode: Int? = nil, failure: WebSocketFailure? = nil,
         direction: WebSocketDirection? = nil, outcome: WebSocketMessageOutcome? = nil,
         payload: WebSocketPayloadReference? = nil, relatedMessageID: String? = nil) {
        self.id = id; self.connectionID = connectionID; sessionID = context.sessionID; sequence = context.sequence
        timestampMillis = timestamp; self.url = url; self.source = source; self.kind = kind
        self.headers = headers; self.headersLimited = headersLimited; self.statusCode = statusCode
        self.closeCode = closeCode; self.failure = failure; self.direction = direction
        self.outcome = outcome; self.payload = payload; self.relatedMessageID = relatedMessageID
        textByteCount = url.utf8.count + headers.reduce(0) { $0 + $1.name.utf8.count + $1.value.utf8.count }
    }

    /// Restores an observation, not a reconstructed connection. Related submissions may be absent.
    public static func restore(id: String, connectionID: String, sessionID: String, sequence: Int64,
                               timestampMillis: Int64, url: String, source: WebSocketSource, kind: WebSocketKind,
                               headers: [HTTPHeader], headersLimited: Bool, statusCode: Int?, closeCode: Int?,
                               failure: WebSocketFailure?, direction: WebSocketDirection?, outcome: WebSocketMessageOutcome?,
                               relatedMessageID: String?, payload: WebSocketPayloadReference?) throws -> WebSocketEvent {
        guard EvidenceValidation.id(id), EvidenceValidation.id(connectionID), EvidenceValidation.id(sessionID), sequence > 0,
              EvidenceValidation.url(url), EvidenceValidation.headers(headers),
              statusCode.map({ [.opened, .failed].contains(kind) && (100...599).contains($0) }) ?? true,
              closeCode.map({ [.closeRequested, .closing, .closed, .cancelRequested].contains(kind) && (1000...4999).contains($0) }) ?? true,
              (headers.isEmpty && !headersLimited) || [.connecting, .attached, .opened, .failed].contains(kind) else { throw PersistenceError.invalidRecord }
        switch kind {
        case .message:
            guard let direction, let outcome, let payload, outcome != .unobserved,
                  (direction == .incoming) == (outcome == .received), failure == nil, relatedMessageID == nil,
                  payload.blobID.map({ $0.lowercased() == id.lowercased() }) ?? true else { throw PersistenceError.invalidRecord }
        case .messageResult:
            guard direction == .outgoing, let outcome, [.sent, .failed, .unobserved].contains(outcome),
                  (outcome == .failed) == (failure != nil), let relatedMessageID, EvidenceValidation.id(relatedMessageID),
                  payload == nil else { throw PersistenceError.invalidRecord }
        default:
            guard direction == nil, outcome == nil, payload == nil, relatedMessageID == nil,
                  (kind == .failed) == (failure != nil) else { throw PersistenceError.invalidRecord }
        }
        return WebSocketEvent(id: id, connectionID: connectionID, sessionID: sessionID, sequence: sequence,
            timestampMillis: timestampMillis, url: url, source: source, kind: kind, headers: headers,
            headersLimited: headersLimited, statusCode: statusCode, closeCode: closeCode, failure: failure,
            direction: direction, outcome: outcome, relatedMessageID: relatedMessageID, payload: payload)
    }
    private init(id: String, connectionID: String, sessionID: String, sequence: Int64, timestampMillis: Int64,
                 url: String, source: WebSocketSource, kind: WebSocketKind, headers: [HTTPHeader], headersLimited: Bool,
                 statusCode: Int?, closeCode: Int?, failure: WebSocketFailure?, direction: WebSocketDirection?,
                 outcome: WebSocketMessageOutcome?, relatedMessageID: String?, payload: WebSocketPayloadReference?) {
        self.id = id; self.connectionID = connectionID; self.sessionID = sessionID; self.sequence = sequence
        self.timestampMillis = timestampMillis; self.url = url; self.source = source; self.kind = kind
        self.headers = headers; self.headersLimited = headersLimited; self.statusCode = statusCode; self.closeCode = closeCode
        self.failure = failure; self.direction = direction; self.outcome = outcome; self.relatedMessageID = relatedMessageID; self.payload = payload
        textByteCount = url.utf8.count + headers.reduce(0) { $0 + $1.name.utf8.count + $1.value.utf8.count }
    }
}

public enum WebSocketWriteResult: Sendable { case stored, busy, tooLarge }
public protocol WebSocketSink: Sendable {
    /// Atomic, bounded, non-blocking append of already-sanitized values.
    func append(_ event: WebSocketEvent, payload: WebSocketPayload?) throws -> WebSocketWriteResult
}
public struct WebSocketSnapshot: Sendable {
    public let events: [WebSocketEvent]
    public let retainedTextBytes: Int
    public let retainedPayloadBytes: Int
    public let evictedEvents: Int64
    public let evictedPayloads: Int64
    public let revision: UInt64
    public init(events: [WebSocketEvent], retainedTextBytes: Int, retainedPayloadBytes: Int,
                evictedEvents: Int64, evictedPayloads: Int64, revision: UInt64) {
        self.events = events; self.retainedTextBytes = retainedTextBytes; self.retainedPayloadBytes = retainedPayloadBytes
        self.evictedEvents = evictedEvents; self.evictedPayloads = evictedPayloads; self.revision = revision
    }
}
public enum WebSocketPayloadLookup: Sendable { case available(WebSocketPayload), unavailable, busy }
public protocol WebSocketHistory: Sendable {
    func snapshot() -> WebSocketSnapshot?
    func payload(id: String) -> WebSocketPayloadLookup
    func clear()
}
