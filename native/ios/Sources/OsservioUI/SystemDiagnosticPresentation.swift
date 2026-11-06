#if canImport(UIKit)
import OsservioCore
import UIKit

enum SystemDiagnosticPresentation {
    static func kind(_ kind: SystemDiagnosticKind) -> String { RuntimePresentation.text("diagnostic_kind_" + kind.rawValue) }
    static func hint(_ diagnostic: SystemDiagnostic) -> String {
        RuntimePresentation.text(diagnostic.source == .androidExitInfo ? "diagnostic_android_hint" : "diagnostic_metric_hint")
    }
    static func fields(_ diagnostic: SystemDiagnostic) -> [(title: String, value: String)] {
        func field(_ key: String, _ value: String?) -> (String, String) {
            (RuntimePresentation.text(key), value ?? RuntimePresentation.text("runtime_unavailable"))
        }
        var result = [field("diagnostic_source", RuntimePresentation.text("diagnostic_source_" + diagnostic.source.rawValue)),
                      field("diagnostic_kind", kind(diagnostic.kind))]
        if let exit = diagnostic.androidExit {
            result += [field("diagnostic_exit_time", RuntimePresentation.date(diagnostic.intervalStartMillis)),
                       field("diagnostic_reason", String(exit.reasonCode)), field("diagnostic_status", String(exit.status)),
                       field("env_processID", String(exit.processID)), field("diagnostic_importance", String(exit.importance)),
                       field("diagnostic_pss", exit.pssKiB.map { "\($0) KiB" }), field("diagnostic_rss", exit.rssKiB.map { "\($0) KiB" })]
        }
        if let metric = diagnostic.metricKit {
            result += [field("diagnostic_interval_start", RuntimePresentation.date(diagnostic.intervalStartMillis)),
                       field("diagnostic_interval_end", RuntimePresentation.date(diagnostic.intervalEndMillis))]
            for key in SystemDiagnostic.MetadataKey.allCases {
                let value = metric.metadata[key].map { $0.value + ($0.isTruncated ? " · " + RuntimePresentation.text("truncated") : "") }
                result.append(field("env_" + key.rawValue, value))
            }
            if diagnostic.kind == .crash {
                result += [field("diagnostic_signal", metric.signal.map(String.init)),
                           field("diagnostic_exception_type", metric.exceptionType.map(String.init)),
                           field("diagnostic_exception_code", metric.exceptionCode)]
            } else {
                result.append(field("diagnostic_hang_duration", metric.hangDurationMillis.map { "\($0) ms" }))
            }
        }
        return result
    }
    static func summary(_ diagnostic: SystemDiagnostic) -> String {
        (fields(diagnostic).map { $0.title + ": " + $0.value } + [hint(diagnostic)]).joined(separator: "\n")
    }
}
#endif
