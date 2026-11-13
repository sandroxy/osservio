import Foundation

public struct HTTPExchangeToken: Sendable {
    public let id: String
    let sessionID: String
}

public enum HTTPRecordResult: Sendable {
    case recorded, inactive, busy, stale, rateLimited, activeLimit, invalid, tooLarge, missing, sinkFailure, sequenceExhausted
}

public struct HTTPBeginResult: Sendable {
    public let result: HTTPRecordResult
    public let token: HTTPExchangeToken?
}

/// Bounded HTTP observation. It never owns or reads a host stream, and performs no I/O.
public final class HTTPRecorder: CaptureSessionObserver, @unchecked Sendable {
    public let captureController: CaptureController
    public let privacy: HTTPPrivacyPolicy
    private let sink: any HTTPSink
    private let activeLimit: Int
    private let requestsPerSecond: Int
    private let exchangeLifetimeMillis: UInt64
    private var active: [String: Exchange] = [:]
    private var rateSession = ""
    private var windowStart: UInt64 = 0
    private var windowCount = 0

    private final class BodyBuffer {
        var bytes = Data()
        var observed: Int64 = 0
        var supportsText: Bool
        var hasGap = false
        init(supportsText: Bool) { self.supportsText = supportsText }
    }

    private final class Exchange {
        var event: HTTPEvent
        let started: UInt64
        let request: BodyBuffer
        let response = BodyBuffer(supportsText: false)
        init(event: HTTPEvent, started: UInt64, supportsRequest: Bool) {
            self.event = event; self.started = started; request = BodyBuffer(supportsText: supportsRequest)
        }
    }

    public init(sink: any HTTPSink, captureController: CaptureController, privacy: HTTPPrivacyPolicy,
                activeLimit: Int = 32, requestsPerSecond: Int = 50, exchangeLifetimeMillis: UInt64 = 120_000) throws {
        guard (1...256).contains(activeLimit), (1...1_000).contains(requestsPerSecond),
              (1...600_000).contains(exchangeLifetimeMillis) else { throw CaptureConfigurationError.invalidLimit }
        self.sink = sink; self.captureController = captureController; self.privacy = privacy
        self.activeLimit = activeLimit; self.requestsPerSecond = requestsPerSecond
        self.exchangeLifetimeMillis = exchangeLifetimeMillis
        try captureController.register(self)
    }

    public func begin(method: String, url: String, headers: [HTTPHeader] = [], source: HTTPSource = .host) -> HTTPBeginResult {
        let access = captureController.record(requiresDetailed: true) { context -> HTTPBeginResult in
            expire(at: context.monotonicMillis)
            if rateSession != context.sessionID {
                rateSession = context.sessionID; windowStart = context.sessionStartMillis; windowCount = 0
            }
            if context.monotonicMillis >= windowStart, context.monotonicMillis - windowStart >= 1_000 {
                windowStart = context.monotonicMillis; windowCount = 0
            }
            guard windowCount < requestsPerSecond else { return HTTPBeginResult(result: .rateLimited, token: nil) }
            windowCount += 1
            guard active.count < activeLimit else { return HTTPBeginResult(result: .activeLimit, token: nil) }
            let sanitized = privacy.headers(headers)
            let id = UUID().uuidString
            let event = HTTPEvent(id: id, context: context, timestamp: captureController.clock.wallTimeMillis(),
                                  method: privacy.method(method), url: privacy.url(url),
                                  headers: sanitized.values, limited: sanitized.limited, source: source)
            let result = write(HTTPUpdate(event: event, isNew: true))
            guard result == .recorded else { return HTTPBeginResult(result: result, token: nil) }
            active[id] = Exchange(event: event, started: context.monotonicMillis,
                                  supportsRequest: privacy.supportsBody(headers, decoded: false))
            return HTTPBeginResult(result: .recorded, token: HTTPExchangeToken(id: id, sessionID: context.sessionID))
        }
        let observed: HTTPBeginResult
        switch access {
        case .value(let value): observed = value
        case .inactive: observed = HTTPBeginResult(result: .inactive, token: nil)
        case .busy: observed = HTTPBeginResult(result: .busy, token: nil)
        case .sequenceExhausted: observed = HTTPBeginResult(result: .sequenceExhausted, token: nil)
        }
        captureController.statistics.observe(observed.result)
        return observed
    }

