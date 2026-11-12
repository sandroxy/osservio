import Foundation
import OsservioCore

enum InspectorSearchRecord: Sendable {
    case log(LogEvent), http(HTTPEvent), socket(WebSocketEvent)
    var key: String { switch self {
        case .log(let event): return "log:" + event.sessionID + ":" + String(event.sequence)
        case .http(let event): return "http:" + event.id
        case .socket(let event): return "socket:" + event.id
    } }
    var time: Int64 { switch self { case .log(let event): return event.timestampMillis; case .http(let event): return event.timestampMillis; case .socket(let event): return event.timestampMillis } }
    func matchesFacets(_ query: InspectorQuery, now: Int64) -> Bool {
        switch self { case .log(let event): return query.matchesFacets(event, now: now); case .http(let event): return query.matchesFacets(event, now: now); case .socket(let event): return query.matchesFacets(event, now: now) }
    }
    struct Match { let source: String; let text: String }
    func contains(_ text: String) -> Bool { text.isEmpty || match(text, scope: "all") != nil }
    func match(_ text: String, scope: String) -> Match? {
        guard !text.isEmpty else { return nil }
        func find(_ field: String, _ source: String, _ values: [String]) -> Match? {
            guard scope == "all" || scope == field else { return nil }
            return values.first { InspectorQuery.contains(text, in: $0) }.map { Match(source: source, text: $0) }
        }
        func headers(_ values: [HTTPHeader], _ source: String) -> Match? {
            guard scope == "all" || scope == "headers" else { return nil }
            for header in values where InspectorQuery.contains(text, in: header.name) || InspectorQuery.contains(text, in: header.value) {
                return Match(source: source, text: header.name + ": " + header.value)
            }
            return nil
        }
        switch self {
        case .log(let event):
            return find("identity", "ui_search_identity", [event.sessionID + " #\(event.sequence)"])
                ?? find("metadata", "ui_search_metadata", [event.category, InspectorFilterOptions.label(.level, event.level.rawValue), event.isPrivate ? InspectorLocalization.text("ui_private_content") : ""])
        case .http(let event):
            return find("address", "ui_search_address", [event.url, InspectorRecordPresentation.address(event.url), event.responseURL ?? ""])
                ?? headers(event.requestHeaders, "ui_search_request_headers") ?? headers(event.responseHeaders, "ui_search_response_headers")
                ?? find("identity", "ui_search_identity", [event.id, event.sessionID + " #\(event.sequence)"])
                ?? find("metadata", "ui_search_metadata", [event.method, HTTPPresentation.state(event.state), event.statusCode.map(String.init) ?? "", event.failure.map { InspectorLocalization.text("http_" + $0.rawValue) } ?? ""])
        case .socket(let event):
            return find("address", "ui_search_address", [event.url, InspectorRecordPresentation.address(event.url)])
                ?? headers(event.headers, "ui_search_handshake_headers")
                ?? find("identity", "ui_search_identity", [event.id, event.connectionID, event.relatedMessageID ?? "", event.sessionID + " #\(event.sequence)"])
                ?? find("metadata", "ui_search_metadata", [event.kind.rawValue, event.outcome?.rawValue ?? "", WebSocketPresentation.kind(event), event.direction.map { InspectorLocalization.text("ws_" + $0.rawValue) } ?? "", event.outcome.map { InspectorLocalization.text("ws_outcome_" + $0.rawValue) } ?? "", event.kind == .message ? InspectorRecordPresentation.payloadLabel(event.payload) : "", event.statusCode.map(String.init) ?? "", event.closeCode.map(String.init) ?? "", event.failure.map { InspectorLocalization.text("http_" + $0.rawValue) } ?? ""])
        }
    }
    var headersLimited: Bool { switch self { case .http(let event): return event.headersLimited; case .socket(let event): return event.headersLimited; case .log: return false } }
    static func newestFirst(_ left: Self, _ right: Self) -> Bool {
        if case .socket(let lhs) = left, case .socket(let rhs) = right { return InspectorQuery.timelineNewestFirst(lhs, rhs) }
        return left.time == right.time ? left.key < right.key : left.time > right.time
    }
}

