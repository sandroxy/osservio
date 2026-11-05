import Foundation
import OsservioCore

/// Metadata and body budgets are independent. All captured content has already passed Core's privacy policy.
public final class MemoryHTTPStore: HTTPSink, HTTPHistory, @unchecked Sendable {
    private let lock = NSLock()
    private let recordLimit: Int
    private let textByteLimit: Int
    private let bodyByteLimit: Int
    private var order: [String] = []
    private var events: [String: HTTPEvent] = [:]
    private var bodyOrder: [String] = []
    private var bodies: [String: HTTPBody] = [:]
    private var textBytes = 0
    private var bodyBytes = 0
    private var evictedEvents: Int64 = 0
    private var evictedBodies: Int64 = 0
    private var revision: UInt64 = 0

    public init(recordLimit: Int = 200, textByteLimit: Int = 262_144, bodyByteLimit: Int = 524_288) throws {
        guard (1...2_000).contains(recordLimit), (1...16_777_216).contains(textByteLimit),
              (0...16_777_216).contains(bodyByteLimit) else { throw LogStoreConfigurationError.invalidLimit }
        self.recordLimit = recordLimit; self.textByteLimit = textByteLimit; self.bodyByteLimit = bodyByteLimit
    }

    public func write(_ update: HTTPUpdate) -> HTTPWriteResult {
        guard lock.try() else { return .busy }
        defer { lock.unlock() }
        let event = update.event
        guard event.textByteCount <= textByteLimit else { return .tooLarge }
        let previous = events[event.id]
        if update.isNew {
            guard previous == nil else { return .missing }
        } else {
            guard let previous = previous, event.revision > previous.revision else { return .missing }
        }
        let previousSize = previous?.textByteCount ?? 0
        while !order.isEmpty && ((update.isNew && events.count >= recordLimit)
            || textBytes - previousSize > textByteLimit - event.textByteCount) {
            guard let oldest = order.first(where: { $0 != event.id }) else { break }
            removeEvent(oldest)
            if evictedEvents < Int64.max { evictedEvents += 1 }
        }
        if previous == nil { order.append(event.id) }
        events[event.id] = event
        textBytes += event.textByteCount - previousSize
        for body in update.bodies {
            guard body.byteCount <= bodyByteLimit, bodyByteLimit > 0 else {
                if evictedBodies < Int64.max { evictedBodies += 1 }
                continue
            }
            if bodies[body.id] != nil { removeBody(body.id, evicted: false) }
            while bodyBytes > bodyByteLimit - body.byteCount, let oldest = bodyOrder.first {
                removeBody(oldest, evicted: true)
            }
            bodies[body.id] = body; bodyOrder.append(body.id); bodyBytes += body.byteCount
        }
        revision &+= 1
        return .stored
    }

    public func snapshot() -> HTTPSnapshot? {
        guard lock.try() else { return nil }
        defer { lock.unlock() }
        return HTTPSnapshot(events: order.compactMap { events[$0] }, retainedTextBytes: textBytes,
                            retainedBodyBytes: bodyBytes, evictedEvents: evictedEvents,
                            evictedBodies: evictedBodies, revision: revision)
    }

    public func body(id: String) -> HTTPBodyLookup {
        guard lock.try() else { return .busy }
        defer { lock.unlock() }
        return bodies[id].map(HTTPBodyLookup.available) ?? .unavailable
    }

    public func clear() {
        lock.lock()
        defer { lock.unlock() }
        order.removeAll(); events.removeAll(); bodyOrder.removeAll(); bodies.removeAll()
        textBytes = 0; bodyBytes = 0; revision &+= 1
    }

    private func removeEvent(_ id: String) {
        if let event = events.removeValue(forKey: id) {
            textBytes -= event.textByteCount
            removeBody(id + ".request", evicted: true)
            removeBody(id + ".response", evicted: true)
        }
        order.removeAll { $0 == id }
    }

    private func removeBody(_ id: String, evicted: Bool) {
        if let body = bodies.removeValue(forKey: id) {
            bodyBytes -= body.byteCount
            if evicted, evictedBodies < Int64.max { evictedBodies += 1 }
        }
        bodyOrder.removeAll { $0 == id }
    }
}
