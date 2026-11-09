import Foundation
import OsservioCore

/// Text is built only from admitted metadata or an explicit body lookup.
enum HTTPPresentation {
    static func text(_ key: String) -> String { InspectorLocalization.text(key) }

    static func state(_ value: HTTPState) -> String {
        let key: String
        switch value {
        case .pending: key = "pending"
        case .receiving: key = "receiving"
        case .completed: key = "completed"
        case .failed: key = "failed"
        case .cancelled: key = "cancelled"
        case .closedEarly: key = "closed_early"
        case .captureEnded: key = "capture_ended"
        }
        return text("http_" + key)
    }

    static func summary(_ event: HTTPEvent) -> String {
        var parts = [state(event.state)]
        if let code = event.statusCode { parts.append(String(code)) }
        if let duration = event.durationMillis { parts.append("\(duration) ms") }
        return parts.joined(separator: " · ")
    }

    static func overview(_ event: HTTPEvent) -> String {
        func field(_ key: String, _ value: String?) -> String { text("http_" + key) + ": " + (value ?? text("http_none")) }
        func headers(_ values: [HTTPHeader]) -> String {
            values.isEmpty ? text("http_none") : values.map { $0.name + ": " + $0.value }.joined(separator: "\n")
        }
        let source: String
        let note: String
        switch event.source {
        case .host: source = text("ws_host"); note = "http_host_note"
        case .okHttp: source = "OkHttp"; note = "http_okhttp_note"
        case .urlSession: source = "URLSession"; note = "http_urlsession_note"
        }
        let date = InspectorLocalization.date(event.timestampMillis)
        return [event.method + " " + event.url, state(event.state),
                field("final_url", event.responseURL), field("status", event.statusCode.map(String.init)),
                field("duration", event.durationMillis.map { "\($0) ms" }), field("time", date),
                field("source", source), field("failure", event.failure.map { text("http_" + $0.rawValue) }),
                field("id", event.id), field("session", event.sessionID) + " · #\(event.sequence)",
                "\n" + text("http_request_headers") + "\n" + headers(event.requestHeaders),
                "\n" + text("http_response_headers") + "\n" + headers(event.responseHeaders),
                event.headersLimited ? text("http_headers_limited") : "", "\n" + text(note), text("http_result_note")]
            .filter { !$0.isEmpty }.joined(separator: "\n\n")
    }

    static func body(_ reference: HTTPBodyReference, history: any HTTPHistory) -> String {
        var result = bodyDescription(reference)
        guard let id = reference.blobID else { return result }
        switch history.body(id: id) {
        case .available(let body): result += "\n\n" + (body.text.isEmpty ? text("http_body_empty") : body.text)
        case .unavailable: result += "\n\n" + text("http_body_unavailable")
        case .busy: result += "\n\n" + text("http_body_busy")
        }
        return result
    }
    static func body(_ reference: HTTPBodyReference, savedPayloads: [String: IncidentPayload]) -> String {
        bodyDescription(reference) + (reference.blobID.map { "\n\n" + IncidentPayloadPresentation.content(savedPayloads[$0]) } ?? "")
    }
    static func bodyDescription(_ reference: HTTPBodyReference) -> String {
        let key = reference.state == .invalidText ? "invalid_text" : reference.state.rawValue
        return text("http_body_" + key) + "\n" + text("http_observed_bytes") + ": \(reference.observedBytes)"
    }
}
