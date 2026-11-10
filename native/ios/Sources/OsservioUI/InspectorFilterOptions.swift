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
    static let socketKinds: [WebSocketKind] = [.connecting, .attached, .opened, .message, .messageResult, .closeRequested,
        .closing, .closed, .failed, .cancelled, .cancelRequested, .observationEnded, .observationLimited]
    static func logs(_ events: [LogEvent]) -> [InspectorFilterGroup] {
        [InspectorFilterGroup(.level, values: LogLevel.allCases.map(\.rawValue)),
         InspectorFilterGroup(.category, values: events.map(\.category).sorted())]
    }
    static func network(_ events: [HTTPEvent], sockets: [WebSocketEvent], query: InspectorQuery,
                        includeHTTP: Bool = true, includeSockets: Bool = true) -> [InspectorFilterGroup] {
        var result = [InspectorFilterGroup(.transport, values: (includeHTTP ? ["http"] : []) + (includeSockets ? ["webSocket"] : [])),
            InspectorFilterGroup(.host, values: (events.map(\.url) + sockets.map(\.url)).map(InspectorQuery.host).sorted())]
        let sources = Set(events.map { $0.source.rawValue } + sockets.map { $0.source.rawValue })
        if sources.count > 1 || !(query.values[.source]?.isEmpty ?? true) { result.append(InspectorFilterGroup(.source, values: sources.sorted())) }
        result += [InspectorFilterGroup(.method, title: InspectorLocalization.text("ui_http_method"), values: events.map(\.method).sorted()),
            InspectorFilterGroup(.status, title: InspectorLocalization.text("ui_http_status"), values: ["1xx", "2xx", "3xx", "4xx", "5xx", "none"]),
            InspectorFilterGroup(.state, title: InspectorLocalization.text("ui_http_state"), values: events.map { $0.state.rawValue }.sorted()),
            InspectorFilterGroup(.content, title: InspectorLocalization.text("ui_http_content"), values: events.map { InspectorHTTPContent.category($0.responseHeaders) }.sorted()),
            InspectorFilterGroup(.direction, title: InspectorLocalization.text("ui_socket_direction"), values: ["incoming", "outgoing", "none"]),
            InspectorFilterGroup(.kind, title: InspectorLocalization.text("ui_socket_kind"), values: sockets.map { $0.kind.rawValue }.sorted()),
            InspectorFilterGroup(.connection, values: sockets.map(\.connectionID).sorted())]
        return result.filter { (includeHTTP || !InspectorQuery.httpFields.contains($0.field)) &&
            (includeSockets || !InspectorQuery.socketFields.contains($0.field)) }
    }
    static func timeline() -> [InspectorFilterGroup] {
        [InspectorFilterGroup(.direction, values: ["incoming", "outgoing", "none"]),
         InspectorFilterGroup(.kind, values: socketKinds.map(\.rawValue))]
    }
    static func label(_ field: InspectorQuery.Field, _ value: String) -> String {
        if value.isEmpty || value == "unknown" { return InspectorLocalization.text("ui_unknown") }
        switch field {
        case .transport: return value == "http" ? "HTTP" : "WebSocket"
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
        if !query.excludedText.isEmpty { parts.append(InspectorLocalization.text("ui_exclude") + ": " + query.excludedText) }
        if !query.fieldPath.isEmpty { parts.append(query.fieldPath + " = " + query.fieldValue) }
        if query.minimumDurationMillis > 0 { parts.append("HTTP ≥ \(query.minimumDurationMillis) ms") }
        return parts.isEmpty ? InspectorLocalization.text("ui_no_filters") : parts.joined(separator: " · ")
    }
}
