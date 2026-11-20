import Foundation
import OsservioCore

/// View-only criteria. Each value set is a union; independent fields intersect.
/// The caller supplies a fixed time anchor while reading a paused snapshot.
struct InspectorQuery: Equatable, Codable, Sendable {
    enum Target: Equatable {
        case field(Field), time, duration, excludedText, json
    }
    enum Field: String, Codable, Sendable { case level, category, method, state, status, host, source, direction, kind, record, content, transport, connection, activity, outcome; case breadcrumbKind = "breadcrumb_kind", excludedBreadcrumbKind = "excluded_breadcrumb_kind", excludedRecord = "excluded_record", evidence; case closeCode = "close_code", searchScope = "search_scope", excludedHost = "excluded_host", excludedCategory = "excluded_category" }
    static let fieldOrder: [Field] = [.transport, .level, .record, .evidence, .breadcrumbKind, .excludedRecord, .excludedBreadcrumbKind, .host, .excludedHost, .category, .excludedCategory, .method, .status, .state,
        .activity, .direction, .kind, .outcome, .closeCode, .connection, .content, .source, .searchScope]
    var text = ""
    var excludedText = ""
    var fieldPath = ""
    var fieldValue = ""
    var minimumDurationMillis: UInt64 = 0
    var values: [Field: Set<String>] = [:]
    var recentMinutes = 0

    var activeCount: Int { values.values.filter { !$0.isEmpty }.count + (recentMinutes > 0 ? 1 : 0)
        + (minimumDurationMillis > 0 ? 1 : 0) + (fieldPath.isEmpty ? 0 : 1) + (excludedText.isEmpty ? 0 : 1) }
    var searchesPayload: Bool { !text.isEmpty || !excludedText.isEmpty || !fieldPath.isEmpty }
    static let httpFields: Set<Field> = [.method, .state, .status, .content]
    static let socketFields: Set<Field> = [.activity, .direction, .kind, .connection, .outcome, .closeCode]
    var searchScope: String { values[.searchScope]?.first ?? "all" }
    var transport: String? { values[.transport]?.first }
    func searches(_ scope: String) -> Bool { searchScope == "all" || searchScope == scope }
    func allowsStatus(_ code: Int?) -> Bool {
        guard let selected = values[.status], !selected.isEmpty else { return true }
        guard let code else { return selected.contains("none") }
        return selected.contains(String(code)) || selected.contains("\(code / 100)xx")
    }
    mutating func select(_ field: Field, value: String?) {
        if field == .transport {
            values[field] = value.map { [$0] } ?? []
            if value != "http" { for key in Self.httpFields { values[key] = nil }; minimumDurationMillis = 0 }
            if value != "webSocket" { for key in Self.socketFields { values[key] = nil } }
            if value != "http", ["request", "response"].contains(searchScope) { values[.searchScope] = nil }
            if value != "webSocket", searchScope == "message" { values[.searchScope] = nil }
            return
        }
        if field == .searchScope || field == .activity {
            values[field] = value.map { [$0] } ?? []
            if field == .activity, value == "connections" { values[.direction] = nil; values[.outcome] = nil }
            if field == .activity, let value {
                if value == "messages" { values[.closeCode] = nil }
                values[.kind] = values[.kind]?.filter { (["message", "messageResult"].contains($0)) == (value == "messages") }
            }
            return
        }
        if value != nil {
            if Self.httpFields.contains(field) { select(.transport, value: "http") }
            if Self.socketFields.contains(field) { select(.transport, value: "webSocket") }
        }
        guard let value else { values[field] = nil; return }
        var selection = values[field] ?? []
        if selection.contains(value) { selection.remove(value) }
        else {
            selection.insert(value)
            if let opposite = Self.opposite(field) { values[opposite]?.remove(value) }
        }
        values[field] = selection
    }
    private static func opposite(_ field: Field) -> Field? {
        switch field {
        case .record: return .excludedRecord
        case .excludedRecord: return .record
        case .breadcrumbKind: return .excludedBreadcrumbKind
        case .excludedBreadcrumbKind: return .breadcrumbKind
        case .category: return .excludedCategory
        case .excludedCategory: return .category
        case .host: return .excludedHost
        case .excludedHost: return .host
        default: return nil
        }
    }
    mutating func filterRecord(_ field: Field, value: String, excluding: Bool) {
        guard let excluded = Self.opposite(field), [.host, .category, .record, .breadcrumbKind].contains(field) else { return }
        if excluding {
            values[field]?.remove(value); values[excluded, default: []].insert(value)
        } else {
            values[excluded]?.remove(value); values[field] = [value]
        }
    }
    private func allowsHost(_ url: String) -> Bool {
        guard !(values[.host]?.isEmpty ?? true) || !(values[.excludedHost]?.isEmpty ?? true) else { return true }
        let host = Self.host(url)
        return allows(.host, host) && values[.excludedHost]?.contains(host) != true
    }
    var hasTransportConditions: Bool {
        minimumDurationMillis > 0 || Self.httpFields.union(Self.socketFields).contains { !(values[$0]?.isEmpty ?? true) }
            || ["request", "response", "message"].contains(searchScope)
    }

    /// Only the open sheet retains inactive protocol choices. Common criteria stay live in the draft.
    struct ScopeDraft {
        private var choices: [String: InspectorQuery] = [:]

        mutating func switchTo(_ scope: String?, in query: inout InspectorQuery) {
            guard query.transport != scope else { return }
            choices[query.transport ?? "all"] = query
            query.select(.transport, value: scope)
            guard let saved = choices[scope ?? "all"] else { return }
            let fields: Set<Field> = scope == "http" ? httpFields : scope == "webSocket" ? socketFields : []
            for field in fields { query.values[field] = saved.values[field] }
            if scope == "http" { query.minimumDurationMillis = saved.minimumDurationMillis }
            if ["request", "response", "message"].contains(saved.searchScope) {
                query.values[.searchScope] = saved.values[.searchScope]
            }
        }
    }

