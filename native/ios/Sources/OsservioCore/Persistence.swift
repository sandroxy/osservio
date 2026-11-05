import Foundation

public enum PersistenceError: Error, Sendable {
    case busy, closed, unavailable, unsupportedSchema, corrupt, storageFull, invalidRecord
}
public enum PersistenceState: Sendable { case opening, ready, failed, closing, closed }

/// Counters describe this store instance. A successful in-memory append is not a durable commit.
public struct PersistenceStatus: Sendable {
    public let state: PersistenceState
    public let queuedEvents: Int
    public let queuedTextBytes: Int
    public let committedEvents: Int64
    public let discardedEvents: Int64
    public let failedBatches: Int64
    public let lastError: PersistenceError?
    public init(state: PersistenceState, queuedEvents: Int, queuedTextBytes: Int, committedEvents: Int64,
                discardedEvents: Int64, failedBatches: Int64, lastError: PersistenceError?) {
        self.state = state; self.queuedEvents = queuedEvents; self.queuedTextBytes = queuedTextBytes
        self.committedEvents = committedEvents; self.discardedEvents = discardedEvents
        self.failedBatches = failedBatches; self.lastError = lastError
    }
}

/// A recording run, not a capture session. Its existence or unfinished state does not prove a crash.
public struct RetainedRun: Sendable {
    public let id: String
    public let platform: EnvironmentPlatform
    public let processID: Int64
    public let recordingStartedAtMillis: Int64
    public let lastRecordedAtMillis: Int64
    public let eventCount: Int
    public let evictedEvents: Int64
    public let isCurrent: Bool
    public init(id: String, platform: EnvironmentPlatform, processID: Int64, recordingStartedAtMillis: Int64,
                lastRecordedAtMillis: Int64, eventCount: Int, evictedEvents: Int64, isCurrent: Bool) {
        self.id = id; self.platform = platform; self.processID = processID
        self.recordingStartedAtMillis = recordingStartedAtMillis; self.lastRecordedAtMillis = lastRecordedAtMillis
        self.eventCount = eventCount; self.evictedEvents = evictedEvents; self.isCurrent = isCurrent
    }
}
public struct RetainedBreadcrumbs: Sendable {
    public let run: RetainedRun
    public let events: [BreadcrumbEvent]
    public init(run: RetainedRun, events: [BreadcrumbEvent]) { self.run = run; self.events = events }
}

/// Asynchronous, bounded access to committed breadcrumb evidence. Callbacks may run on any queue.
public protocol BreadcrumbArchive: Sendable {
    func persistenceStatus() -> PersistenceStatus?
    func readRuns(completion: @escaping @Sendable (Result<[RetainedRun], PersistenceError>) -> Void)
    func readRun(id: String, completion: @escaping @Sendable (Result<RetainedBreadcrumbs?, PersistenceError>) -> Void)
    /// A barrier for admitted persistence work, excluding discarded observations shown in status.
    func flush(completion: @escaping @Sendable (Result<Void, PersistenceError>) -> Void)
    /// Deletes committed evidence after draining admitted writes. Does not clear live history or stop capture.
    func deleteRun(id: String, completion: @escaping @Sendable (Result<Void, PersistenceError>) -> Void)
    func deleteAllRuns(completion: @escaping @Sendable (Result<Void, PersistenceError>) -> Void)
}
