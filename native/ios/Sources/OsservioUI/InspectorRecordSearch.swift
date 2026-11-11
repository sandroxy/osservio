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
    func contains(_ text: String) -> Bool {
        if text.isEmpty { return true }
        let fields: [String]
        switch self {
        case .log(let event): fields = [event.category, InspectorFilterOptions.label(.level, event.level.rawValue), event.isPrivate ? InspectorLocalization.text("ui_private_content") : ""]
        case .http(let event):
            fields = [event.url, InspectorRecordPresentation.address(event.url), event.responseURL ?? "", event.method, event.id, HTTPPresentation.state(event.state), event.statusCode.map(String.init) ?? "", event.failure.map { InspectorLocalization.text("http_" + $0.rawValue) } ?? ""]
                + (event.requestHeaders + event.responseHeaders).flatMap { [$0.name, $0.value] }
        case .socket(let event):
            fields = [event.url, InspectorRecordPresentation.address(event.url), event.id, event.connectionID, event.kind.rawValue, event.outcome?.rawValue ?? "", WebSocketPresentation.kind(event), event.direction.map { InspectorLocalization.text("ws_" + $0.rawValue) } ?? "", event.outcome.map { InspectorLocalization.text("ws_outcome_" + $0.rawValue) } ?? "", event.kind == .message ? InspectorRecordPresentation.payloadLabel(event.payload) : "", event.failure.map { InspectorLocalization.text("http_" + $0.rawValue) } ?? ""]
        }
        return fields.contains { InspectorQuery.contains(text, in: $0) }
    }
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
    private struct Payload { let text: String?; let truncated: Bool; var source: String = "" }
    static func run(_ records: [InspectorSearchRecord], query: InspectorQuery, now: Int64,
                    http: (any HTTPHistory)?, sockets: (any WebSocketHistory)?,
                    savedPayloads: [String: IncidentPayload]? = nil, unitLimit: Int = maximumUnits, cancelled: @escaping () -> Bool) -> Result? {
        var result = Result(), spent = 0
        let started = DispatchTime.now().uptimeNanoseconds
        for (position, record) in records.enumerated() {
            if cancelled() { return nil }
            if position >= maximumRecords || DispatchTime.now().uptimeNanoseconds - started > 750_000_000 { result.limited = true; break }
            guard record.matchesFacets(query, now: now) else { continue }
            let metadata = record.contains(query.text)
            if !query.excludedText.isEmpty && record.contains(query.excludedText) { continue }
            if metadata && query.fieldPath.isEmpty && query.excludedText.isEmpty { result.keys.insert(record.key); continue }
            if spent >= unitLimit { result.limited = true; continue }
            var matched = metadata && query.fieldPath.isEmpty, excluded = false, exclusionUnknown = false
            for payload in payloads(record, http: http, sockets: sockets, saved: savedPayloads) {
                if cancelled() { return nil }
                guard let text = payload.text else { result.unavailable += 1; exclusionUnknown = true; continue }
                let count = text.utf16.count
                guard count <= InspectorJSONDocument.maximumUnits, count <= unitLimit - spent else { result.limited = true; exclusionUnknown = true; continue }
                spent += count
                if payload.truncated { result.truncated += 1; exclusionUnknown = true }
                if !query.excludedText.isEmpty && InspectorQuery.contains(query.excludedText, in: text) { excluded = true; break }
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
                if fieldMatches && (metadata || InspectorQuery.contains(query.text, in: text)) {
                    matched = true
                    if result.previews[record.key] == nil { result.previews[record.key] = preview(text, term: query.text); result.previewSources[record.key] = payload.source }
                }
            }
            if matched && !excluded && (query.excludedText.isEmpty || !exclusionUnknown) { result.keys.insert(record.key) } else { result.previews[record.key] = nil }
        }
        return cancelled() ? nil : result
    }
    private static func payloads(_ record: InspectorSearchRecord, http: (any HTTPHistory)?, sockets: (any WebSocketHistory)?, saved: [String: IncidentPayload]?) -> [Payload] {
        switch record {
        case .log(let event): return [Payload(text: event.isPrivate ? nil : event.message, truncated: event.isTruncated)]
        case .http(let event):
            let references: [HTTPBodyReference] = [event.requestBody, event.responseBody]
            return references.enumerated().map { index, reference in
                let source = index == 0 ? "ui_search_request_body" : "ui_search_response_body"
                var text: String?
                if let id = reference.blobID {
                    if let saved { if let payload = saved[id], payload.state == .available { text = payload.text } }
                    else if let http, case .available(let body) = http.body(id: id) { text = body.text }
                }
                return Payload(text: text, truncated: reference.state == .truncated, source: source)
            }
        case .socket(let event):
            guard let reference = event.payload else { return [] }
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
