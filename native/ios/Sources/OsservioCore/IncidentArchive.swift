import Foundation

/// Lightweight metadata for a committed manual snapshot. Attachments are loaded only on request.
public struct IncidentSummary: Sendable {
    public let id: String
    public let timestampMillis: Int64
    public let note: IncidentNote?
    public let hasEnvironment: Bool
    public let breadcrumbCount: Int?
    public init(id: String, timestampMillis: Int64, note: IncidentNote?, hasEnvironment: Bool, breadcrumbCount: Int?) {
        self.id = id; self.timestampMillis = timestampMillis; self.note = note
        self.hasEnvironment = hasEnvironment; self.breadcrumbCount = breadcrumbCount
    }
}

/// Explicit, bounded local persistence. Completion may run on any queue, including immediate rejection.
public protocol IncidentArchive: Sendable {
    /// Queued/committed event counters refer to snapshots, not their attached breadcrumbs.
    func persistenceStatus() -> PersistenceStatus?
    /// Success means the transaction committed. Reusing an already saved identity is rejected.
    func save(_ snapshot: IncidentSnapshot, completion: @escaping @Sendable (Result<Void, PersistenceError>) -> Void)
    func readIncidents(completion: @escaping @Sendable (Result<[IncidentSummary], PersistenceError>) -> Void)
    /// A successful nil result means that this snapshot is no longer retained.
    func readIncident(id: String, completion: @escaping @Sendable (Result<IncidentSnapshot?, PersistenceError>) -> Void)
    /// Deletion affects this archive only. It does not clear live histories or change capture policy.
    func deleteIncident(id: String, completion: @escaping @Sendable (Result<Void, PersistenceError>) -> Void)
    func deleteAllIncidents(completion: @escaping @Sendable (Result<Void, PersistenceError>) -> Void)
}
