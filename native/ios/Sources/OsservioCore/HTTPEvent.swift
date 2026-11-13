import Foundation

public enum HTTPState: String, Sendable { case pending, receiving, completed, failed, cancelled, closedEarly, captureEnded }
public enum HTTPBodyDirection: String, Sendable { case request, response }
public enum HTTPBodyState: String, Sendable { case pending, omitted, unsupported, incomplete, invalidText, available, truncated }
public enum HTTPFailure: String, Sendable { case transport, timeout, tls, connection, unknown }
public enum HTTPSource: String, Sendable { case host, okHttp, urlSession }

public struct HTTPHeader: Sendable, Equatable {
    public let name: String
    public let value: String

    /// Raw input for admission. This type alone does not establish that a header is safe.
    public init(name: String, value: String) { self.name = name; self.value = value }
}

public struct HTTPBodyReference: Sendable {
    public let state: HTTPBodyState
    public let blobID: String?
    public let observedBytes: Int64
    /// True when host processing changed the captured text; observedBytes still describes the original observation.
    public let isProcessed: Bool
    init(state: HTTPBodyState, blobID: String?, observedBytes: Int64, isProcessed: Bool = false) {
        self.state = state; self.blobID = blobID; self.observedBytes = observedBytes; self.isProcessed = isProcessed
    }

    public static func restore(state: HTTPBodyState, blobID: String?, observedBytes: Int64, isProcessed: Bool = false) throws -> HTTPBodyReference {
        let hasContent = state == .available || state == .truncated
        guard observedBytes >= 0, hasContent == (blobID != nil), hasContent || !isProcessed,
              blobID.map(EvidenceValidation.httpBodyID) ?? true,
              state != .pending || observedBytes == 0,
              state != .available || observedBytes <= 65_536,
              state != .truncated || observedBytes > 0 else { throw PersistenceError.invalidRecord }
        return HTTPBodyReference(state: state, blobID: blobID, observedBytes: observedBytes, isProcessed: isProcessed)
    }
}

/// Sanitized metadata. Body content is queried separately and may be evicted independently.
public struct HTTPEvent: Sendable {
    public let id: String
    public let sessionID: String
    public let sequence: Int64
    public let revision: Int64
    public let timestampMillis: Int64
    public let method: String
    public let source: HTTPSource
    public let url: String
    public let responseURL: String?
    public let requestHeaders: [HTTPHeader]
    public let responseHeaders: [HTTPHeader]
    public let headersLimited: Bool
    public let statusCode: Int?
    public let state: HTTPState
    public let failure: HTTPFailure?
    public let durationMillis: UInt64?
    public let metrics: HTTPMetrics?
    public let requestBody: HTTPBodyReference
    public let responseBody: HTTPBodyReference
    public let textByteCount: Int

    init(id: String, context: CaptureContext, timestamp: Int64, method: String, url: String,
         headers: [HTTPHeader], limited: Bool, source: HTTPSource) {
        self.id = id; sessionID = context.sessionID; sequence = context.sequence; revision = 0
        timestampMillis = timestamp; self.method = method; self.url = url; self.source = source; responseURL = nil
        requestHeaders = headers; responseHeaders = []; headersLimited = limited
        statusCode = nil; state = .pending; failure = nil; durationMillis = nil; metrics = nil
        requestBody = HTTPBodyReference(state: .pending, blobID: nil, observedBytes: 0)
        responseBody = HTTPBodyReference(state: .pending, blobID: nil, observedBytes: 0)
        textByteCount = Self.size(method: method, url: url, headers: headers)
    }

    init(previous: HTTPEvent, headers: [HTTPHeader]? = nil, limited: Bool = false,
         code: Int? = nil, responseURL: String? = nil, state: HTTPState, failure: HTTPFailure? = nil, duration: UInt64? = nil,
         request: HTTPBodyReference? = nil, response: HTTPBodyReference? = nil, metrics: HTTPMetrics? = nil) {
        id = previous.id; sessionID = previous.sessionID; sequence = previous.sequence
        revision = previous.revision + 1; timestampMillis = previous.timestampMillis
        method = previous.method; url = previous.url; source = previous.source
        self.responseURL = responseURL ?? previous.responseURL; requestHeaders = previous.requestHeaders
        responseHeaders = headers ?? previous.responseHeaders; headersLimited = limited || previous.headersLimited
        statusCode = code ?? previous.statusCode; self.state = state; self.failure = failure
        durationMillis = duration; self.metrics = metrics; requestBody = request ?? previous.requestBody; responseBody = response ?? previous.responseBody
        textByteCount = Self.size(method: method, url: url, headers: requestHeaders + responseHeaders) + (self.responseURL?.utf8.count ?? 0) + (metrics?.textByteCount ?? 0)
    }

    private static func size(method: String, url: String, headers: [HTTPHeader]) -> Int {
        method.utf8.count + url.utf8.count + headers.reduce(0) { $0 + $1.name.utf8.count + $1.value.utf8.count }
    }

