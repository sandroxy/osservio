import Foundation
import OsservioCore

public enum IncidentDocumentError: Error, Sendable { case tooLarge, invalidRecord }

/// Portable UTF-8 documents from one immutable sanitized snapshot. Encoding performs no
/// capture, history lookup, storage access, or transport. Use away from capture and UI paths.
public struct IncidentDocuments: Sendable {
    public let metadataJSON: Data
    public let payloadsJSON: Data
    public static let maximumTextBytes = 2_097_152
    public static let maximumDocumentBytes = 16_777_216

    public static func encode(_ snapshot: IncidentSnapshot) throws -> IncidentDocuments {
        guard snapshot.textByteCount <= maximumTextBytes else { throw IncidentDocumentError.tooLarge }
        let incident: [String: Any] = [
            "id": snapshot.id, "timestampMillis": String(snapshot.timestampMillis),
            "note": optional(snapshot.note.map { ["text": $0.text, "isPrivate": $0.isPrivate, "isTruncated": $0.isTruncated] }),
            "environment": optional(snapshot.environment.map(environment)),
            "breadcrumbs": optional(snapshot.breadcrumbs.map(breadcrumbs)),
            "logs": optional(snapshot.logs.map(logs)), "http": optional(snapshot.http.map(http)),
            "webSockets": optional(snapshot.webSockets.map(webSockets))
        ]
        let metadata: [String: Any] = ["format": "osservio.incident", "revision": 1, "payloadFile": "payloads.json", "incident": incident]
        let payloads: [String: Any] = ["format": "osservio.incident.payloads", "revision": 1, "incidentID": snapshot.id,
            "http": content(snapshot.http?.payloads ?? [:]), "webSockets": content(snapshot.webSockets?.payloads ?? [:])]
        let metadataJSON: Data, payloadsJSON: Data
        do {
            metadataJSON = try JSONSerialization.data(withJSONObject: metadata, options: [.sortedKeys])
            payloadsJSON = try JSONSerialization.data(withJSONObject: payloads, options: [.sortedKeys])
        } catch { throw IncidentDocumentError.invalidRecord }
        guard metadataJSON.count <= maximumDocumentBytes - payloadsJSON.count else { throw IncidentDocumentError.tooLarge }
        return IncidentDocuments(metadataJSON: metadataJSON, payloadsJSON: payloadsJSON)
    }
    private static func optional<T>(_ value: T?) -> Any { value.map { $0 as Any } ?? NSNull() }
    private static func decimal<T: BinaryInteger>(_ value: T?) -> Any { optional(value.map(String.init)) }
    private static func content(_ payloads: [String: IncidentPayload]) -> [String: String] {
        payloads.compactMapValues { $0.text }
    }
    private static func selection(_ payloads: [String: IncidentPayload]) -> [String: Any] {
        payloads.mapValues { ["state": $0.state.rawValue, "textByteCount": $0.textByteCount] as [String: Any] }
    }
    private static func headers(_ values: [HTTPHeader]) -> [[String: String]] { values.map { ["name": $0.name, "value": $0.value] } }
    private static func environment(_ value: EnvironmentSnapshot) -> [String: Any] {
        var facts: [String: Any] = [:]
        for (key, value) in value.values {
            let kind: String, fact: Any
            switch value.fact {
            case .text(let text): kind = "text"; fact = text
            case .number(let number): kind = "number"; fact = String(number)
            case .flag(let flag): kind = "flag"; fact = flag
            case .unavailable: kind = "unavailable"; fact = NSNull()
            case .unsupported: kind = "unsupported"; fact = NSNull()
            }
            facts[key.rawValue] = ["type": kind, "value": fact, "isTruncated": value.isTruncated]
        }
        return ["platform": value.platform.rawValue, "timestampMillis": String(value.timestampMillis), "values": facts]
    }
    private static func breadcrumbs(_ value: IncidentBreadcrumbs) -> [String: Any] {
        ["sourceEventCount": value.sourceEventCount, "omittedEventCount": value.omittedEventCount,
         "sourceEvictedCount": String(value.sourceEvictedCount), "retainedTextBytes": value.retainedTextBytes,
         "events": value.events.map { event -> [String: Any] in
            ["id": event.id, "sessionID": event.sessionID, "sequence": String(event.sequence), "timestampMillis": String(event.timestampMillis),
             "sessionElapsedMillis": decimal(event.sessionElapsedMillis), "kind": event.kind.rawValue, "label": event.label,
             "message": optional(event.message), "isPrivate": event.isPrivate, "isTruncated": event.isTruncated,
             "value": decimal(event.value), "retention": event.retention.rawValue]
         }]
    }
    private static func logs(_ value: IncidentLogs) -> [String: Any] {
        ["observedAtMillis": String(value.observedAtMillis), "sourceEventCount": value.sourceEventCount, "omittedEventCount": value.omittedEventCount,
         "sourceEvictedCount": String(value.sourceEvictedCount), "retainedTextBytes": value.retainedTextBytes,
         "events": value.events.map { event -> [String: Any] in
            ["sessionID": event.sessionID, "sequence": String(event.sequence), "timestampMillis": String(event.timestampMillis),
             "level": event.level.rawValue, "category": event.category, "message": event.message,
             "isPrivate": event.isPrivate, "isTruncated": event.isTruncated]
         }]
    }
    private static func http(_ value: IncidentHTTP) -> [String: Any] {
        ["observedAtMillis": String(value.observedAtMillis), "sourceEventCount": value.sourceEventCount, "omittedEventCount": value.omittedEventCount,
         "sourceEvictedEvents": String(value.sourceEvictedEvents), "sourceEvictedBodies": String(value.sourceEvictedBodies),
         "retainedTextBytes": value.retainedTextBytes, "retainedPayloadBytes": value.retainedPayloadBytes, "payloads": selection(value.payloads),
         "events": value.events.map { event -> [String: Any] in
            ["id": event.id, "sessionID": event.sessionID, "sequence": String(event.sequence), "revision": String(event.revision),
             "timestampMillis": String(event.timestampMillis), "method": event.method, "source": event.source.rawValue, "url": event.url,
             "responseURL": optional(event.responseURL), "requestHeaders": headers(event.requestHeaders), "responseHeaders": headers(event.responseHeaders),
             "headersLimited": event.headersLimited, "statusCode": optional(event.statusCode), "state": event.state.rawValue,
             "failure": optional(event.failure?.rawValue), "durationMillis": decimal(event.durationMillis),
             "requestBody": body(event.requestBody), "responseBody": body(event.responseBody)]
         }]
    }
    private static func body(_ value: HTTPBodyReference) -> [String: Any] {
        ["state": value.state.rawValue, "blobID": optional(value.blobID), "observedBytes": String(value.observedBytes)]
    }
    private static func webSockets(_ value: IncidentWebSockets) -> [String: Any] {
        ["observedAtMillis": String(value.observedAtMillis), "sourceEventCount": value.sourceEventCount, "omittedEventCount": value.omittedEventCount,
         "sourceEvictedEvents": String(value.sourceEvictedEvents), "sourceEvictedPayloads": String(value.sourceEvictedPayloads),
         "retainedTextBytes": value.retainedTextBytes, "retainedPayloadBytes": value.retainedPayloadBytes, "payloads": selection(value.payloads),
         "events": value.events.map { event -> [String: Any] in
            ["id": event.id, "connectionID": event.connectionID, "sessionID": event.sessionID, "sequence": String(event.sequence),
             "timestampMillis": String(event.timestampMillis), "url": event.url, "source": event.source.rawValue, "kind": event.kind.rawValue,
             "headers": headers(event.headers), "headersLimited": event.headersLimited, "statusCode": optional(event.statusCode), "closeCode": optional(event.closeCode),
             "failure": optional(event.failure?.rawValue), "direction": optional(event.direction?.rawValue), "outcome": optional(event.outcome?.rawValue),
             "relatedMessageID": optional(event.relatedMessageID), "payload": optional(event.payload.map { reference -> [String: Any] in
                ["state": reference.state.rawValue, "blobID": optional(reference.blobID), "byteCount": decimal(reference.byteCount)]
             })]
         }]
    }
}
