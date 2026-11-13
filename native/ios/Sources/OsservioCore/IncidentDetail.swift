import Foundation

/// Why a previously referenced body is or is not included in this explicit snapshot.
public enum IncidentPayloadState: String, Sendable { case available, notSelected, unavailable, busy, budgetExceeded }

/// Body text is separate from event metadata. Available empty text is distinct from missing content.
public struct IncidentPayload: Sendable {
    public let id: String
    public let state: IncidentPayloadState
    public let text: String?
    public let textByteCount: Int

    /// Restores already sanitized body text; this method is not a raw-body privacy policy.
    public static func restore(id: String, state: IncidentPayloadState, text: String?) throws -> IncidentPayload {
        guard EvidenceValidation.id(id) || EvidenceValidation.httpBodyID(id),
              (state == .available) == (text != nil), text.map({ EvidenceValidation.text($0, limit: 65_536) }) ?? true else {
            throw PersistenceError.invalidRecord
        }
        return IncidentPayload(id: id, state: state, text: text, textByteCount: text?.utf8.count ?? 0)
    }
}

public struct IncidentLogs: Sendable {
    public let observedAtMillis: Int64
    public let events: [LogEvent]
    public let sourceEventCount: Int
    public let sourceEvictedCount: Int64
    public let retainedTextBytes: Int
    public let payloads: [String: IncidentPayload]
    public let retainedPayloadBytes: Int
    public let sourceEvictedDetails: Int64
    public var omittedEventCount: Int { sourceEventCount - events.count }

    public static func capture(_ source: LogSnapshot, observedAtMillis: Int64,
                               eventLimit: Int = 200, textByteLimit: Int = 65_536,
                               history: (any LogDetailsHistory)? = nil, includeDetails: Bool = false, payloadByteLimit: Int = 65_536) throws -> IncidentLogs {
        let chosen = try IncidentWindow.select(source.events, count: eventLimit, bytes: textByteLimit, size: { $0.textByteCount })
        return try restore(events: chosen, observedAtMillis: observedAtMillis, sourceEventCount: source.events.count, sourceEvictedCount: source.evictedCount,
            payloads: selectPayloads(chosen, history: history, include: includeDetails, byteLimit: payloadByteLimit), sourceEvictedDetails: source.evictedDetails)
    }
    public func includingDetails(from history: any LogDetailsHistory, payloadByteLimit: Int = 65_536) throws -> IncidentLogs {
        try Self.restore(events: events, observedAtMillis: observedAtMillis, sourceEventCount: sourceEventCount, sourceEvictedCount: sourceEvictedCount,
            payloads: Self.selectPayloads(events, history: history, include: true, byteLimit: payloadByteLimit), sourceEvictedDetails: sourceEvictedDetails)
    }
    private static func selectPayloads(_ events: [LogEvent], history: (any LogDetailsHistory)?, include: Bool, byteLimit: Int) throws -> [String: IncidentPayload] {
        guard (0...262_144).contains(byteLimit) else { throw CaptureConfigurationError.invalidLimit }
        var payloads: [String: IncidentPayload] = [:], remaining = byteLimit
        for event in events.reversed() {
            guard let id = event.details?.blobID else { continue }
            var state: IncidentPayloadState = include ? .unavailable : .notSelected, text: String?
            if include, let history {
                switch history.details(id: id) {
                case .available(let content):
                    guard content.id == id, content.byteCount == event.details?.textByteCount else { throw PersistenceError.invalidRecord }
                    state = content.byteCount <= remaining ? .available : .budgetExceeded
                    if state == .available { text = content.text; remaining -= content.byteCount }
                case .unavailable: break
                case .busy: state = .busy
                }
            }
            guard payloads.updateValue(try .restore(id: id, state: state, text: text), forKey: id) == nil else { throw PersistenceError.invalidRecord }
        }
        return payloads
    }
    public static func restore(events: [LogEvent], observedAtMillis: Int64, sourceEventCount: Int, sourceEvictedCount: Int64,
                               payloads: [String: IncidentPayload] = [:], sourceEvictedDetails: Int64 = 0) throws -> IncidentLogs {
        let bytes = try IncidentWindow.validate(events, sourceCount: sourceEventCount, evicted: sourceEvictedCount,
            size: { $0.textByteCount }, identity: { $0.sessionID.lowercased() + ":" + String($0.sequence) })
        let payloadBytes = try IncidentWindow.payloadBytes(payloads, maximumCount: events.count)
        guard sourceEvictedDetails >= 0 else { throw PersistenceError.invalidRecord }
        var references = Set<String>()
        for event in events {
            guard let reference = event.details, let id = reference.blobID else { continue }
            guard references.insert(id).inserted, let payload = payloads[id],
                  payload.state != .available || payload.textByteCount == reference.textByteCount else { throw PersistenceError.invalidRecord }
        }
        guard references.count == payloads.count else { throw PersistenceError.invalidRecord }
        return IncidentLogs(observedAtMillis: observedAtMillis, events: events, sourceEventCount: sourceEventCount,
            sourceEvictedCount: sourceEvictedCount, retainedTextBytes: bytes, payloads: payloads,
            retainedPayloadBytes: payloadBytes, sourceEvictedDetails: sourceEvictedDetails)
    }
}

