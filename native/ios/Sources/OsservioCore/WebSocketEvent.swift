import Foundation

public enum WebSocketKind: String, Sendable {
    case connecting, attached, opened, message, closing, closed, failed, cancelRequested, observationEnded
}
public enum WebSocketSource: String, Sendable { case host, okHttp, urlSession }
public enum WebSocketDirection: String, Sendable { case incoming, outgoing }
/// Queued and sent describe client API results, never proof of peer receipt.
public enum WebSocketMessageOutcome: String, Sendable { case received, queued, sent, rejected, failed }
public enum WebSocketPayloadState: String, Sendable { case omitted, binary, available, truncated, invalidText }
public enum WebSocketFailure: String, Sendable { case transport, timeout, tls, connection, unknown }

public struct WebSocketPayloadReference: Sendable {
    public let state: WebSocketPayloadState
    public let blobID: String?
    /// Nil means the byte count was not available without inspecting more content.
    public let byteCount: Int64?
}
public struct WebSocketPayload: Sendable {
    public let id: String
    public let text: String
    public let byteCount: Int
    init(id: String, text: String) { self.id = id; self.text = text; byteCount = text.utf8.count }
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
    public let payload: WebSocketPayloadReference?
    public let textByteCount: Int

    init(id: String, connectionID: String, context: CaptureContext, timestamp: Int64, url: String,
         source: WebSocketSource, kind: WebSocketKind, headers: [HTTPHeader] = [], headersLimited: Bool = false,
         statusCode: Int? = nil, closeCode: Int? = nil, failure: WebSocketFailure? = nil,
         direction: WebSocketDirection? = nil, outcome: WebSocketMessageOutcome? = nil,
         payload: WebSocketPayloadReference? = nil) {
        self.id = id; self.connectionID = connectionID; sessionID = context.sessionID; sequence = context.sequence
        timestampMillis = timestamp; self.url = url; self.source = source; self.kind = kind
        self.headers = headers; self.headersLimited = headersLimited; self.statusCode = statusCode
        self.closeCode = closeCode; self.failure = failure; self.direction = direction
        self.outcome = outcome; self.payload = payload
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
