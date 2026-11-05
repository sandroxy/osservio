import Foundation
import OsservioCore

/// A bounded recent window. This implementation never persists restart-relevant events.
public final class MemoryBreadcrumbStore: BreadcrumbSink, BreadcrumbHistory, @unchecked Sendable {
    private let lock = NSLock()
    private var entries: [BreadcrumbEvent?]
    private let byteLimit: Int
    private var head = 0
    private var count = 0
    private var bytes = 0
    private var evicted: Int64 = 0
    private var revision: UInt64 = 0

    public init(recordLimit: Int = 200, textByteLimit: Int = 65_536) throws {
        guard (1...2_000).contains(recordLimit), (1...1_048_576).contains(textByteLimit) else {
            throw LogStoreConfigurationError.invalidLimit
        }
        entries = Array(repeating: nil, count: recordLimit); byteLimit = textByteLimit
    }
    public func append(_ event: BreadcrumbEvent) -> BreadcrumbWriteResult {
        guard lock.try() else { return .busy }
        defer { lock.unlock() }
        guard event.textByteCount <= byteLimit else { return .tooLarge }
        while count > 0 && (count == entries.count || bytes > byteLimit - event.textByteCount) {
            if let entry = entries[head] { bytes -= entry.textByteCount }
            entries[head] = nil; head = (head + 1) % entries.count; count -= 1
            if evicted < Int64.max { evicted += 1 }
        }
        entries[(head + count) % entries.count] = event
        count += 1; bytes += event.textByteCount; revision &+= 1
        return .stored
    }
    public func snapshot() -> BreadcrumbSnapshot? {
        guard lock.try() else { return nil }
        defer { lock.unlock() }
        return BreadcrumbSnapshot(events: (0..<count).compactMap { entries[(head + $0) % entries.count] },
                                  retainedTextBytes: bytes, evictedCount: evicted, revision: revision)
    }
    public func clear() {
        lock.lock(); defer { lock.unlock() }
        entries = Array(repeating: nil, count: entries.count); head = 0; count = 0; bytes = 0; revision &+= 1
    }
}