/// On-demand search of sanitized retained evidence, never a host stream. No body index is kept.
enum InspectorRecordSearch {
    static let maximumRecords = 4_000, maximumUnits = 2_097_152
    struct Result {
        var keys: Set<String> = []
        var previews: [String: String] = [:]
        var previewSources: [String: String] = [:]
        var unavailable = 0, truncated = 0
        var limited = false
    }
    private struct Payload {
        let text: String?; let truncated: Bool; var source: String = "ui_search_message"
        var scope: String { source == "ui_search_request_body" ? "request" : source == "ui_search_response_body" ? "response" : "message" }
    }
    static func run(_ records: [InspectorSearchRecord], query: InspectorQuery, now: Int64,
                    http: (any HTTPHistory)?, sockets: (any WebSocketHistory)?,
                    savedPayloads: [String: IncidentPayload]? = nil, unitLimit: Int = maximumUnits, cancelled: @escaping () -> Bool) -> Result? {
        var result = Result(), spent = 0
        let started = DispatchTime.now().uptimeNanoseconds
        for (position, record) in records.enumerated() {
            if cancelled() { return nil }
            if position >= maximumRecords || DispatchTime.now().uptimeNanoseconds - started > 750_000_000 { result.limited = true; break }
            guard record.matchesFacets(query, now: now) else { continue }
            let hit = record.match(query.text, scope: query.searchScope)
            let metadata = query.text.isEmpty || hit != nil
            let incompleteHeaders = query.searches("headers") && record.headersLimited
            if incompleteHeaders { result.truncated += 1 }
            if !query.excludedText.isEmpty && record.match(query.excludedText, scope: query.searchScope) != nil { continue }
            if let hit { result.previews[record.key] = preview(hit.text, term: query.text); result.previewSources[record.key] = hit.source }
            if metadata && query.fieldPath.isEmpty && query.excludedText.isEmpty { result.keys.insert(record.key); continue }
            if spent >= unitLimit { result.limited = true; result.previews[record.key] = nil; result.previewSources[record.key] = nil; continue }
            var matched = metadata && query.fieldPath.isEmpty, excluded = false, exclusionUnknown = incompleteHeaders
            for payload in payloads(record, query: query, http: http, sockets: sockets, saved: savedPayloads) {
                if cancelled() { return nil }
                guard let text = payload.text else { result.unavailable += 1; exclusionUnknown = exclusionUnknown || query.searches(payload.scope); continue }
                let count = text.utf16.count
                guard count <= InspectorJSONDocument.maximumUnits, count <= unitLimit - spent else { result.limited = true; exclusionUnknown = exclusionUnknown || query.searches(payload.scope); continue }
                spent += count
                if payload.truncated { result.truncated += 1; exclusionUnknown = exclusionUnknown || query.searches(payload.scope) }
                if query.searches(payload.scope) && !query.excludedText.isEmpty && InspectorQuery.contains(query.excludedText, in: text) { excluded = true; break }
                var fieldMatches = true
                if !query.fieldPath.isEmpty {
                    if payload.truncated { fieldMatches = false }
                    else {
                        switch InspectorJSONField.matches(text, pointer: query.fieldPath, value: query.fieldValue, cancelled: cancelled) {
                        case .yes: break
                        case .no: fieldMatches = false
                        case .unavailable: fieldMatches = false; result.unavailable += 1
                        }
                    }
                }
                if fieldMatches && (metadata || query.searches(payload.scope) && InspectorQuery.contains(query.text, in: text)) {
                    matched = true
                    if result.previews[record.key] == nil { result.previews[record.key] = preview(text, term: query.text); result.previewSources[record.key] = payload.source }
                }
            }
            if matched && !excluded && (query.excludedText.isEmpty || !exclusionUnknown) { result.keys.insert(record.key) } else { result.previews[record.key] = nil; result.previewSources[record.key] = nil }
        }
        return cancelled() ? nil : result
    }
    private static func payloads(_ record: InspectorSearchRecord, query: InspectorQuery, http: (any HTTPHistory)?, sockets: (any WebSocketHistory)?, saved: [String: IncidentPayload]?) -> [Payload] {
        switch record {
        case .log(let event): return query.searches("message") || !query.fieldPath.isEmpty ? [Payload(text: event.isPrivate ? nil : event.message, truncated: event.isTruncated, source: "ui_search_log_message")] : []
        case .http(let event):
            let references: [HTTPBodyReference] = [event.requestBody, event.responseBody]
            return references.enumerated().compactMap { index, reference in
                guard query.searches(index == 0 ? "request" : "response") || !query.fieldPath.isEmpty else { return nil }
                let source = index == 0 ? "ui_search_request_body" : "ui_search_response_body"
                var text: String?
                if let id = reference.blobID {
                    if let saved { if let payload = saved[id], payload.state == .available { text = payload.text } }
                    else if let http, case .available(let body) = http.body(id: id) { text = body.text }
                }
                return Payload(text: text, truncated: reference.state == .truncated, source: source)
            }
        case .socket(let event):
            guard query.searches("message") || !query.fieldPath.isEmpty, let reference = event.payload else { return [] }
            var text: String?
            if let id = reference.blobID {
                if let saved { if let payload = saved[id], payload.state == .available { text = payload.text } }
                else if let sockets, case .available(let body) = sockets.payload(id: id) { text = body.text }
            }
            return [Payload(text: text, truncated: reference.state == .truncated, source: "ui_search_message")]
        }
    }
    static func preview(_ text: String, term: String) -> String {
        let source = text as NSString
        let hit = term.isEmpty ? NSRange(location: 0, length: 0) : source.range(of: term, options: [.caseInsensitive, .literal])
        var start = source.length <= 180 || hit.location == NSNotFound ? 0 : max(0, hit.location - 30)
        if start > 0 && (0xDC00...0xDFFF).contains(source.character(at: start)) { start -= 1 }
        var end = min(source.length, start + 180)
        if end < source.length && (0xD800...0xDBFF).contains(source.character(at: end - 1)) { end -= 1 }
        return (start > 0 ? "…" : "") + source.substring(with: NSRange(location: start, length: end - start))
            .replacingOccurrences(of: "\n", with: " ") + (end < source.length ? "…" : "")
    }
}

