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
    static func payload(_ reference: WebSocketPayloadReference?, history: any WebSocketHistory) -> String {
        guard let reference else { return text("ws_no_payload") }
        var result = payloadDescription(reference)
        guard let id = reference.blobID else { return result }
        switch history.payload(id: id) {
        case .available(let payload): result += "\n\n" + (payload.text.isEmpty ? text("http_body_empty") : payload.text)
        case .unavailable: result += "\n\n" + text("http_body_unavailable")
        case .busy: result += "\n\n" + text("http_body_busy")
        }
        return result
    }
    static func payload(_ reference: WebSocketPayloadReference?, savedPayloads: [String: IncidentPayload]) -> String {
        guard let reference else { return text("ws_no_payload") }
        return payloadDescription(reference) + (reference.blobID.map { "\n\n" + IncidentPayloadPresentation.content(savedPayloads[$0]) } ?? "")
    }
    static func payloadDescription(_ reference: WebSocketPayloadReference) -> String {
        text("ws_payload_" + reference.state.rawValue) + "\n" + text("ws_bytes") + ": " + (reference.byteCount.map(String.init) ?? text("http_none"))
    }
}
