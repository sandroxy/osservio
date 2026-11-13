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
        func find(_ field: String, _ source: String, _ values: @autoclosure () -> [String]) -> Match? {
            guard scope == "all" || scope == field else { return nil }
            return values().first { InspectorQuery.contains(text, in: $0) }.map { Match(source: source, text: $0) }
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
    /// Sort lightweight indices rather than repeatedly moving payload-bearing enum values.
    static func ordered(_ records: [Self]) -> [Self] {
        records.indices.sorted { newestFirst(records[$0], records[$1]) }.map { records[$0] }
    }
    private enum OrderingStopped: Error { case cancelled }
    static func searchOrder(count: Int, record: (Int) -> Self, includes: (Int) -> Bool,
                            cancelled: () -> Bool) -> [Int]? {
        var positions: [Int] = []
        for index in 0..<count {
            if index % 256 == 0 && cancelled() { return nil }
            if includes(index) { positions.append(index) }
        }
        var comparisons = 0
        do {
            try positions.sort { left, right in
                comparisons += 1
                if comparisons % 256 == 0 && cancelled() { throw OrderingStopped.cancelled }
                return newestFirst(record(left), record(right))
            }
        } catch { return nil }
        return cancelled() ? nil : positions
    }
}

/// On-demand search of sanitized retained evidence, never a host stream. No body index is kept.
enum InspectorRecordSearch {
    static let maximumUnits = InspectorJSONDocument.maximumUnits
    struct Result {
        var keys: Set<String> = []
        var positions: [Int] = []
        var scanned = 0, total = 0
        var complete = true
        var previews: [String: String] = [:]
        var previewSources: [String: String] = [:]
        var unavailable = 0, truncated = 0
        var limited = false
    }
    private struct Payload {
        let text: String?; let truncated: Bool; var source: String = "ui_search_message"
        var scope: String { source == "ui_search_request_body" ? "request" : source == "ui_search_response_body" ? "response" : "message" }
    }
    static func run<Records: Collection>(_ records: Records, query: InspectorQuery, now: Int64,
                    http: (any HTTPHistory)?, sockets: (any WebSocketHistory)?,
                    savedPayloads: [String: IncidentPayload]? = nil, logs: (any LogDetailsHistory)? = nil, unitLimit: Int = maximumUnits, cancelled: @escaping () -> Bool,
                    position: (Int) -> Int = { $0 }, progress: (Result) -> Void = { _ in }) -> Result? where Records.Element == InspectorSearchRecord {
        var result = Result(); result.total = records.count; result.complete = false
        var sliceStart = DispatchTime.now().uptimeNanoseconds, publishedAt: UInt64 = 0, sliceCount = 0
        for (index, record) in records.enumerated() {
            if cancelled() { return nil }
            if match(record, query: query, now: now, http: http, sockets: sockets,
                     savedPayloads: savedPayloads, logs: logs, unitLimit: unitLimit, cancelled: cancelled, result: &result) {
                result.keys.insert(record.key); result.positions.append(position(index))
            }
            result.scanned = index + 1; sliceCount += 1
            let tick = DispatchTime.now().uptimeNanoseconds
            if sliceCount >= 256 || tick - sliceStart >= 8_000_000 {
                if publishedAt == 0 || tick - publishedAt >= 100_000_000 {
                    if cancelled() { return nil }; progress(result); publishedAt = tick
                }
                // Cooperate with host work without abandoning the rest of the retained window.
                Thread.sleep(forTimeInterval: 0.001)
                sliceStart = DispatchTime.now().uptimeNanoseconds; sliceCount = 0
            }
        }
        result.complete = true
        return cancelled() ? nil : result
    }
    private static func match(_ record: InspectorSearchRecord, query: InspectorQuery, now: Int64,
                          http: (any HTTPHistory)?, sockets: (any WebSocketHistory)?,
                          savedPayloads: [String: IncidentPayload]?, logs: (any LogDetailsHistory)?,
                          unitLimit: Int, cancelled: @escaping () -> Bool, result: inout Result) -> Bool {
        guard record.matchesFacets(query, now: now) else { return false }
        let hit = record.match(query.text, scope: query.searchScope)
        let metadata = query.text.isEmpty || hit != nil
        let incompleteHeaders = query.searches("headers") && record.headersLimited
        if incompleteHeaders { result.truncated += 1 }
        if !query.excludedText.isEmpty && record.match(query.excludedText, scope: query.searchScope) != nil { return false }
        if let hit { result.previews[record.key] = preview(hit.text, term: query.text); result.previewSources[record.key] = hit.source }
        if metadata && query.fieldPath.isEmpty && query.excludedText.isEmpty { return true }
        var matched = metadata && query.fieldPath.isEmpty, excluded = false, exclusionUnknown = incompleteHeaders
        for payload in payloads(record, query: query, http: http, sockets: sockets, saved: savedPayloads, logs: logs) {
            if cancelled() { return false }
            guard let text = payload.text else { result.unavailable += 1; exclusionUnknown = exclusionUnknown || query.searches(payload.scope); continue }
            let count = text.utf16.count
            guard count <= InspectorJSONDocument.maximumUnits, count <= unitLimit else { result.limited = true; exclusionUnknown = exclusionUnknown || query.searches(payload.scope); continue }
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
        if matched && !excluded && (query.excludedText.isEmpty || !exclusionUnknown) { return true }
        result.previews[record.key] = nil; result.previewSources[record.key] = nil
        return false
    }
    private static func payloads(_ record: InspectorSearchRecord, query: InspectorQuery, http: (any HTTPHistory)?, sockets: (any WebSocketHistory)?, saved: [String: IncidentPayload]?, logs: (any LogDetailsHistory)?) -> [Payload] {
        switch record {
        case .log(let event):
            guard query.searches("message") || !query.fieldPath.isEmpty else { return [] }
            var result = [Payload(text: event.isPrivate ? nil : event.message, truncated: event.isTruncated, source: "ui_search_log_message")]
            if let reference = event.details {
                var text: String?
                if let id = reference.blobID {
                    if let saved { if let payload = saved[id], payload.state == .available { text = payload.text } }
                    else if let logs, case .available(let payload) = logs.details(id: id) { text = payload.text }
                }
                result.append(Payload(text: text, truncated: false, source: "ui_search_log_details"))
            }
            return result
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
    typealias Work = (@escaping () -> Bool, @escaping (InspectorRecordSearch.Result) -> Void) -> InspectorRecordSearch.Result?
    private let search: Work
    private let completion: (InspectorRecordSearch.Result) -> Void
    private let deliveryLock = NSLock()
    private var pendingResult: InspectorRecordSearch.Result?
    private var deliveryScheduled = false
    private init(search: @escaping Work, completion: @escaping (InspectorRecordSearch.Result) -> Void) {
        self.search = search; self.completion = completion
    }
    static func submit(search: @escaping (@escaping () -> Bool) -> InspectorRecordSearch.Result?, completion: @escaping (InspectorRecordSearch.Result) -> Void) -> InspectorRecordSearchTask? {
        submitProgressive(search: { cancelled, _ in search(cancelled) }, completion: completion)
    }
    static func submitProgressive(search: @escaping Work, completion: @escaping (InspectorRecordSearch.Result) -> Void) -> InspectorRecordSearchTask? {
        admission.lock(); defer { admission.unlock() }
        guard worker.operationCount < 2 else { return nil }
        let task = InspectorRecordSearchTask(search: search, completion: completion); worker.addOperation(task); return task
    }
    override func main() {
        let span = InspectorPerformance.begin(.recordSearch); defer { span?.end() }
        guard !isCancelled, let result = search({ [weak self] in self?.isCancelled ?? true }, { [weak self] in self?.publish($0) }), !isCancelled else { return }
        publish(result)
    }
    private func publish(_ result: InspectorRecordSearch.Result) {
        deliveryLock.lock()
        guard !isCancelled else { deliveryLock.unlock(); return }
        pendingResult = result
        guard !deliveryScheduled else { deliveryLock.unlock(); return }
        deliveryScheduled = true; deliveryLock.unlock()
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.deliveryLock.lock()
            let value = self.pendingResult; self.pendingResult = nil; self.deliveryScheduled = false
            self.deliveryLock.unlock()
            if !self.isCancelled, let value { self.completion(value) }
        }
    }
    override func cancel() {
        super.cancel()
        deliveryLock.lock(); pendingResult = nil; deliveryLock.unlock()
    }
}
