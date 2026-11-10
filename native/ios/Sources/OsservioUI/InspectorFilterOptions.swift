import Foundation
import OsservioCore

struct InspectorFilterGroup {
    let field: InspectorQuery.Field
    let title: String
    var options: [(value: String, label: String)]
    init(_ field: InspectorQuery.Field, title: String? = nil, values: [String]) {
        self.field = field; self.title = title ?? InspectorLocalization.text("ui_" + field.rawValue)
        var seen = Set<String>()
        options = values.filter { seen.insert($0).inserted }.map { ($0, InspectorFilterOptions.label(field, $0)) }
    }
}

enum InspectorFilterOptions {
    static let httpStates: [HTTPState] = [.pending, .receiving, .completed, .failed, .cancelled, .closedEarly, .captureEnded]
    static let socketKinds: [WebSocketKind] = [.connecting, .attached, .opened, .message, .messageResult, .closeRequested,
        .closing, .closed, .failed, .cancelled, .cancelRequested, .observationEnded, .observationLimited]
    static func logs(_ events: [LogEvent]) -> [InspectorFilterGroup] {
        [InspectorFilterGroup(.level, values: LogLevel.allCases.map(\.rawValue)),
         InspectorFilterGroup(.category, values: events.map(\.category).sorted())]
    }
    static func network(_ events: [HTTPEvent], connections: [InspectorConnection], scope: Int, query: InspectorQuery) -> [InspectorFilterGroup] {
        let urls = (scope == 2 ? [] : events.map(\.url)) + (scope == 1 ? [] : connections.map { $0.latest.url })
        let sources = (scope == 2 ? [] : events.map { $0.source.rawValue }) + (scope == 1 ? [] : connections.map { $0.latest.source.rawValue })
        var result = [InspectorFilterGroup(.host, values: urls.map(InspectorQuery.host).sorted())]
        if Set(sources).count > 1 || !(query.values[.source]?.isEmpty ?? true) {
            result.append(InspectorFilterGroup(.source, values: sources.sorted()))
        }
        if scope == 1 {
            result += [InspectorFilterGroup(.method, values: events.map(\.method).sorted()),
                       InspectorFilterGroup(.content, values: InspectorHTTPContent.categories),
                       InspectorFilterGroup(.state, values: httpStates.map(\.rawValue)),
                       InspectorFilterGroup(.status, values: ["1xx", "2xx", "3xx", "4xx", "5xx", "none"])]
        } else if scope == 2 {
            result.append(InspectorFilterGroup(.state, title: InspectorLocalization.text("ui_connection_state"),
                values: socketKinds.filter { $0 != .message && $0 != .messageResult }.map(\.rawValue) + ["unknown"]))
        }
        return result
    }
    static func timeline() -> [InspectorFilterGroup] {
        [InspectorFilterGroup(.direction, values: ["incoming", "outgoing", "none"]),
         InspectorFilterGroup(.kind, values: socketKinds.map(\.rawValue))]
    }
    static func label(_ field: InspectorQuery.Field, _ value: String) -> String {
        if value.isEmpty || value == "unknown" { return InspectorLocalization.text("ui_unknown") }
        switch field {
        case .content: return InspectorLocalization.text("ui_content_" + value)
        case .level: return value == "warning" ? "WARN" : value.uppercased()
        case .state:
            if let state = HTTPState(rawValue: value) { return HTTPPresentation.state(state) }
            return InspectorLocalization.text("ws_kind_" + value)
        case .kind:
            if WebSocketKind(rawValue: value) != nil { return InspectorLocalization.text("ws_kind_" + value) }
            return InspectorLocalization.text("crumb_kind_" + value)
        case .status: return value == "none" ? InspectorLocalization.text("ui_no_status") : value
        case .direction: return InspectorLocalization.text(value == "none" ? "ui_no_direction" : "ws_" + value)
        case .source:
            if value == "host" { return InspectorLocalization.text("ws_host") }
            if value == "okHttp" { return "OkHttp" }
            if value == "urlSession" { return "URLSession" }
            return InspectorLocalization.text("ui_" + value)
        case .record: return value == "manual" ? InspectorLocalization.text("ui_manual") : InspectorLocalization.text("diagnostic_kind_" + value)
        default: return value
        }
    }
    static func summary(_ query: InspectorQuery) -> String {
        var parts = query.values.keys.sorted { $0.rawValue < $1.rawValue }.compactMap { field -> String? in
            guard let values = query.values[field], !values.isEmpty else { return nil }
            let labels = values.sorted().prefix(2).map { String(label(field, $0).prefix(48)) }
            return InspectorLocalization.text("ui_" + field.rawValue) + ": " + labels.joined(separator: ", ") + (values.count > 2 ? " +\(values.count - 2)" : "")
        }
        if query.recentMinutes > 0 { parts.append(InspectorLocalization.text("ui_time_\(query.recentMinutes)")) }
        return parts.isEmpty ? InspectorLocalization.text("ui_no_filters") : parts.joined(separator: " · ")
    }
}
