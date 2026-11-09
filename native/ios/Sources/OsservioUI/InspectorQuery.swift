import Foundation
import OsservioCore

/// View-only criteria. Each value set is a union; independent fields intersect.
/// The caller supplies a fixed time anchor while reading a paused snapshot.
struct InspectorQuery: Equatable {
    enum Field: String { case level, category, method, state, status, host, source, direction, kind, record }
    var text = ""
    var values: [Field: Set<String>] = [:]
    var recentMinutes = 0

    var activeCount: Int { values.values.filter { !$0.isEmpty }.count + (recentMinutes > 0 ? 1 : 0) }
    func allows(_ field: Field, _ value: String) -> Bool {
        guard let selected = values[field], !selected.isEmpty else { return true }
        return selected.contains(value)
    }
    func includesTime(_ timestamp: Int64, now: Int64) -> Bool {
        guard recentMinutes > 0 else { return true }
        let duration = Int64(min(recentMinutes, 1_440)) * 60_000
        let lower = now.subtractingReportingOverflow(duration)
        return timestamp >= (lower.overflow ? Int64.min : lower.partialValue) && timestamp <= now
    }
    func contains(_ candidates: String...) -> Bool {
        text.isEmpty || candidates.contains { $0.range(of: text, options: [.caseInsensitive, .literal], locale: Locale(identifier: "en_US_POSIX")) != nil }
    }
    func matches(_ event: LogEvent, now: Int64) -> Bool {
        allows(.level, event.level.rawValue) && allows(.category, event.category)
            && includesTime(event.timestampMillis, now: now) && contains(event.message, event.category)
    }
    func matches(_ event: HTTPEvent, now: Int64) -> Bool {
        allows(.method, event.method) && allows(.state, event.state.rawValue)
            && allows(.status, event.statusCode.map { "\($0 / 100)xx" } ?? "none")
            && allows(.host, Self.host(event.url)) && allows(.source, event.source.rawValue)
            && includesTime(event.timestampMillis, now: now)
            && contains(event.url, event.responseURL ?? "", event.method, event.statusCode.map(String.init) ?? "")
    }
    func matches(_ event: WebSocketEvent, now: Int64) -> Bool {
        allows(.direction, event.direction?.rawValue ?? "none") && allows(.kind, event.kind.rawValue)
            && allows(.host, Self.host(event.url)) && allows(.source, event.source.rawValue)
            && includesTime(event.timestampMillis, now: now)
            && contains(event.url, event.id, event.connectionID, event.kind.rawValue, event.outcome?.rawValue ?? "")
    }
    func matches(_ connection: InspectorConnection, now: Int64) -> Bool {
        allows(.state, connection.state) && allows(.host, Self.host(connection.latest.url))
            && allows(.source, connection.latest.source.rawValue)
            && includesTime(connection.first.timestampMillis, now: now)
            && contains(connection.latest.url, connection.latest.connectionID)
    }
    func matches(_ incident: IncidentSummary, now: Int64) -> Bool {
        allows(.record, incident.diagnosticKind?.rawValue ?? "manual")
            && allows(.source, incident.diagnosticKind == nil ? "manual" : "system")
            && includesTime(incident.timestampMillis, now: now)
            && contains(incident.id, incident.note?.text ?? "", incident.diagnosticKind?.rawValue ?? "manual")
    }
    static func host(_ url: String) -> String { URLComponents(string: url)?.host?.lowercased() ?? "" }
    static func path(_ url: String) -> String {
        guard let components = URLComponents(string: url), components.host != nil else { return url }
        let path = components.percentEncodedPath.isEmpty ? "/" : components.percentEncodedPath
        return path + (components.percentEncodedQuery.map { "?" + $0 } ?? "")
    }
}

/// The last retained lifecycle observation, not a claim about the live socket.
/// Message results cannot change connection state. Missing lifecycle stays unknown.
struct InspectorConnection {
    let first: WebSocketEvent
    let latest: WebSocketEvent
    let lifecycle: WebSocketEvent?
    let count: Int
    var state: String { lifecycle?.kind.rawValue ?? "unknown" }

    static func groups(_ events: [WebSocketEvent]) -> [InspectorConnection] {
        Dictionary(grouping: events, by: \.connectionID).values.map { group in
            let ordered = group.sorted { $0.sequence == $1.sequence ? $0.id < $1.id : $0.sequence < $1.sequence }
            return InspectorConnection(first: ordered[0], latest: ordered[ordered.count - 1],
                lifecycle: ordered.last { $0.kind != .message && $0.kind != .messageResult }, count: ordered.count)
        }.sorted {
            $0.first.timestampMillis == $1.first.timestampMillis
                ? $0.first.connectionID < $1.first.connectionID : $0.first.timestampMillis > $1.first.timestampMillis
        }
    }
}
