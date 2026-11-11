import Foundation

public enum LogLevel: String, CaseIterable, Sendable {
    case debug, info, warning, error
}

/// A host classification of message content. Private input is discarded immediately.
public struct LogMessage: Sendable {
    let text: String
    let isPrivate: Bool

    public static func privateText(_ text: String) -> LogMessage {
        LogMessage(text: "[private]", isPrivate: true)
    }

    /// Retains host-authorized diagnostic text; this does not classify it as publicly distributable.
    public static func publicText(_ text: String) -> LogMessage {
        LogMessage(text: text, isPrivate: false)
    }
}

/// Immutable evidence after admission, privacy handling, and UTF-8 bounding.
public struct LogEvent: Sendable {
    public let sessionID: String
    public let sequence: Int64
    public let timestampMillis: Int64
    public let level: LogLevel
    public let category: String
    public let message: String
    public let isPrivate: Bool
    public let isTruncated: Bool
    public let textByteCount: Int

    init(sessionID: String, sequence: Int64, timestampMillis: Int64,
         level: LogLevel, category: String, message: String,
         isPrivate: Bool, isTruncated: Bool) {
        self.sessionID = sessionID
        self.sequence = sequence
        self.timestampMillis = timestampMillis
        self.level = level
        self.category = category
        self.message = message
        self.isPrivate = isPrivate
        self.isTruncated = isTruncated
        textByteCount = category.utf8.count + message.utf8.count
    }

    /// Restores already sanitized evidence. Oversized or inconsistent values are rejected, never truncated.
    public static func restore(sessionID: String, sequence: Int64, timestampMillis: Int64,
                               level: LogLevel, category: String, message: String,
                               isPrivate: Bool, isTruncated: Bool) throws -> LogEvent {
        guard EvidenceValidation.id(sessionID), sequence > 0, EvidenceValidation.label(category),
              EvidenceValidation.text(message, limit: 65_536),
              !isPrivate || (message == "[private]" && !isTruncated) else { throw PersistenceError.invalidRecord }
        return LogEvent(sessionID: sessionID, sequence: sequence, timestampMillis: timestampMillis,
            level: level, category: category, message: message, isPrivate: isPrivate, isTruncated: isTruncated)
    }
}

public enum LogWriteResult: Sendable { case stored, busy, tooLarge }
public enum LogCaptureResult: Sendable {
    case recorded, inactive, rateLimited, busy, invalidCategory, invalidMessage, tooLarge, sinkFailure, sequenceExhausted
}

public protocol LogSink: Sendable {
    /// Must return promptly without waiting for a lock, disk, or network.
    func append(_ event: LogEvent) throws -> LogWriteResult
}

public struct LogSnapshot: Sendable {
    public let events: [LogEvent]
    public let retainedTextBytes: Int
    public let evictedCount: Int64
    public let revision: UInt64

    public init(events: [LogEvent], retainedTextBytes: Int, evictedCount: Int64, revision: UInt64) {
        self.events = events
        self.retainedTextBytes = retainedTextBytes
        self.evictedCount = evictedCount
        self.revision = revision
    }
}

public protocol LogHistory: Sendable {
    /// Nil means contention, not an empty history. Retain the previous view.
    func snapshot() -> LogSnapshot?
    /// Clears this live history. Does not start or stop recording.
    func clear()
}

public protocol LogClock: Sendable {
    func monotonicMillis() -> UInt64
    func wallTimeMillis() -> Int64
}

public struct SystemLogClock: LogClock {
    public init() {}
    public func monotonicMillis() -> UInt64 { DispatchTime.now().uptimeNanoseconds / 1_000_000 }
    public func wallTimeMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1_000) }
}

public struct LogCaptureStatus: Sendable {
    public let isRecording: Bool
    public let remainingMillis: UInt64
    public let rateLimitedCount: Int64
}
