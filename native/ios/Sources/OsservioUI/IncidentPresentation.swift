#if canImport(UIKit)
import OsservioCore
import UIKit

enum IncidentPresentation {
    static func title(_ kind: SystemDiagnosticKind?) -> String {
        kind.map(SystemDiagnosticPresentation.kind) ?? RuntimePresentation.text("incident_manual")
    }
    static func listTitle(_ summary: IncidentSummary) -> String {
        if let note = summary.note, !note.isPrivate, !note.text.isEmpty { return note.text }
        return title(summary.diagnosticKind)
    }
    static func listSubtitle(_ summary: IncidentSummary) -> String {
        var evidence: [String] = []
        if summary.hasEnvironment { evidence.append(RuntimePresentation.text("runtime_environment")) }
        for (key, count) in [("runtime_breadcrumbs", summary.breadcrumbCount), ("logs", summary.logCount),
                             ("HTTP", summary.httpCount), ("ws_title", summary.webSocketCount)] {
            if let count { evidence.append((key == "HTTP" ? key : RuntimePresentation.text(key)) + ": \(count)") }
        }
        return (summary.note?.isPrivate == true ? RuntimePresentation.text("incident_private_note") + "\n" : "")
            + (evidence.isEmpty ? RuntimePresentation.text("incident_no_attachments") : evidence.joined(separator: " · "))
            + "\n" + timeLabel(summary.diagnosticKind) + " · " + RuntimePresentation.date(summary.timestampMillis)
    }
    static func timeLabel(_ kind: SystemDiagnosticKind?) -> String {
        RuntimePresentation.text(kind == nil ? "incident_frozen_time" : "diagnostic_recovered_at")
    }
    static func hint(_ snapshot: IncidentSnapshot) -> String {
        RuntimePresentation.text(snapshot.systemDiagnostic == nil ? "incident_snapshot_hint" : "diagnostic_snapshot_hint")
    }
    static func listDetail(_ summary: IncidentSummary) -> String {
        guard summary.diagnosticKind != nil else { return note(summary.note) + "\n" + attachments(summary) }
        return timeLabel(summary.diagnosticKind) + ": " + RuntimePresentation.date(summary.timestampMillis)
            + (summary.note.map { "\n" + note($0) } ?? "")
    }
    static func note(_ note: IncidentNote?) -> String {
        guard let note else { return RuntimePresentation.text("incident_no_note") }
        return (note.isPrivate ? RuntimePresentation.text("incident_private_note") : note.text)
            + (note.isTruncated ? "\n" + RuntimePresentation.text("truncated") : "")
    }
    static func counts(_ context: IncidentBreadcrumbs?) -> String {
        guard let context else { return RuntimePresentation.text("incident_not_attached") }
        return String(format: RuntimePresentation.text("incident_counts"), context.events.count, context.omittedEventCount, context.sourceEvictedCount)
    }
    static func attachments(_ summary: IncidentSummary) -> String {
        let environment = RuntimePresentation.text("runtime_environment") + ": "
            + RuntimePresentation.text(summary.hasEnvironment ? "runtime_yes" : "incident_not_attached")
        let breadcrumbs = RuntimePresentation.text("runtime_breadcrumbs") + ": "
            + (summary.breadcrumbCount.map(String.init) ?? RuntimePresentation.text("incident_not_attached"))
        return environment + " · " + breadcrumbs + "\n" + RuntimePresentation.text("logs") + ": " + count(summary.logCount)
            + " · HTTP: " + count(summary.httpCount) + " · " + RuntimePresentation.text("ws_title") + ": " + count(summary.webSocketCount)
    }
    static func count(_ value: Int?) -> String { value.map(String.init) ?? RuntimePresentation.text("incident_not_attached") }
    static func window(time: Int64, count: Int, omitted: Int, evicted: Int64, payloadEvicted: Int64? = nil) -> String {
        var value = String(format: RuntimePresentation.text("incident_evidence_time"), RuntimePresentation.date(time)) + "\n"
            + String(format: RuntimePresentation.text("incident_evidence_counts"), count, omitted, evicted)
        if let payloadEvicted { value += "\n" + String(format: RuntimePresentation.text("incident_evidence_payload_evicted"), payloadEvicted) }
        return value
    }
    static func summary(_ snapshot: IncidentSnapshot) -> String {
        var lines = [title(snapshot.systemDiagnostic?.kind),
            timeLabel(snapshot.systemDiagnostic?.kind) + ": " + RuntimePresentation.date(snapshot.timestampMillis),
            "ID: " + snapshot.id, note(snapshot.note),
            RuntimePresentation.text("runtime_breadcrumbs") + ": " + counts(snapshot.breadcrumbs)]
        if let diagnostic = snapshot.systemDiagnostic { lines.append(SystemDiagnosticPresentation.summary(diagnostic)) }
        if let environment = snapshot.environment { lines.append(RuntimePresentation.environment(environment)) }
        else { lines.append(RuntimePresentation.text("runtime_environment") + ": " + RuntimePresentation.text("incident_not_attached")) }
        lines.append(RuntimePresentation.text("logs") + ": " + (snapshot.logs.map { window(time: $0.observedAtMillis, count: $0.events.count, omitted: $0.omittedEventCount, evicted: $0.sourceEvictedCount) } ?? count(nil)))
        lines.append("HTTP: " + (snapshot.http.map { window(time: $0.observedAtMillis, count: $0.events.count, omitted: $0.omittedEventCount,
            evicted: $0.sourceEvictedEvents, payloadEvicted: $0.sourceEvictedBodies) } ?? count(nil)))
        lines.append(RuntimePresentation.text("ws_title") + ": " + (snapshot.webSockets.map { window(time: $0.observedAtMillis, count: $0.events.count,
            omitted: $0.omittedEventCount, evicted: $0.sourceEvictedEvents, payloadEvicted: $0.sourceEvictedPayloads) } ?? count(nil)))
        lines.append(hint(snapshot))
        return lines.joined(separator: "\n\n")
    }
}
#endif
