import Foundation
import OsservioCore

public enum LogStoreConfigurationError: Error { case invalidLimit }

/// Bounded by event count and UTF-8 text bytes. Object overhead is bounded by count.
public final class MemoryLogStore: LogSink, LogHistory, @unchecked Sendable {
    private let lock = NSLock()
    private var entries: [LogEvent?]
    private let byteLimit: Int
    private var head = 0
    private var count = 0
    private var bytes = 0
    private var evicted: Int64 = 0
    private var revision: UInt64 = 0

    public init(recordLimit: Int = 500, textByteLimit: Int = 524_288) throws {
        guard (1...10_000).contains(recordLimit), (1...16_777_216).contains(textByteLimit) else {
            throw LogStoreConfigurationError.invalidLimit
        }
        entries = Array(repeating: nil, count: recordLimit)
        byteLimit = textByteLimit
    }

    public func append(_ event: LogEvent) -> LogWriteResult {
        guard lock.try() else { return .busy }
        defer { lock.unlock() }
        guard event.textByteCount <= byteLimit else { return .tooLarge }
        while count > 0 && (count == entries.count || bytes > byteLimit - event.textByteCount) {
            if let oldest = entries[head] { bytes -= oldest.textByteCount }
            entries[head] = nil
            head = (head + 1) % entries.count
            count -= 1
            if evicted < Int64.max { evicted += 1 }
        }
        entries[(head + count) % entries.count] = event
        count += 1
        bytes += event.textByteCount
        revision &+= 1
        return .stored
    }

    public func snapshot() -> LogSnapshot? {
        guard lock.try() else { return nil }
        defer { lock.unlock() }
        let events = (0..<count).compactMap { entries[(head + $0) % entries.count] }
        return LogSnapshot(events: events, retainedTextBytes: bytes, evictedCount: evicted, revision: revision)
    }

    public func clear() {
        lock.lock()
        defer { lock.unlock() }
        entries = Array(repeating: nil, count: entries.count)
        head = 0
        count = 0
        bytes = 0
        revision &+= 1
    }
}
