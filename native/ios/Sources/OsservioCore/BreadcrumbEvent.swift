import Foundation

/// Static categories describe observations, not an inferred cause of a crash or a blank screen.
public enum BreadcrumbKind: String, Sendable {
    case hostMarker, appActive, appInactive, appForeground, appBackground
    case activityStarted, activityResumed, activityPaused, activityStopped, activityDestroyed
    case memoryWarning, memoryTrim, thermalState, responsiveness
}
public enum BreadcrumbRetention: String, Sendable { case memoryOnly, restartRelevant }

public struct BreadcrumbEvent: Sendable {
    public let id: String
    public let sessionID: String
    public let sequence: Int64
    public let timestampMillis: Int64
    /// Elapsed monotonic time within this capture session, not device boot time.
    public let sessionElapsedMillis: UInt64?
    public let kind: BreadcrumbKind
    /// A static ASCII label. Never a user ID, URL, or arbitrary object description.
    public let label: String
    public let message: String?
    public let isPrivate: Bool
    public let isTruncated: Bool
    /// A platform-defined code or measured value; its meaning depends on the kind and label.
    public let value: Int64?
    public let retention: BreadcrumbRetention
    public let textByteCount: Int

    init(context: CaptureContext, timestamp: Int64, kind: BreadcrumbKind, label: String,
         message: String?, isPrivate: Bool, isTruncated: Bool, value: Int64?, retention: BreadcrumbRetention) {
        id = UUID().uuidString; sessionID = context.sessionID; sequence = context.sequence
        timestampMillis = timestamp
        sessionElapsedMillis = context.monotonicMillis >= context.sessionStartMillis
            ? context.monotonicMillis - context.sessionStartMillis : nil
        self.kind = kind; self.label = label; self.message = message
        self.isPrivate = isPrivate; self.isTruncated = isTruncated; self.value = value; self.retention = retention
        textByteCount = label.utf8.count + (message?.utf8.count ?? 0)
    }

    /// Reconstitutes previously sanitized evidence. Callers must not use this to classify raw text as public.
    /// Invalid persisted values fail instead of being truncated, renumbered, or given a new identity.
    public static func restore(id: String, sessionID: String, sequence: Int64, timestampMillis: Int64,
                               sessionElapsedMillis: UInt64?, kind: BreadcrumbKind, label: String,
                               message: String?, isPrivate: Bool, isTruncated: Bool, value: Int64?,
                               retention: BreadcrumbRetention) throws -> BreadcrumbEvent {
        guard validUUID(id), validUUID(sessionID), sequence > 0, validBreadcrumbLabel(label),
              !isPrivate || (message == "[private]" && !isTruncated),
              !isTruncated || message != nil else { throw PersistenceError.invalidRecord }
        if let message {
            guard let bounded = BoundedText.prefix(message, bytes: 4_096), !bounded.truncated else { throw PersistenceError.invalidRecord }
        }
        return BreadcrumbEvent(id: id, sessionID: sessionID, sequence: sequence, timestampMillis: timestampMillis,
            sessionElapsedMillis: sessionElapsedMillis, kind: kind, label: label, message: message,
            isPrivate: isPrivate, isTruncated: isTruncated, value: value, retention: retention)
    }
    private init(id: String, sessionID: String, sequence: Int64, timestampMillis: Int64,
                 sessionElapsedMillis: UInt64?, kind: BreadcrumbKind, label: String, message: String?,
                 isPrivate: Bool, isTruncated: Bool, value: Int64?, retention: BreadcrumbRetention) {
        self.id = id; self.sessionID = sessionID; self.sequence = sequence; self.timestampMillis = timestampMillis
        self.sessionElapsedMillis = sessionElapsedMillis; self.kind = kind; self.label = label; self.message = message
        self.isPrivate = isPrivate; self.isTruncated = isTruncated; self.value = value; self.retention = retention
        textByteCount = label.utf8.count + (message?.utf8.count ?? 0)
    }
}

func validBreadcrumbLabel(_ label: String) -> Bool {
    let bytes = Array(label.utf8.prefix(65))
    return !bytes.isEmpty && bytes.count <= 64 && bytes.allSatisfy {
        (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0) || $0 == 45 || $0 == 46 || $0 == 95
    }
}
private func validUUID(_ id: String) -> Bool {
    let bytes = Array(id.utf8.prefix(37))
    guard bytes.count == 36 else { return false }
    for (index, byte) in bytes.enumerated() {
        if index == 8 || index == 13 || index == 18 || index == 23 { if byte != 45 { return false } }
        else if !((48...57).contains(byte) || (65...70).contains(byte) || (97...102).contains(byte)) { return false }
    }
    return true
}
public enum BreadcrumbWriteResult: Sendable { case stored, busy, tooLarge }
public protocol BreadcrumbSink: Sendable {
    /// Accepts only admitted, sanitized events. Restart relevance is intent, not a durability guarantee.
    func append(_ event: BreadcrumbEvent) throws -> BreadcrumbWriteResult
}
public struct BreadcrumbSnapshot: Sendable {
    public let events: [BreadcrumbEvent]
    public let retainedTextBytes: Int
    public let evictedCount: Int64
    public let revision: UInt64
    public init(events: [BreadcrumbEvent], retainedTextBytes: Int, evictedCount: Int64, revision: UInt64) {
        self.events = events; self.retainedTextBytes = retainedTextBytes; self.evictedCount = evictedCount; self.revision = revision
    }
}
public protocol BreadcrumbHistory: Sendable {
    func snapshot() -> BreadcrumbSnapshot?
    func clear()
}
