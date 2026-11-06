#if canImport(UIKit)
import OsservioCore
import UIKit

enum IncidentPresentation {
    static func note(_ note: IncidentNote?) -> String {
        guard let note else { return RuntimePresentation.text("incident_no_note") }
        return note.text + (note.isPrivate ? "\n" + RuntimePresentation.text("crumb_private") : "")
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
        return environment + " · " + breadcrumbs
    }
    static func summary(_ snapshot: IncidentSnapshot) -> String {
        var lines = [RuntimePresentation.text("incident_manual"),
            RuntimePresentation.text("incident_frozen_time") + ": " + RuntimePresentation.date(snapshot.timestampMillis),
            "ID: " + snapshot.id, note(snapshot.note),
            RuntimePresentation.text("runtime_breadcrumbs") + ": " + counts(snapshot.breadcrumbs)]
        if let environment = snapshot.environment { lines.append(RuntimePresentation.environment(environment)) }
        else { lines.append(RuntimePresentation.text("runtime_environment") + ": " + RuntimePresentation.text("incident_not_attached")) }
        lines.append(RuntimePresentation.text("incident_snapshot_hint"))
        return lines.joined(separator: "\n\n")
    }
}
#endif