    /// Validates persisted sanitized metadata without changing its identity, revision, or transport outcome.
    public static func restore(id: String, sessionID: String, sequence: Int64, revision: Int64, timestampMillis: Int64,
                               method: String, source: HTTPSource, url: String, responseURL: String?,
                               requestHeaders: [HTTPHeader], responseHeaders: [HTTPHeader], headersLimited: Bool,
                               statusCode: Int?, state: HTTPState, failure: HTTPFailure?, durationMillis: UInt64?,
                               requestBody: HTTPBodyReference, responseBody: HTTPBodyReference, metrics: HTTPMetrics? = nil) throws -> HTTPEvent {
        guard EvidenceValidation.id(id), EvidenceValidation.id(sessionID), sequence > 0, revision >= 0,
              EvidenceValidation.method(method),
              EvidenceValidation.url(url), responseURL.map(EvidenceValidation.url) ?? true,
              EvidenceValidation.headers(requestHeaders), EvidenceValidation.headers(responseHeaders),
              statusCode.map({ (100...599).contains($0) }) ?? true,
              (state == .failed) == (failure != nil),
              requestBody.blobID.map({ $0.lowercased() == id.lowercased() + ".request" }) ?? true,
              responseBody.blobID.map({ $0.lowercased() == id.lowercased() + ".response" }) ?? true,
              statusCode != nil || (responseURL == nil && responseHeaders.isEmpty) else { throw PersistenceError.invalidRecord }
        switch state {
        case .pending, .receiving:
            guard metrics == nil, durationMillis == nil, requestBody.state == .pending, responseBody.state == .pending,
                  revision == (state == .pending ? 0 : 1), (state == .receiving) == (statusCode != nil) else { throw PersistenceError.invalidRecord }
        case .captureEnded:
            guard metrics == nil, durationMillis == nil, [.omitted, .incomplete].contains(requestBody.state),
                  [.omitted, .incomplete].contains(responseBody.state), revision == (statusCode == nil ? 1 : 2) else { throw PersistenceError.invalidRecord }
        case .completed, .failed, .cancelled, .closedEarly:
            guard requestBody.state != .pending, responseBody.state != .pending,
                  revision == (statusCode == nil ? 1 : 2), state != .completed || statusCode != nil,
                  state == .completed || [.omitted, .incomplete].contains(responseBody.state) else { throw PersistenceError.invalidRecord }
        }
        return HTTPEvent(id: id, sessionID: sessionID, sequence: sequence, revision: revision, timestampMillis: timestampMillis,
            method: method, source: source, url: url, responseURL: responseURL, requestHeaders: requestHeaders,
            responseHeaders: responseHeaders, headersLimited: headersLimited, statusCode: statusCode, state: state,
            failure: failure, durationMillis: durationMillis, requestBody: requestBody, responseBody: responseBody, metrics: metrics)
    }
    private init(id: String, sessionID: String, sequence: Int64, revision: Int64, timestampMillis: Int64,
                 method: String, source: HTTPSource, url: String, responseURL: String?, requestHeaders: [HTTPHeader],
                 responseHeaders: [HTTPHeader], headersLimited: Bool, statusCode: Int?, state: HTTPState,
                 failure: HTTPFailure?, durationMillis: UInt64?, requestBody: HTTPBodyReference, responseBody: HTTPBodyReference, metrics: HTTPMetrics?) {
        self.id = id; self.sessionID = sessionID; self.sequence = sequence; self.revision = revision; self.timestampMillis = timestampMillis
        self.method = method; self.source = source; self.url = url; self.responseURL = responseURL
        self.requestHeaders = requestHeaders; self.responseHeaders = responseHeaders; self.headersLimited = headersLimited
        self.statusCode = statusCode; self.state = state; self.failure = failure; self.durationMillis = durationMillis; self.metrics = metrics
        self.requestBody = requestBody; self.responseBody = responseBody
        textByteCount = Self.size(method: method, url: url, headers: requestHeaders + responseHeaders) + (responseURL?.utf8.count ?? 0) + (metrics?.textByteCount ?? 0)
    }
}

public struct HTTPBody: Sendable {
    public let id: String
    public let text: String
    public let byteCount: Int
    init(id: String, text: String) { self.id = id; self.text = text; byteCount = text.utf8.count }
    public static func restore(id: String, text: String) throws -> HTTPBody {
        guard EvidenceValidation.httpBodyID(id), EvidenceValidation.text(text, limit: 65_536) else { throw PersistenceError.invalidRecord }
        return HTTPBody(id: id, text: text)
    }
}

public struct HTTPUpdate: Sendable {
    public let event: HTTPEvent
    public let isNew: Bool
    public let bodies: [HTTPBody]
    init(event: HTTPEvent, isNew: Bool, bodies: [HTTPBody] = []) {
        self.event = event; self.isNew = isNew; self.bodies = bodies
    }
}

public enum HTTPWriteResult: Sendable { case stored, busy, tooLarge, missing }
public protocol HTTPSink: Sendable {
    /// Atomic, non-blocking metadata/body update. Updates must never recreate cleared or evicted records.
    func write(_ update: HTTPUpdate) throws -> HTTPWriteResult
}

public struct HTTPSnapshot: Sendable {
    public let events: [HTTPEvent]
    public let retainedTextBytes: Int
    public let retainedBodyBytes: Int
    public let evictedEvents: Int64
    public let evictedBodies: Int64
    public let revision: UInt64
    public init(events: [HTTPEvent], retainedTextBytes: Int, retainedBodyBytes: Int,
                evictedEvents: Int64, evictedBodies: Int64, revision: UInt64) {
        self.events = events; self.retainedTextBytes = retainedTextBytes; self.retainedBodyBytes = retainedBodyBytes
        self.evictedEvents = evictedEvents; self.evictedBodies = evictedBodies; self.revision = revision
    }
}

public enum HTTPBodyLookup: Sendable { case available(HTTPBody), unavailable, busy }
public protocol HTTPHistory: Sendable {
    func snapshot() -> HTTPSnapshot?
    func body(id: String) -> HTTPBodyLookup
    func clear()
}
