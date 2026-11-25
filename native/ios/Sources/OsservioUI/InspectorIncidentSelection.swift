import Foundation
import OsservioCore

/// A single explicit observation, not a query for the latest version or nearby activity.
enum InspectorIncidentSelection {
    case log(LogEvent), http(HTTPEvent), socket(WebSocketEvent)
    enum PreparationError: Error { case tooLarge }

    func mark(breadcrumbs: BreadcrumbSnapshot?) throws -> IncidentSnapshot {
        func payloads(_ ids: [String?]) throws -> [String: IncidentPayload] {
            var result: [String: IncidentPayload] = [:]
            for id in ids.compactMap({ $0 }) { result[id] = try .restore(id: id, state: .notSelected, text: nil) }
            return result
        }
        let now = SystemLogClock().wallTimeMillis()
        let focus: IncidentFocus, size: Int
        switch self {
        case .log(let event): focus = .log(event); size = event.textByteCount
        case .http(let event): focus = .http(event); size = event.textByteCount
        case .socket(let event): focus = .webSocket(event); size = event.textByteCount
        }
        // Leaves room for optional context, note and a single bounded payload copy.
        guard size <= 65_536 else { throw PreparationError.tooLarge }
        var logs: IncidentLogs?, http: IncidentHTTP?, sockets: IncidentWebSockets?
        switch self {
        case .log(let event):
            logs = try .restore(events: [event], observedAtMillis: now, sourceEventCount: 1, sourceEvictedCount: 0,
                payloads: payloads([event.details?.blobID]), sourceEvictedDetails: 0)
        case .http(let event):
            http = try .restore(events: [event], payloads: payloads([event.requestBody.blobID, event.responseBody.blobID]),
                observedAtMillis: now, sourceEventCount: 1, sourceEvictedEvents: 0, sourceEvictedBodies: 0)
        case .socket(let event):
            sockets = try .restore(events: [event], payloads: payloads([event.payload?.blobID]),
                observedAtMillis: now, sourceEventCount: 1, sourceEvictedEvents: 0, sourceEvictedPayloads: 0)
        }
        return try IncidentSnapshot.capture(breadcrumbs: breadcrumbs, logs: logs, http: http, webSockets: sockets,
            breadcrumbTextByteLimit: 32_768).withFocus(focus)
    }
    static func selected(in snapshot: IncidentSnapshot) -> Self? {
        guard let focus = snapshot.focus else { return nil }
        switch focus.kind {
        case .log: return snapshot.logs?.events.first(where: focus.matches).map(Self.log)
        case .http: return snapshot.http?.events.first(where: focus.matches).map(Self.http)
        case .webSocket: return snapshot.webSockets?.events.first(where: focus.matches).map(Self.socket)
        }
    }
}
