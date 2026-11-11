#if canImport(UIKit)
import OsservioCore
import UIKit

enum RuntimePresentation {
    static func text(_ key: String) -> String { InspectorLocalization.text(key) }
    static func kind(_ event: BreadcrumbEvent) -> String { text("crumb_kind_" + event.kind.rawValue) }
    static func message(_ value: String, isPrivate: Bool) -> String {
        isPrivate ? text("ui_private_content") : value
    }
    static func date(_ millis: Int64) -> String {
        InspectorLocalization.date(millis)
    }
    static func value(_ value: EnvironmentValue, key: EnvironmentKey, compact: Bool = false) -> String {
        let result: String
        switch value.fact {
        case .text(let string):
            let known = ["nominal", "fair", "serious", "critical", "none", "light", "moderate", "severe", "emergency", "shutdown"]
            result = compact && key == .thermalState && known.contains(string) ? text("ui_thermal_" + string) + " (" + string + ")" : string
        case .number(let number):
            if key.rawValue.hasSuffix("Bytes") {
                result = InspectorValuePresentation.bytes(number) + (compact ? "" : " (\(number) B)")
            } else { result = String(number) + (key == .batteryPercent ? "%" : "") }
        case .flag(let flag): result = text(flag ? "runtime_yes" : "runtime_no")
        case .unavailable: result = text("runtime_unavailable")
        case .unsupported: result = text("runtime_unsupported")
        }
        return result + (value.isTruncated ? " · " + text("truncated") : "")
    }
    static func environment(_ snapshot: EnvironmentSnapshot) -> String {
        ([text("runtime_environment") + " · " + snapshot.platform.rawValue, date(snapshot.timestampMillis)]
         + EnvironmentKey.allCases.map { text("env_" + $0.rawValue) + ": " + value(snapshot.values[$0]!, key: $0) }).joined(separator: "\n")
    }
    static func breadcrumbTitle(_ event: BreadcrumbEvent) -> String {
        event.kind == .hostMarker ? event.label : kind(event)
    }
    static func breadcrumbSubtitle(_ event: BreadcrumbEvent) -> String {
        var parts: [String] = []
        if let message = event.message { parts.append(message.isEmpty ? text("ui_content_empty") : self.message(InspectorRecordPresentation.compact(message), isPrivate: event.isPrivate)) }
        if event.kind.rawValue.hasPrefix("activity") { parts.append(event.label) }
        if let elapsed = event.sessionElapsedMillis { parts.append(String(format: text("ui_since_start"), elapsed)) }
        return parts.joined(separator: " · ")
    }
    static func breadcrumb(_ event: BreadcrumbEvent) -> String {
        var lines = [kind(event), date(event.timestampMillis), "ID: \(event.id)",
            text("crumb_session") + ": \(event.sessionID)", text("crumb_sequence") + ": \(event.sequence)",
            text("crumb_elapsed") + ": " + (event.sessionElapsedMillis.map { "\($0) ms" } ?? text("runtime_unavailable")),
            text("crumb_label") + ": " + event.label,
            text("crumb_retention") + ": " + text(event.retention == .restartRelevant ? "crumb_restart_relevant" : "crumb_memory_only")]
        if let value = event.value { lines.append(text("crumb_value") + ": \(value)") }
        if let message = event.message { lines.append("\n" + message) }
        if event.isPrivate { lines.append(text("crumb_private")) }
        if event.isTruncated { lines.append(text("truncated")) }
        return lines.joined(separator: "\n")
    }
}
#endif