public struct IncidentHTTP: Sendable {
    public let observedAtMillis: Int64
    public let events: [HTTPEvent]
    public let payloads: [String: IncidentPayload]
    public let sourceEventCount: Int
    public let sourceEvictedEvents: Int64
    public let sourceEvictedBodies: Int64
    public let retainedTextBytes: Int
    public let retainedPayloadBytes: Int
    public var omittedEventCount: Int { sourceEventCount - events.count }

    /// Selects the newest contiguous metadata window. Body lookup is bounded and never retries.
    /// The source snapshot and separate body lookups are not an atomic read of the live history.
    public static func capture(_ source: HTTPSnapshot, observedAtMillis: Int64, history: any HTTPHistory,
                               includeBodies: Bool = false, eventLimit: Int = 50, textByteLimit: Int = 65_536,
                               payloadByteLimit: Int = 65_536) throws -> IncidentHTTP {
        guard (0...262_144).contains(payloadByteLimit) else { throw CaptureConfigurationError.invalidLimit }
        let events = try IncidentWindow.select(source.events, count: eventLimit, bytes: textByteLimit, size: { $0.textByteCount })
        let payloads = try selectPayloads(events, history: history, include: includeBodies, byteLimit: payloadByteLimit)
        return try restore(events: events, payloads: payloads, observedAtMillis: observedAtMillis,
            sourceEventCount: source.events.count, sourceEvictedEvents: source.evictedEvents, sourceEvictedBodies: source.evictedBodies)
    }
    /// Returns a new copy with one bounded lookup per frozen body reference. Event metadata,
    /// observation time, and source counters remain unchanged; missing content is not retried.
    public func includingBodies(from history: any HTTPHistory, payloadByteLimit: Int = 65_536) throws -> IncidentHTTP {
        let payloads = try Self.selectPayloads(events, history: history, include: true, byteLimit: payloadByteLimit)
        return try Self.restore(events: events, payloads: payloads, observedAtMillis: observedAtMillis,
            sourceEventCount: sourceEventCount, sourceEvictedEvents: sourceEvictedEvents, sourceEvictedBodies: sourceEvictedBodies)
    }
    private static func selectPayloads(_ events: [HTTPEvent], history: any HTTPHistory,
                                       include: Bool, byteLimit: Int) throws -> [String: IncidentPayload] {
        guard (0...262_144).contains(byteLimit) else { throw CaptureConfigurationError.invalidLimit }
        var payloads: [String: IncidentPayload] = [:], remaining = byteLimit
        // Prefer newer responses, then their requests; never shorten an already captured body a second time.
        for event in events.reversed() {
            for reference in [event.responseBody, event.requestBody] {
                guard let id = reference.blobID else { continue }
                let value: IncidentPayload
                if !include { value = try .restore(id: id, state: .notSelected, text: nil) }
                else {
                    switch history.body(id: id) {
                    case .available(let body):
                        guard body.id == id else { throw PersistenceError.invalidRecord }
                        value = try .restore(id: id, state: body.byteCount <= remaining ? .available : .budgetExceeded,
                            text: body.byteCount <= remaining ? body.text : nil)
                    case .unavailable: value = try .restore(id: id, state: .unavailable, text: nil)
                    case .busy: value = try .restore(id: id, state: .busy, text: nil)
                    }
                }
                guard payloads.updateValue(value, forKey: id) == nil else { throw PersistenceError.invalidRecord }
                remaining -= value.textByteCount
            }
        }
        return payloads
    }
    public static func restore(events: [HTTPEvent], payloads: [String: IncidentPayload], observedAtMillis: Int64,
                               sourceEventCount: Int, sourceEvictedEvents: Int64, sourceEvictedBodies: Int64) throws -> IncidentHTTP {
        let bytes = try IncidentWindow.validate(events, sourceCount: sourceEventCount, evicted: sourceEvictedEvents,
            size: { $0.textByteCount }, identity: { $0.id.lowercased() })
        guard sourceEvictedBodies >= 0 else { throw PersistenceError.invalidRecord }
        let payloadBytes = try IncidentWindow.payloadBytes(payloads, maximumCount: events.count * 2)
        var references = Set<String>()
        for event in events {
            for reference in [event.requestBody, event.responseBody] {
                guard let id = reference.blobID else { continue }
                guard references.insert(id).inserted, let payload = payloads[id] else { throw PersistenceError.invalidRecord }
                if payload.state == .available, !reference.isProcessed {
                    guard reference.state == .available ? Int64(payload.textByteCount) == reference.observedBytes
                        : reference.state == .truncated && Int64(payload.textByteCount) < reference.observedBytes else { throw PersistenceError.invalidRecord }
                }
            }
        }
        guard references.count == payloads.count else { throw PersistenceError.invalidRecord }
        return IncidentHTTP(observedAtMillis: observedAtMillis, events: events, payloads: payloads, sourceEventCount: sourceEventCount,
            sourceEvictedEvents: sourceEvictedEvents, sourceEvictedBodies: sourceEvictedBodies, retainedTextBytes: bytes, retainedPayloadBytes: payloadBytes)
    }
}

