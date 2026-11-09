import Foundation
import OsservioCore

/// Builds metadata rows without loading message payloads. Payload lookup happens only in detail.
enum WebSocketPresentation {
    static func text(_ key: String) -> String { InspectorLocalization.text(key) }
    static func kind(_ event: WebSocketEvent) -> String { text("ws_kind_" + event.kind.rawValue) }
    static func summary(_ event: WebSocketEvent) -> String {
        var parts = [kind(event)]
        if let direction = event.direction { parts.append(text("ws_" + direction.rawValue)) }
        if let outcome = event.outcome { parts.append(text("ws_outcome_" + outcome.rawValue)) }
        if let code = event.closeCode { parts.append(String(code)) }
        return parts.joined(separator: " · ")
    }
    static func overview(_ event: WebSocketEvent) -> String {
        func field(_ key: String, _ value: String?) -> String { text(key) + ": " + (value ?? text("http_none")) }
        let date = InspectorLocalization.date(event.timestampMillis)
        let source = event.source == .okHttp ? "OkHttp" : event.source == .urlSession ? "URLSession" : text("ws_host")
        return [event.url, summary(event), field("ws_time", date), field("http_source", source),
                field("ws_connection", event.connectionID), field("ws_event", event.id),
                field("ws_related", event.relatedMessageID), field("http_session", event.sessionID) + " · #\(event.sequence)",
                field("http_status", event.statusCode.map(String.init)), field("ws_close_code", event.closeCode.map(String.init)),
                field("http_failure", event.failure.map { text("http_" + $0.rawValue) }),
                "\n" + text("ws_headers") + "\n" + (event.headers.isEmpty ? text("http_none") : event.headers.map { $0.name + ": " + $0.value }.joined(separator: "\n")),
                event.headersLimited ? text("http_headers_limited") : "", "\n" + text("ws_evidence_note")]
            .filter { !$0.isEmpty }.joined(separator: "\n\n")
    }
    static func payload(_ reference: WebSocketPayloadReference?, history: any WebSocketHistory) -> InspectorReadContent {
        guard let reference else { return InspectorReadContent(notice: text("ws_no_payload")) }
        let notice = payloadDescription(reference)
        guard let id = reference.blobID else { return InspectorReadContent(notice: notice) }
        switch history.payload(id: id) {
        case .available(let payload):
            return InspectorReadContent(text: payload.text, notice: notice + (payload.text.isEmpty ? "\n" + text("http_body_empty") : ""), allowsJSON: reference.state == .available)
        case .unavailable: return InspectorReadContent(notice: notice + "\n" + text("http_body_unavailable"))
        case .busy: return InspectorReadContent(notice: notice + "\n" + text("http_body_busy"))
        }
    }
    static func payload(_ reference: WebSocketPayloadReference?, savedPayloads: [String: IncidentPayload]) -> InspectorReadContent {
        guard let reference else { return InspectorReadContent(notice: text("ws_no_payload")) }
        let notice = payloadDescription(reference)
        guard let id = reference.blobID else { return InspectorReadContent(notice: notice) }
        return .saved(savedPayloads[id], notice: notice, complete: reference.state == .available)
    }
    static func payloadDescription(_ reference: WebSocketPayloadReference) -> String {
        text("ws_payload_" + reference.state.rawValue) + "\n" + text("ws_bytes") + ": " + (reference.byteCount.map(String.init) ?? text("http_none"))
    }
}
