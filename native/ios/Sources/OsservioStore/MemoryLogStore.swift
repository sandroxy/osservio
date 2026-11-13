import Foundation
import OsservioCore

public enum LogStoreConfigurationError: Error { case invalidLimit }

/// Bounded by event count and UTF-8 text bytes. Object overhead is bounded by count.
public final class MemoryLogStore: LogDetailsSink, LogDetailsHistory, @unchecked Sendable {
    private let lock = NSLock()
    private struct Entry { let event: LogEvent; let admittedAt: UInt64 }
    private var entries: [Entry?]
    private let byteLimit: Int
    private let errorReserve: Int
    private let errorProtectionMillis: UInt64
    private let clock: any LogClock
    private var head = 0
    private var count = 0
    private var bytes = 0
    private var errors = 0
    private var evicted: Int64 = 0
    private var revision: UInt64 = 0
    private let detailsByteLimit: Int
    private var detailOrder: [String] = []
    private var detailContents: [String: LogDetailsContent] = [:]
    private var detailBytes = 0
    private var evictedDetails: Int64 = 0

    public init(recordLimit: Int = 500, textByteLimit: Int = 524_288, errorReserve: Int? = nil,
                errorProtectionMillis: UInt64 = 30_000, clock: any LogClock = SystemLogClock(), detailsByteLimit: Int = 262_144) throws {
        let reserve = errorReserve ?? min(32, max(1, recordLimit / 4))
        guard (1...10_000).contains(recordLimit), (1...16_777_216).contains(textByteLimit),
              (0...min(64, recordLimit)).contains(reserve), (1...300_000).contains(errorProtectionMillis), (0...16_777_216).contains(detailsByteLimit) else {
            throw LogStoreConfigurationError.invalidLimit
        }
        entries = Array(repeating: nil, count: recordLimit)
        byteLimit = textByteLimit
        self.errorReserve = reserve; self.errorProtectionMillis = errorProtectionMillis; self.clock = clock
        self.detailsByteLimit = detailsByteLimit
    }

    public func append(_ event: LogEvent) -> LogWriteResult {
        write(event, details: nil)
    }
    public func append(_ event: LogEvent, details: LogDetailsContent) throws -> LogWriteResult {
        guard event.details?.state == .available, event.details?.blobID == details.id,
              event.details?.textByteCount == details.byteCount else { throw PersistenceError.invalidRecord }
        return write(event, details: details)
    }
    private func write(_ event: LogEvent, details: LogDetailsContent?) -> LogWriteResult {
        guard lock.try() else { return .busy }
        defer { lock.unlock() }
        guard event.textByteCount <= byteLimit else { return .tooLarge }
        let now = clock.monotonicMillis(), incomingErrors = event.level == .error ? 1 : 0
        var remainingErrors = errors + incomingErrors, availableCount = count, availableBytes = bytes
        // Preflight avoids deleting useful history when protected entries prevent this append.
        for offset in 0..<count {
            if availableCount < entries.count && availableBytes <= byteLimit - event.textByteCount { break }
            guard let entry = entries[(head + offset) % entries.count] else { continue }
            let protected = isProtected(entry, remainingErrors: remainingErrors, now: now)
            if entry.event.level == .error { remainingErrors -= 1 }
            if !protected { availableCount -= 1; availableBytes -= entry.event.textByteCount }
        }
        guard availableCount < entries.count && availableBytes <= byteLimit - event.textByteCount else { return .protectedCapacity }
        remainingErrors = errors + incomingErrors
        var offset = 0
        while count > 0 && (count == entries.count || bytes > byteLimit - event.textByteCount) {
            guard let entry = entries[(head + offset) % entries.count] else { preconditionFailure("Missing log entry") }
            let protected = isProtected(entry, remainingErrors: remainingErrors, now: now)
            if entry.event.level == .error { remainingErrors -= 1 }
            if protected { offset += 1; continue }
            bytes -= entry.event.textByteCount
            if let id = entry.event.details?.blobID { removeDetails(id) }
            if entry.event.level == .error { errors -= 1 }
            // Only the protected prefix moves; it is bounded by the error reserve (at most 64).
            for index in stride(from: offset, to: 0, by: -1) {
                entries[(head + index) % entries.count] = entries[(head + index - 1) % entries.count]
            }
            entries[head] = nil
            head = (head + 1) % entries.count
            count -= 1
            if evicted < Int64.max { evicted += 1 }
        }
        entries[(head + count) % entries.count] = Entry(event: event, admittedAt: now)
        count += 1
        errors += incomingErrors
        bytes += event.textByteCount
        if let details {
            if details.byteCount > detailsByteLimit {
                if evictedDetails < Int64.max { evictedDetails += 1 }
            } else {
                while detailBytes > detailsByteLimit - details.byteCount, let oldest = detailOrder.first { removeDetails(oldest) }
                if detailContents[details.id] != nil { removeDetails(details.id) }
                detailContents[details.id] = details; detailOrder.append(details.id); detailBytes += details.byteCount
            }
        }
        revision &+= 1
        return .stored
    }

    private func isProtected(_ entry: Entry, remainingErrors: Int, now: UInt64) -> Bool {
        entry.event.level == .error && remainingErrors <= errorReserve && now >= entry.admittedAt
            && now - entry.admittedAt < errorProtectionMillis
    }

    public func snapshot() -> LogSnapshot? {
        guard lock.try() else { return nil }
        defer { lock.unlock() }
        let events = (0..<count).compactMap { entries[(head + $0) % entries.count]?.event }
        return LogSnapshot(events: events, retainedTextBytes: bytes, evictedCount: evicted, revision: revision,
                           retainedDetailsBytes: detailBytes, evictedDetails: evictedDetails)
    }

    public func details(id: String) -> LogDetailsLookup {
        guard lock.try() else { return .busy }; defer { lock.unlock() }
        return detailContents[id].map(LogDetailsLookup.available) ?? .unavailable
    }
    private func removeDetails(_ id: String) {
        guard let content = detailContents.removeValue(forKey: id) else { return }
        detailBytes -= content.byteCount; detailOrder.removeAll { $0 == id }
        if evictedDetails < Int64.max { evictedDetails += 1 }
    }

    public func clear() {
        lock.lock()
        defer { lock.unlock() }
        entries = Array(repeating: nil, count: entries.count)
        head = 0
        count = 0
        bytes = 0
        errors = 0
        detailContents.removeAll(); detailOrder.removeAll(); detailBytes = 0
        revision &+= 1
    }
}
