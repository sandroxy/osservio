import Foundation

/// Explicitly selected evidence, independent of a localized title or editable note.
public struct IncidentFocus: Sendable {
    public enum Kind: String, Sendable { case log, http, webSocket }
    public let kind: Kind
    public let sessionID: String
    public let sequence: Int64
    /// Logs use session and sequence; network observations additionally retain their event ID.
    public let eventID: String?
    public static func log(_ event: LogEvent) -> IncidentFocus { .init(kind: .log, sessionID: event.sessionID, sequence: event.sequence, eventID: nil) }
    public static func http(_ event: HTTPEvent) -> IncidentFocus { .init(kind: .http, sessionID: event.sessionID, sequence: event.sequence, eventID: event.id) }
    public static func webSocket(_ event: WebSocketEvent) -> IncidentFocus { .init(kind: .webSocket, sessionID: event.sessionID, sequence: event.sequence, eventID: event.id) }
    public static func restore(kind: Kind, sessionID: String, sequence: Int64, eventID: String?) throws -> IncidentFocus {
        func valid(_ value: String) -> Bool { value.utf8.count == 36 && UUID(uuidString: value) != nil }
        guard valid(sessionID), sequence > 0, kind == .log ? eventID == nil : eventID.map(valid) == true else { throw PersistenceError.invalidRecord }
        return .init(kind: kind, sessionID: sessionID, sequence: sequence, eventID: eventID)
    }
    public func matches(_ event: LogEvent) -> Bool { kind == .log && sameSession(event.sessionID, event.sequence) }
    public func matches(_ event: HTTPEvent) -> Bool { kind == .http && sameSession(event.sessionID, event.sequence) && eventID?.lowercased() == event.id.lowercased() }
    public func matches(_ event: WebSocketEvent) -> Bool { kind == .webSocket && sameSession(event.sessionID, event.sequence) && eventID?.lowercased() == event.id.lowercased() }
    private func sameSession(_ id: String, _ value: Int64) -> Bool { sequence == value && sessionID.lowercased() == id.lowercased() }
    func exists(in logs: IncidentLogs?, http: IncidentHTTP?, sockets: IncidentWebSockets?) -> Bool {
        switch kind {
        case .log: return logs?.events.contains(where: matches) == true
        case .http: return http?.events.contains(where: matches) == true
        case .webSocket: return sockets?.events.contains(where: matches) == true
        }
    }
}
