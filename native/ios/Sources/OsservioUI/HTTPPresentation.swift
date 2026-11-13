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
        let date = InspectorValuePresentation.eventTime(event.timestampMillis, full: true)
        return [event.method + " " + event.url, state(event.state),
                field("final_url", event.responseURL), field("status", event.statusCode.map(String.init)),
                field("duration", event.durationMillis.map { "\($0) ms" }), field("time", date),
                field("source", source), field("failure", event.failure.map { text("http_" + $0.rawValue) }),
                field("id", event.id), field("session", event.sessionID) + " · #\(event.sequence)",
                "\n" + text("http_request_headers") + "\n" + headers(event.requestHeaders),
                "\n" + text("http_response_headers") + "\n" + headers(event.responseHeaders),
                metrics(event.metrics).map { $0.0 + ": " + $0.1 }.joined(separator: "\n"),
                event.headersLimited ? text("http_headers_limited") : "", "\n" + text(note), text("http_result_note")]
            .filter { !$0.isEmpty }.joined(separator: "\n\n")
    }

    static func metrics(_ value: HTTPMetrics?) -> [(String, String)] {
        guard let value else { return [(text("http_metrics"), text("http_metrics_missing"))] }
        var rows = [(text("http_protocol"), value.protocolName ?? text("http_none")),
                    (text("http_cache"), text("http_cache_" + value.cache.rawValue)),
                    (text("http_reused"), value.connectionReused.map { text($0 ? "http_reused_yes" : "http_reused_no") } ?? text("http_none"))]
        var occurrences: [HTTPPhase: Int] = [:]
        for phase in value.phases {
            let count = (occurrences[phase.phase] ?? 0) + 1; occurrences[phase.phase] = count
            let duration = phase.endMillis.map { "\($0 - phase.startMillis) ms" } ?? text("http_phase_incomplete")
            let offset = String(format: text("http_phase_offset"), String(phase.startMillis))
            rows.append((text("http_phase_" + phase.phase.rawValue) + " · #\(count)", duration + " · " + offset))
        }
        if value.phases.isEmpty { rows.append((text("http_metrics"), text("http_metrics_missing"))) }
        if value.limited { rows.append((text("http_metrics"), text("http_metrics_limited"))) }
        return rows
    }

    static func body(_ reference: HTTPBodyReference, history: any HTTPHistory) -> InspectorReadContent {
        let notice = { bodyDescription(reference) }
        guard let id = reference.blobID else { return InspectorReadContent(notice: notice) }
        switch history.body(id: id) {
        case .available(let body):
            return InspectorReadContent(text: body.text, notice: { notice() + (body.text.isEmpty ? "\n" + text("http_body_empty") : "") }, allowsJSON: reference.state == .available)
        case .unavailable: return InspectorReadContent(notice: { text("http_body_unavailable") })
        case .busy: return InspectorReadContent(notice: { text("http_body_busy") })
        }
    }
    static func body(_ reference: HTTPBodyReference, savedPayloads: [String: IncidentPayload]) -> InspectorReadContent {
        let notice = { bodyDescription(reference) }
        guard let id = reference.blobID else { return InspectorReadContent(notice: notice) }
        return .saved(savedPayloads[id], notice: notice, complete: reference.state == .available)
    }
    static func bodyDescription(_ reference: HTTPBodyReference) -> String {
        let key = reference.state == .invalidText ? "invalid_text" : reference.state.rawValue
        return (reference.isProcessed ? text("ui_content_processed") + "\n" : "") + text("http_body_" + key) + (reference.observedBytes > 0 ? "\n" + text("http_observed_bytes") + ": " + InspectorValuePresentation.bytes(reference.observedBytes) : "")
    }
}