public struct IncidentWebSockets: Sendable {
    public let observedAtMillis: Int64
    public let events: [WebSocketEvent]
    public let payloads: [String: IncidentPayload]
    public let sourceEventCount: Int
    public let sourceEvictedEvents: Int64
    public let sourceEvictedPayloads: Int64
    public let retainedTextBytes: Int
    public let retainedPayloadBytes: Int
    public var omittedEventCount: Int { sourceEventCount - events.count }

    public static func capture(_ source: WebSocketSnapshot, observedAtMillis: Int64, history: any WebSocketHistory,
                               includePayloads: Bool = false, eventLimit: Int = 200, textByteLimit: Int = 65_536,
                               payloadByteLimit: Int = 65_536) throws -> IncidentWebSockets {
        guard (0...262_144).contains(payloadByteLimit) else { throw CaptureConfigurationError.invalidLimit }
        let events = try IncidentWindow.select(source.events, count: eventLimit, bytes: textByteLimit, size: { $0.textByteCount })
        let payloads = try selectPayloads(events, history: history, include: includePayloads, byteLimit: payloadByteLimit)
        return try restore(events: events, payloads: payloads, observedAtMillis: observedAtMillis,
            sourceEventCount: source.events.count, sourceEvictedEvents: source.evictedEvents, sourceEvictedPayloads: source.evictedPayloads)
    }
    /// Returns a new copy with one bounded lookup per frozen body reference. Event metadata,
    /// observation time, and source counters remain unchanged; missing content is not retried.
    public func includingPayloads(from history: any WebSocketHistory, payloadByteLimit: Int = 65_536) throws -> IncidentWebSockets {
        let payloads = try Self.selectPayloads(events, history: history, include: true, byteLimit: payloadByteLimit)
        return try Self.restore(events: events, payloads: payloads, observedAtMillis: observedAtMillis,
            sourceEventCount: sourceEventCount, sourceEvictedEvents: sourceEvictedEvents, sourceEvictedPayloads: sourceEvictedPayloads)
    }
    private static func selectPayloads(_ events: [WebSocketEvent], history: any WebSocketHistory,
                                       include: Bool, byteLimit: Int) throws -> [String: IncidentPayload] {
        guard (0...262_144).contains(byteLimit) else { throw CaptureConfigurationError.invalidLimit }
        var payloads: [String: IncidentPayload] = [:], remaining = byteLimit
        for event in events.reversed() {
            guard let id = event.payload?.blobID else { continue }
            let value: IncidentPayload
            if !include { value = try .restore(id: id, state: .notSelected, text: nil) }
            else {
                switch history.payload(id: id) {
                case .available(let payload):
                    guard payload.id == id else { throw PersistenceError.invalidRecord }
                    value = try .restore(id: id, state: payload.byteCount <= remaining ? .available : .budgetExceeded,
                        text: payload.byteCount <= remaining ? payload.text : nil)
                case .unavailable: value = try .restore(id: id, state: .unavailable, text: nil)
                case .busy: value = try .restore(id: id, state: .busy, text: nil)
                }
            }
            guard payloads.updateValue(value, forKey: id) == nil else { throw PersistenceError.invalidRecord }
            remaining -= value.textByteCount
        }
        return payloads
    }
    public static func restore(events: [WebSocketEvent], payloads: [String: IncidentPayload], observedAtMillis: Int64,
                               sourceEventCount: Int, sourceEvictedEvents: Int64, sourceEvictedPayloads: Int64) throws -> IncidentWebSockets {
        let bytes = try IncidentWindow.validate(events, sourceCount: sourceEventCount, evicted: sourceEvictedEvents,
            size: { $0.textByteCount }, identity: { $0.id.lowercased() })
        guard sourceEvictedPayloads >= 0 else { throw PersistenceError.invalidRecord }
        let payloadBytes = try IncidentWindow.payloadBytes(payloads, maximumCount: events.count)
        var references = Set<String>()
        for event in events {
            guard let reference = event.payload, let id = reference.blobID else { continue }
            guard references.insert(id).inserted, let payload = payloads[id] else { throw PersistenceError.invalidRecord }
            if payload.state == .available, reference.state == .available, !reference.isProcessed {
                guard reference.byteCount == Int64(payload.textByteCount) else { throw PersistenceError.invalidRecord }
            }
        }
        guard references.count == payloads.count else { throw PersistenceError.invalidRecord }
        return IncidentWebSockets(observedAtMillis: observedAtMillis, events: events, payloads: payloads, sourceEventCount: sourceEventCount,
            sourceEvictedEvents: sourceEvictedEvents, sourceEvictedPayloads: sourceEvictedPayloads, retainedTextBytes: bytes, retainedPayloadBytes: payloadBytes)
    }
}

