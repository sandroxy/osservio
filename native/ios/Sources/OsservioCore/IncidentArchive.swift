import Foundation

/// Lightweight metadata for a committed snapshot. Attachments are loaded only on request.
public struct IncidentSummary: Sendable {
    /// Records present in each source window but left out when the snapshot was made.
    /// A nil count means the archive did not supply it. Earlier source evictions are separate.
    public struct OmittedCounts: Sendable {
        public let breadcrumbs: Int?
        public let logs: Int?
        public let http: Int?
        public let webSockets: Int?
        public init(breadcrumbs: Int? = nil, logs: Int? = nil, http: Int? = nil, webSockets: Int? = nil) {
            self.breadcrumbs = breadcrumbs; self.logs = logs; self.http = http; self.webSockets = webSockets
        }
    }
    public let omitted: OmittedCounts
    public let id: String
    public let timestampMillis: Int64
    public let note: IncidentNote?
    public let hasEnvironment: Bool
    public let breadcrumbCount: Int?
    public let logCount: Int?
    public let httpCount: Int?
    public let webSocketCount: Int?
    public let diagnosticKind: SystemDiagnosticKind?
    public init(id: String, timestampMillis: Int64, note: IncidentNote?, hasEnvironment: Bool, breadcrumbCount: Int?,
                logCount: Int? = nil, httpCount: Int? = nil, webSocketCount: Int? = nil, diagnosticKind: SystemDiagnosticKind? = nil) {
        self.init(id: id, timestampMillis: timestampMillis, note: note, hasEnvironment: hasEnvironment, breadcrumbCount: breadcrumbCount,
                  logCount: logCount, httpCount: httpCount, webSocketCount: webSocketCount, diagnosticKind: diagnosticKind, omitted: OmittedCounts())
    }
    public init(id: String, timestampMillis: Int64, note: IncidentNote?, hasEnvironment: Bool, breadcrumbCount: Int?,
                logCount: Int? = nil, httpCount: Int? = nil, webSocketCount: Int? = nil, diagnosticKind: SystemDiagnosticKind? = nil,
                omitted: OmittedCounts) {
        self.omitted = omitted
        self.id = id; self.timestampMillis = timestampMillis; self.note = note
        self.hasEnvironment = hasEnvironment; self.breadcrumbCount = breadcrumbCount
        self.logCount = logCount; self.httpCount = httpCount; self.webSocketCount = webSocketCount
        self.diagnosticKind = diagnosticKind
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