    /// The copy closure receives a bounded prefix size. It must copy from already-observed memory, never read a stream.
    /// Callers must report any rejected chunk as incomplete when finishing the corresponding body.
    @discardableResult
    public func observeBody(_ token: HTTPExchangeToken, direction: HTTPBodyDirection, byteCount: Int64,
                            copyPrefix: (Int) throws -> Data) -> HTTPRecordResult {
        update(token) { exchange, _ in
            let buffer = direction == .request ? exchange.request : exchange.response
            guard direction != .response || exchange.event.state == .receiving else {
                buffer.hasGap = true; return .invalid
            }
            guard byteCount >= 0 else { buffer.hasGap = true; return .invalid }
            buffer.observed = byteCount > Int64.max - buffer.observed ? Int64.max : buffer.observed + byteCount
            guard buffer.supportsText, !buffer.hasGap else { return .recorded }
            let requested = Int(min(byteCount, Int64(privacy.bodyByteLimit - buffer.bytes.count)))
            guard requested > 0 else { return .recorded }
            do {
                let bytes = try copyPrefix(requested)
                guard bytes.count == requested else { buffer.hasGap = true; buffer.bytes.removeAll(); return .invalid }
                buffer.bytes.append(bytes)
                return .recorded
            } catch { buffer.hasGap = true; buffer.bytes.removeAll(); return .invalid }
        }
    }

    @discardableResult
    public func receiveResponse(_ token: HTTPExchangeToken, statusCode: Int, headers: [HTTPHeader],
                                bodyIsDecoded: Bool = false, url: String? = nil) -> HTTPRecordResult {
        update(token) { exchange, _ in
            guard (100...599).contains(statusCode), exchange.event.state == .pending else { return .invalid }
            let sanitized = privacy.headers(headers)
            exchange.response.supportsText = privacy.supportsBody(headers, decoded: bodyIsDecoded)
            exchange.event = HTTPEvent(previous: exchange.event, headers: sanitized.values,
                                       limited: sanitized.limited, code: statusCode, responseURL: url.map(privacy.url), state: .receiving)
            return write(HTTPUpdate(event: exchange.event, isNew: false))
        }
    }

    /// Completed means the collector confirmed response-body completion, not server-side business success.
    @discardableResult
    public func finish(_ token: HTTPExchangeToken, state: HTTPState, failure: HTTPFailure? = nil,
                       requestComplete: Bool = true, responseComplete: Bool = true) -> HTTPRecordResult {
        update(token) { exchange, now in
            guard [.completed, .failed, .cancelled, .closedEarly].contains(state),
                  state != .completed || exchange.event.statusCode != nil,
                  (state == .failed) == (failure != nil) else { return .invalid }
            let request = finalize(exchange.request, id: token.id + ".request", complete: requestComplete, field: .requestBody)
            let response = finalize(exchange.response, id: token.id + ".response", complete: responseComplete && state == .completed, field: .responseBody)
            exchange.event = HTTPEvent(previous: exchange.event, state: state, failure: failure,
                                       duration: now >= exchange.started ? now - exchange.started : nil,
                                       request: request.reference, response: response.reference)
            let result = write(HTTPUpdate(event: exchange.event, isNew: false,
                                         bodies: [request.body, response.body].compactMap { $0 }))
            // A terminal observation is never retried with raw content, even if storage is unavailable.
            active.removeValue(forKey: token.id)
            return result
        }
    }

    /// Performs lazy timeout/expiry cleanup. Useful when refreshing the inspector without new traffic.
    @discardableResult
    public func maintenance() -> HTTPRecordResult {
        captureController.inspect { status in
            guard status.mode == .detailed else { return HTTPRecordResult.inactive }
            expire(at: captureController.clock.monotonicMillis())
            return HTTPRecordResult.recorded
        } ?? .busy
    }

    /// Ends SDK observation without asserting a transport outcome or cancelling the host request.
    @discardableResult
    public func stopObserving(_ token: HTTPExchangeToken) -> HTTPRecordResult {
        update(token) { exchange, _ in
            let result = endObservation(exchange)
            active.removeValue(forKey: token.id)
            return result
        }
    }

