#if canImport(UIKit)
import OsservioCore
import UIKit

enum RuntimePresentation {
    static func text(_ key: String) -> String { InspectorLocalization.text(key) }
    static func kind(_ event: BreadcrumbEvent) -> String { text("crumb_kind_" + event.kind.rawValue) }
    static func date(_ millis: Int64) -> String {
        InspectorLocalization.date(millis)
    }
    static func value(_ value: EnvironmentValue, key: EnvironmentKey) -> String {
        let result: String
        switch value.fact {
        case .text(let string): result = string
        case .number(let number):
            if key.rawValue.hasSuffix("Bytes") {
                result = ByteCountFormatter.string(fromByteCount: number, countStyle: .memory) + " (\(number) B)"
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