    mutating func resetFilters() {
        values.removeAll(); recentMinutes = 0; minimumDurationMillis = 0
        fieldPath = ""; fieldValue = ""; excludedText = ""
    }
    func allows(_ field: Field, _ value: @autoclosure () -> String) -> Bool {
        guard let selected = values[field], !selected.isEmpty else { return true }
        return selected.contains(value())
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
        matchesFacets(event, now: now) && contains(event.message, event.category)
            && (excludedText.isEmpty || !Self.contains(excludedText, in: event.message) && !Self.contains(excludedText, in: event.category))
    }
    func matchesFacets(_ event: LogEvent, now: Int64) -> Bool {
        allows(.level, event.level.rawValue) && allows(.category, event.category) && values[.excludedCategory]?.contains(event.category) != true && includesTime(event.timestampMillis, now: now)
    }
    func matches(_ event: HTTPEvent, now: Int64) -> Bool {
        matchesFacets(event, now: now) && contains(event.url, event.responseURL ?? "", event.method, event.statusCode.map(String.init) ?? "")
    }
    func matchesFacets(_ event: HTTPEvent, now: Int64) -> Bool {
        allows(.transport, "http") && allows(.method, event.method) && allows(.state, event.state.rawValue)
            && allowsStatus(event.statusCode)
            && allowsHost(event.url) && allows(.source, event.source.rawValue)
            && allows(.content, InspectorHTTPContent.category(event.responseHeaders))
            && (minimumDurationMillis == 0 || event.durationMillis.map { $0 >= minimumDurationMillis } == true)
            && Self.socketFields.allSatisfy { values[$0]?.isEmpty ?? true }
            && includesTime(event.timestampMillis, now: now)
    }
    func matches(_ event: WebSocketEvent, now: Int64) -> Bool {
        matchesFacets(event, now: now) && contains(event.url, event.id, event.connectionID, event.kind.rawValue, event.outcome?.rawValue ?? "")
    }
    func matchesFacets(_ event: WebSocketEvent, now: Int64) -> Bool {
        allows(.transport, "webSocket") && allows(.connection, event.connectionID)
            && allows(.activity, event.kind == .message || event.kind == .messageResult ? "messages" : "connections")
            && allows(.direction, event.direction?.rawValue ?? "none") && allows(.kind, event.kind.rawValue)
            && allows(.outcome, event.outcome?.rawValue ?? "none") && allows(.closeCode, event.closeCode.map(String.init) ?? "none")
            && allowsHost(event.url) && allows(.source, event.source.rawValue)
            && Self.httpFields.allSatisfy { values[$0]?.isEmpty ?? true } && minimumDurationMillis == 0
            && includesTime(event.timestampMillis, now: now)
    }
    func matches(_ connection: InspectorConnection, now: Int64) -> Bool {
        allows(.state, connection.state) && allowsHost(connection.latest.url)
            && allows(.source, connection.latest.source.rawValue)
            && includesTime(connection.latest.timestampMillis, now: now)
            && contains(connection.latest.url, connection.latest.connectionID)
    }
    static func contains(_ query: String, in text: String) -> Bool {
        text.range(of: query, options: [.caseInsensitive, .literal], locale: Locale(identifier: "en_US_POSIX")) != nil
    }
    func matches(_ event: BreadcrumbEvent, now: Int64) -> Bool {
        allows(.breadcrumbKind, event.kind.rawValue)
            && values[.excludedBreadcrumbKind]?.contains(event.kind.rawValue) != true
            && includesTime(event.timestampMillis, now: now)
            && contains(event.label, event.isPrivate ? "" : event.message ?? "", event.id, InspectorFilterOptions.label(.breadcrumbKind, event.kind.rawValue))
    }
    static func evidence(_ incident: IncidentSummary) -> Set<String> {
        var available = Set<String>()
        if incident.hasEnvironment { available.insert("environment") }
        for (key, count) in [("breadcrumbs", incident.breadcrumbCount), ("logs", incident.logCount),
                             ("http", incident.httpCount), ("webSocket", incident.webSocketCount)] {
            if let count, count > 0 { available.insert(key) }
        }
        return available
    }
    func matches(_ incident: IncidentSummary, now: Int64) -> Bool {
        allows(.record, incident.diagnosticKind?.rawValue ?? "manual")
            && values[.excludedRecord]?.contains(incident.diagnosticKind?.rawValue ?? "manual") != true
            && (values[.evidence]?.isEmpty != false || !Self.evidence(incident).isDisjoint(with: values[.evidence] ?? []))
            && includesTime(incident.timestampMillis, now: now)
            && contains(incident.id, incident.note?.isPrivate == false ? incident.note?.text ?? "" : "", incident.diagnosticKind?.rawValue ?? "manual", InspectorFilterOptions.label(.record, incident.diagnosticKind?.rawValue ?? "manual"))
    }
    static func host(_ url: String) -> String { URLComponents(string: url)?.host?.lowercased() ?? "" }
    /// A timestamp tie must preserve capture order within a session. Session IDs
    /// provide a stable tie-break only; they do not establish cross-session causality.
    static func timelineNewestFirst(_ left: WebSocketEvent, _ right: WebSocketEvent) -> Bool {
        if left.timestampMillis != right.timestampMillis { return left.timestampMillis > right.timestampMillis }
        if left.sessionID != right.sessionID { return left.sessionID < right.sessionID }
        return left.sequence == right.sequence ? left.id < right.id : left.sequence > right.sequence
    }
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
