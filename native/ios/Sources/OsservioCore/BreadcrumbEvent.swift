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
