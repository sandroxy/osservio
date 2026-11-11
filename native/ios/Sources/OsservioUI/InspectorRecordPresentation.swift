import Foundation
import OsservioCore

/// The same admitted event has the same summary in live history and saved evidence.
enum InspectorRecordPresentation {
    enum Tone { case ordinary, warning, error }
    struct Row {
        let badge: String
        let title: String
        let subtitle: String
        let time: Int64
        let tone: Tone
    }
    static func text(_ key: String) -> String { InspectorLocalization.text("ui_" + key) }
    static func compact(_ text: String, limit: Int = 180) -> String {
        let prefix = text.prefix(limit + 1)
        return String(prefix.prefix(limit)).replacingOccurrences(of: "\r", with: " ")
            .replacingOccurrences(of: "\n", with: " ") + (prefix.count > limit ? "…" : "")
    }
    static func identity(_ id: String) -> String { id.count <= 16 ? id : String(id.prefix(8)) + "…" + id.suffix(4) }
    static func connection(_ id: String) -> String { text("connection") + " · " + identity(id) }
    static func path(_ source: String) -> String {
        let url = InspectorURL(source)
        return !url.available ? text("url_unavailable") : url.pathHidden ? text("path_hidden") : url.path
    }
    static func address(_ source: String) -> String {
        let url = InspectorURL(source)
        return joined([url.origin, path(source), url.queryHidden ? text("query_hidden") : ""])
    }
    static func joined(_ values: [String]) -> String { values.filter { !$0.isEmpty }.joined(separator: " · ") }
    static func log(_ event: LogEvent, preview: String? = nil) -> Row {
        Row(badge: InspectorFilterOptions.label(.level, event.level.rawValue),
            title: event.isPrivate ? text("private_content") : compact(preview ?? event.message, limit: 600),
            subtitle: joined([event.category, event.isTruncated ? InspectorLocalization.text("truncated") : ""]),
            time: event.timestampMillis, tone: event.level == .error ? .error : event.level == .warning ? .warning : .ordinary)
    }
    static func http(_ event: HTTPEvent, preview: String? = nil) -> Row {
        let url = InspectorURL(event.url)
        let result = event.failure.map { InspectorLocalization.text("http_" + $0.rawValue) }
            ?? (event.state == .completed ? "" : HTTPPresentation.state(event.state))
        return Row(badge: event.method + (event.statusCode.map { " · \($0)" } ?? ""), title: path(event.url),
            subtitle: joined([url.origin, result, event.durationMillis.map { "\($0) ms" } ?? "", url.queryHidden ? text("query_hidden") : ""])
                + (preview.map { "\n" + $0 } ?? ""), time: event.timestampMillis,
            tone: event.state == .failed || (event.statusCode ?? 0) >= 400 ? .error : .ordinary)
    }
    static func socket(_ event: WebSocketEvent, preview: String? = nil, scoped: Bool = false) -> Row {
        let message = event.kind == .message
        let result = event.kind == .messageResult
        let direction = event.direction.map { InspectorLocalization.text("ws_" + $0.rawValue) }
        let badge = "Socket · " + (message ? direction ?? WebSocketPresentation.kind(event) : WebSocketPresentation.kind(event))
        let title: String
        if let preview { title = compact(preview) }
        else if message { title = payloadLabel(event.payload) }
        else if result { title = event.outcome.map { InspectorLocalization.text("ws_outcome_" + $0.rawValue) } ?? WebSocketPresentation.kind(event) }
        else { title = path(event.url) }
        var details: [String] = []
        if !scoped { details += [message || result ? address(event.url) : InspectorURL(event.url).origin, connection(event.connectionID)] }
        if let failure = event.failure { details.append(InspectorLocalization.text("http_" + failure.rawValue)) }
        if let code = event.closeCode { details.append(text("close_code_short") + " " + String(code)) }
        if message, event.payload?.state == .truncated { details.append(text("preview_partial")) }
        if message, let bytes = event.payload?.byteCount { details.append(InspectorValuePresentation.bytes(bytes)) }
        if message, let outcome = event.outcome, outcome != .received { details.append(InspectorLocalization.text("ws_outcome_" + outcome.rawValue)) }
        if result, let related = event.relatedMessageID { details.append(text("related_message") + " · " + identity(related)) }
        return Row(badge: badge, title: title, subtitle: joined(details), time: event.timestampMillis,
            tone: event.kind == .failed || event.outcome == .failed || event.outcome == .rejected ? .error : .ordinary)
    }
    static func payloadLabel(_ reference: WebSocketPayloadReference?) -> String {
        guard let reference else { return InspectorLocalization.text("ws_no_payload") }
        switch reference.state {
        case .available: return text("message_text")
        case .truncated: return text("message_truncated")
        case .omitted: return text("private_content")
        case .binary: return text("binary_message")
        case .invalidText: return text("unreadable_message")
        }
    }
    static func context(_ event: LogEvent) -> String {
        joined([InspectorFilterOptions.label(.level, event.level.rawValue), event.category, InspectorValuePresentation.eventTime(event.timestampMillis), text("record_identity") + " · " + identity(event.sessionID) + " #\(event.sequence)"])
    }
    static func context(_ event: HTTPEvent) -> String {
        joined([event.method, text("record_identity") + " · " + identity(event.id), InspectorValuePresentation.eventTime(event.timestampMillis), address(event.url)])
    }
    static func context(_ event: WebSocketEvent) -> String {
        joined([WebSocketPresentation.kind(event), event.direction.map { InspectorLocalization.text("ws_" + $0.rawValue) } ?? "",
                connection(event.connectionID), text("record_identity") + " · " + identity(event.id), InspectorValuePresentation.eventTime(event.timestampMillis)])
    }
}