/// One worker and one pending job across inspectors, including cancellation in progress.
final class InspectorRecordSearchTask: Operation, @unchecked Sendable {
    private static let admission = NSLock()
    private static let worker: OperationQueue = {
        let value = OperationQueue(); value.name = "com.sandrox.osservio.record-search"
        value.maxConcurrentOperationCount = 1; value.qualityOfService = .userInitiated; return value
    }()
    private let search: (@escaping () -> Bool) -> InspectorRecordSearch.Result?
    private let completion: (InspectorRecordSearch.Result) -> Void
    private init(search: @escaping (@escaping () -> Bool) -> InspectorRecordSearch.Result?, completion: @escaping (InspectorRecordSearch.Result) -> Void) {
        self.search = search; self.completion = completion
    }
    static func submit(search: @escaping (@escaping () -> Bool) -> InspectorRecordSearch.Result?, completion: @escaping (InspectorRecordSearch.Result) -> Void) -> InspectorRecordSearchTask? {
        admission.lock(); defer { admission.unlock() }
        guard worker.operationCount < 2 else { return nil }
        let task = InspectorRecordSearchTask(search: search, completion: completion); worker.addOperation(task); return task
    }
    override func main() {
        guard !isCancelled, let result = search({ [weak self] in self?.isCancelled ?? true }), !isCancelled else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self, !self.isCancelled else { return }; self.completion(result)
        }
    }
}