private enum IncidentWindow {
    static func select<Event>(_ events: [Event], count: Int, bytes: Int, size: (Event) -> Int) throws -> [Event] {
        guard (1...2_000).contains(count), (64...1_048_576).contains(bytes) else { throw CaptureConfigurationError.invalidLimit }
        var selected: [Event] = [], retained = 0
        for event in events.reversed() {
            let amount = size(event)
            guard selected.count < count, amount <= bytes - retained else { break }
            selected.append(event); retained += amount
        }
        return selected.reversed()
    }
    static func validate<Event>(_ events: [Event], sourceCount: Int, evicted: Int64,
                                size: (Event) -> Int, identity: (Event) -> String) throws -> Int {
        guard events.count <= 2_000, sourceCount >= events.count, sourceCount <= Int32.max, evicted >= 0 else { throw PersistenceError.invalidRecord }
        var identifiers = Set<String>(), bytes = 0
        for event in events {
            guard identifiers.insert(identity(event)).inserted, size(event) <= 1_048_576 - bytes else { throw PersistenceError.invalidRecord }
            bytes += size(event)
        }
        return bytes
    }
    static func payloadBytes(_ payloads: [String: IncidentPayload], maximumCount: Int) throws -> Int {
        guard payloads.count <= maximumCount else { throw PersistenceError.invalidRecord }
        var identifiers = Set<String>(), bytes = 0
        for (id, payload) in payloads {
            guard id == payload.id, identifiers.insert(id.lowercased()).inserted,
                  payload.textByteCount <= 262_144 - bytes else { throw PersistenceError.invalidRecord }
            bytes += payload.textByteCount
        }
        return bytes
    }
}
