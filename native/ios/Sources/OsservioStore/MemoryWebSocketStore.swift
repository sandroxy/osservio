import Foundation
import OsservioCore

/// Append-only observations and separately evictable text payloads. No socket state is reconstructed.
public final class MemoryWebSocketStore: WebSocketSink, WebSocketHistory, @unchecked Sendable {
    private let lock = NSLock()
    private let recordLimit: Int
    private let textByteLimit: Int
    private let payloadByteLimit: Int
    private var events: [WebSocketEvent] = []
    private var payloads: [String: WebSocketPayload] = [:]
    private var payloadOrder: [String] = []
    private var textBytes = 0
    private var payloadBytes = 0
    private var evictedEvents: Int64 = 0
    private var evictedPayloads: Int64 = 0
    private var revision: UInt64 = 0

    public init(recordLimit: Int = 500, textByteLimit: Int = 262_144, payloadByteLimit: Int = 262_144) throws {
        guard (1...2_000).contains(recordLimit), (1...16_777_216).contains(textByteLimit),
              (0...16_777_216).contains(payloadByteLimit) else { throw LogStoreConfigurationError.invalidLimit }
        self.recordLimit = recordLimit; self.textByteLimit = textByteLimit; self.payloadByteLimit = payloadByteLimit
    }
    public func append(_ event: WebSocketEvent, payload: WebSocketPayload?) -> WebSocketWriteResult {
        guard lock.try() else { return .busy }
        defer { lock.unlock() }
        guard event.textByteCount <= textByteLimit else { return .tooLarge }
        // Duplicate deliveries are idempotent and cannot replace retained evidence.
        if events.contains(where: { $0.id == event.id }) { return .stored }
        while !events.isEmpty && (events.count >= recordLimit || textBytes > textByteLimit - event.textByteCount) {
            let oldest = events.removeFirst(); textBytes -= oldest.textByteCount
            if evictedEvents < Int64.max { evictedEvents += 1 }
            removePayload(oldest.id)
        }
        events.append(event); textBytes += event.textByteCount
        if let payload, payload.id == event.payload?.blobID {
            if payloadByteLimit > 0 && payload.byteCount <= payloadByteLimit {
                while payloadBytes > payloadByteLimit - payload.byteCount, let first = payloadOrder.first { removePayload(first) }
                payloads[payload.id] = payload; payloadOrder.append(payload.id); payloadBytes += payload.byteCount
            } else if evictedPayloads < Int64.max { evictedPayloads += 1 }
        }
        revision &+= 1; return .stored
    }
    public func snapshot() -> WebSocketSnapshot? {
        guard lock.try() else { return nil }
        defer { lock.unlock() }
        return WebSocketSnapshot(events: events, retainedTextBytes: textBytes, retainedPayloadBytes: payloadBytes,
                                 evictedEvents: evictedEvents, evictedPayloads: evictedPayloads, revision: revision)
    }
    public func payload(id: String) -> WebSocketPayloadLookup {
        guard lock.try() else { return .busy }
        defer { lock.unlock() }
        return payloads[id].map(WebSocketPayloadLookup.available) ?? .unavailable
    }
    public func clear() {
        lock.lock(); defer { lock.unlock() }
        events.removeAll(); payloads.removeAll(); payloadOrder.removeAll()
        textBytes = 0; payloadBytes = 0; revision &+= 1
    }
    private func removePayload(_ id: String) {
        if let body = payloads.removeValue(forKey: id) {
            payloadBytes -= body.byteCount
            if evictedPayloads < Int64.max { evictedPayloads += 1 }
        }
        payloadOrder.removeAll { $0 == id }
    }
}