    func captureSessionDidEnd() {
        for exchange in active.values { captureController.statistics.observe(endObservation(exchange)) }
        active.removeAll()
    }

    private func expire(at now: UInt64) {
        let expired = active.filter { now >= $0.value.started && now - $0.value.started >= exchangeLifetimeMillis }.map(\.key)
        for id in expired {
            if let exchange = active.removeValue(forKey: id) { captureController.statistics.observe(endObservation(exchange)) }
        }
    }

    private func endObservation(_ exchange: Exchange) -> HTTPRecordResult {
        let state: HTTPBodyState = privacy.bodyPolicy == .omit ? .omitted : .incomplete
        let event = HTTPEvent(previous: exchange.event, state: .captureEnded,
                              request: HTTPBodyReference(state: state, blobID: nil, observedBytes: exchange.request.observed),
                              response: HTTPBodyReference(state: state, blobID: nil, observedBytes: exchange.response.observed))
        return write(HTTPUpdate(event: event, isNew: false))
    }

    private func update(_ token: HTTPExchangeToken, _ body: (Exchange, UInt64) -> HTTPRecordResult) -> HTTPRecordResult {
        result(captureController.record(requiresDetailed: true) { context in
            expire(at: context.monotonicMillis)
            guard token.sessionID == context.sessionID, let exchange = active[token.id] else { return .stale }
            return body(exchange, context.monotonicMillis)
        })
    }

    private func result(_ access: CaptureAccess<HTTPRecordResult>) -> HTTPRecordResult {
        let observed: HTTPRecordResult
        switch access {
        case .value(let result): observed = result
        case .inactive: observed = .inactive
        case .busy: observed = .busy
        case .sequenceExhausted: observed = .sequenceExhausted
        }
        return captureController.statistics.observe(observed)
    }

    private func write(_ update: HTTPUpdate) -> HTTPRecordResult {
        do {
            switch try sink.write(update) {
            case .stored: return .recorded
            case .busy: return .busy
            case .tooLarge: return .tooLarge
            case .missing: return .missing
            }
        } catch { return .sinkFailure }
    }

    private func finalize(_ buffer: BodyBuffer, id: String, complete: Bool, field: DiagnosticTextPolicy.Field) -> (reference: HTTPBodyReference, body: HTTPBody?) {
        func omitted(_ state: HTTPBodyState) -> (HTTPBodyReference, HTTPBody?) {
            (HTTPBodyReference(state: state, blobID: nil, observedBytes: buffer.observed), nil)
        }
        guard privacy.bodyPolicy != .omit else { return omitted(.omitted) }
        guard complete, !buffer.hasGap else { return omitted(.incomplete) }
        guard buffer.supportsText else { return omitted(.unsupported) }
        let truncated = buffer.observed > buffer.bytes.count
        var bytes = buffer.bytes
        if truncated { bytes = Self.completeUTF8Prefix(bytes) }
        guard let text = String(data: bytes, encoding: .utf8) else { return omitted(.invalidText) }
        guard let processed = privacy.textPolicy.apply(text, field: field, truncated: truncated, limit: privacy.bodyByteLimit) else {
            return omitted(.omitted)
        }
        let body = HTTPBody(id: id, text: processed)
        return (HTTPBodyReference(state: truncated ? .truncated : .available, blobID: id,
                                 observedBytes: buffer.observed, isProcessed: processed != text), body)
    }

    private static func completeUTF8Prefix(_ bytes: Data) -> Data {
        guard !bytes.isEmpty else { return bytes }
        let values = Array(bytes.suffix(4))
        var index = values.count - 1
        while index > 0 && (values[index] & 0xC0) == 0x80 { index -= 1 }
        let first = values[index]
        let expected = (0xC2...0xDF).contains(first) ? 2 : (0xE0...0xEF).contains(first) ? 3 : (0xF0...0xF4).contains(first) ? 4 : 1
        let actual = values.count - index
        if actual > 1 {
            let second = values[index + 1]
            if (first == 0xE0 && second < 0xA0) || (first == 0xED && second > 0x9F)
                || (first == 0xF0 && second < 0x90) || (first == 0xF4 && second > 0x8F) { return bytes }
        }
        return expected > actual ? Data(bytes.dropLast(actual)) : bytes
    }
}
