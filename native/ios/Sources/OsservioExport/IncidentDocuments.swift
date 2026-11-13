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
            "origin": snapshot.systemDiagnostic == nil ? "manual" : "systemDiagnostic",
            "systemDiagnostic": optional(snapshot.systemDiagnostic.map(diagnostic)),
            "note": optional(snapshot.note.map { ["text": $0.text, "isPrivate": $0.isPrivate, "isTruncated": $0.isTruncated] }),
            "environment": optional(snapshot.environment.map(environment)),
            "breadcrumbs": optional(snapshot.breadcrumbs.map(breadcrumbs)),
            "logs": optional(snapshot.logs.map(logs)), "http": optional(snapshot.http.map(http)),
            "webSockets": optional(snapshot.webSockets.map(webSockets))
        ]
        let metadata: [String: Any] = ["format": "osservio.incident", "revision": 7, "payloadFile": "payloads.json", "incident": incident]
        let payloads: [String: Any] = ["format": "osservio.incident.payloads", "revision": 7, "incidentID": snapshot.id,
            "logs": content(snapshot.logs?.payloads ?? [:]), "http": content(snapshot.http?.payloads ?? [:]), "webSockets": content(snapshot.webSockets?.payloads ?? [:])]
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
    private static func diagnostic(_ value: SystemDiagnostic) -> [String: Any] {
        let android = value.androidExit.map { exit -> [String: Any] in
            ["reasonCode": exit.reasonCode, "status": exit.status, "processID": exit.processID,
             "importance": exit.importance, "pssKiB": decimal(exit.pssKiB), "rssKiB": decimal(exit.rssKiB),
             "tombstoneState": exit.tombstoneState.rawValue, "tombstone": optional(exit.tombstone.map(tombstone))]
        }
        let metric = value.metricKit.map { report -> [String: Any] in
            var metadata: [String: Any] = [:]
            for (key, text) in report.metadata {
                metadata[key.rawValue] = ["text": text.value, "isTruncated": text.isTruncated]
            }
            return ["metadata": metadata, "signal": optional(report.signal), "exceptionType": decimal(report.exceptionType),
                    "exceptionCode": optional(report.exceptionCode), "hangDurationMillis": decimal(report.hangDurationMillis)]
        }
        return ["source": value.source.rawValue, "kind": value.kind.rawValue,
                "intervalStartMillis": String(value.intervalStartMillis), "intervalEndMillis": String(value.intervalEndMillis),
                "androidExit": optional(android), "metricKit": optional(metric), "appleSignal": optional(value.appleSignal.map(signalCrash))]
    }
    private static func tombstone(_ trace: AndroidTombstone) -> [String: Any] {
        ["architectureCode": trace.architectureCode, "signalNumber": trace.signalNumber, "signalCode": trace.signalCode,
         "threadID": trace.threadID, "faultAddress": decimal(trace.faultAddress), "frames": trace.frames.map { frame -> [String: Any] in
             ["programCounter": String(frame.programCounter), "relativeProgramCounter": String(frame.relativeProgramCounter), "buildID": optional(frame.buildID)]
         }]
    }
    private static func signalCrash(_ crash: AppleSignalCrash) -> [String: Any] {
        var metadata: [String: Any] = [:]
        for (key, text) in crash.metadata { metadata[key.rawValue] = ["text": text.value, "isTruncated": text.isTruncated] }
        return ["reportID": crash.reportID, "signal": crash.signal.rawValue, "code": crash.code,
                "faultAddress": String(crash.faultAddress), "threadNumber": optional(crash.threadNumber),
                "frames": crash.frames.map(String.init), "imageCacheIncomplete": crash.imageCacheIncomplete, "metadata": metadata,
                "images": crash.images.map { image -> [String: Any] in
                    ["baseAddress": String(image.baseAddress), "size": String(image.size), "uuid": optional(image.uuid),
                     "cpuType": decimal(image.cpuType), "cpuSubtype": decimal(image.cpuSubtype)]
                }]
    }
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
         "sourceEvictedDetails": String(value.sourceEvictedDetails), "retainedPayloadBytes": value.retainedPayloadBytes, "payloads": selection(value.payloads),
         "events": value.events.map { event -> [String: Any] in
            ["sessionID": event.sessionID, "sequence": String(event.sequence), "timestampMillis": String(event.timestampMillis),
             "level": event.level.rawValue, "category": event.category, "message": event.message,
             "isPrivate": event.isPrivate, "isTruncated": event.isTruncated,
             "details": optional(event.details.map { ["state": $0.state.rawValue, "blobID": optional($0.blobID), "textByteCount": $0.textByteCount] as [String: Any] })]
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
             "failure": optional(event.failure?.rawValue), "durationMillis": decimal(event.durationMillis), "metrics": event.metrics.map(metrics) ?? NSNull(),
             "requestBody": body(event.requestBody), "responseBody": body(event.responseBody)]
         }]
    }
    private static func metrics(_ value: HTTPMetrics) -> [String: Any] {
        ["protocol": optional(value.protocolName), "cache": value.cache.rawValue,
         "connectionReused": optional(value.connectionReused), "limited": value.limited,
         "phases": value.phases.map { ["phase": $0.phase.rawValue, "startMillis": String($0.startMillis), "endMillis": optional($0.endMillis.map(String.init))] as [String: Any] }]
    }
    private static func body(_ value: HTTPBodyReference) -> [String: Any] {
        ["state": value.state.rawValue, "blobID": optional(value.blobID), "observedBytes": String(value.observedBytes), "isProcessed": value.isProcessed]
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
                ["state": reference.state.rawValue, "blobID": optional(reference.blobID), "byteCount": decimal(reference.byteCount), "isProcessed": reference.isProcessed]
             })]
         }]
    }
}
