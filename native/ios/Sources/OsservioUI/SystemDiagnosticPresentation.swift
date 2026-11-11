#if canImport(UIKit)
import OsservioCore
import UIKit

enum SystemDiagnosticPresentation {
    static func kind(_ kind: SystemDiagnosticKind) -> String { RuntimePresentation.text("diagnostic_kind_" + kind.rawValue) }
    static func hint(_ diagnostic: SystemDiagnostic) -> String {
        switch diagnostic.source {
        case .androidExitInfo: return RuntimePresentation.text("diagnostic_android_hint")
        case .metricKit: return RuntimePresentation.text("diagnostic_metric_hint")
        case .appleSignal: return RuntimePresentation.text("crash_hint")
        }
    }
    struct Field {
        let key: String
        let value: String
        var title: String { RuntimePresentation.text(key) }
        var primary: Bool { ["diagnostic_source", "diagnostic_kind", "diagnostic_exit_time", "diagnostic_reason", "diagnostic_signal", "diagnostic_interval_start", "diagnostic_interval_end", "diagnostic_hang_duration", "crash_time", "tombstone_state"].contains(key) }
    }
    static func exitReason(_ code: Int) -> String {
        guard (0...16).contains(code) else { return String(code) }
        return RuntimePresentation.text("ui_exit_reason_" + String(code)) + " (" + String(code) + ")"
    }
    static func fields(_ diagnostic: SystemDiagnostic) -> [Field] {
        func field(_ key: String, _ value: String?) -> Field {
            Field(key: key, value: value ?? RuntimePresentation.text("runtime_unavailable"))
        }
        var result = [field("diagnostic_source", RuntimePresentation.text("diagnostic_source_" + diagnostic.source.rawValue)),
                      field("diagnostic_kind", kind(diagnostic.kind))]
        if let exit = diagnostic.androidExit {
            result += [field("diagnostic_exit_time", RuntimePresentation.date(diagnostic.intervalStartMillis)),
                       field("diagnostic_reason", exitReason(Int(exit.reasonCode))), field("diagnostic_status", String(exit.status)),
                       field("env_processID", String(exit.processID)), field("diagnostic_importance", String(exit.importance)),
                       field("diagnostic_pss", exit.pssKiB.map { "\($0) KiB" }), field("diagnostic_rss", exit.rssKiB.map { "\($0) KiB" })]
            result.append(field("tombstone_state", TombstonePresentation.state(exit.tombstoneState)))
            if let trace = exit.tombstone {
                result += [field("env_architecture", TombstonePresentation.architecture(trace.architectureCode)),
                           field("diagnostic_signal", String(trace.signalNumber)), field("crash_code", String(trace.signalCode)),
                           field("tombstone_thread_id", String(trace.threadID)), field("crash_fault_address", trace.faultAddress.map(SignalCrashPresentation.address)),
                           field("crash_frame_count", SignalCrashPresentation.count(trace.frames.count, limited: trace.frameLimitReached))]
            }
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
        if let signal = diagnostic.appleSignal {
            result += [field("crash_time", RuntimePresentation.date(signal.timestampMillis)), field("crash_report_id", signal.reportID),
                       field("diagnostic_signal", signal.signal.rawValue), field("crash_code", signal.code),
                       field("crash_fault_address", SignalCrashPresentation.address(signal.faultAddress)),
                       field("crash_thread", signal.threadNumber.map(String.init)),
                       field("crash_frame_count", SignalCrashPresentation.count(signal.frames.count, limited: signal.frameLimitReached)),
                       field("crash_image_count", SignalCrashPresentation.count(signal.images.count, limited: signal.imageLimitReached)),
                       field("crash_image_cache", RuntimePresentation.text(signal.imageCacheIncomplete ? "crash_image_cache_excluded" : "crash_image_cache_no_exclusion"))]
            for key in SystemDiagnostic.MetadataKey.allCases { result.append(field("env_" + key.rawValue, signal.metadata[key]?.value)) }
        }
        return result
    }
    static func summary(_ diagnostic: SystemDiagnostic) -> String {
        (fields(diagnostic).map { $0.title + ": " + $0.value } + [hint(diagnostic)]).joined(separator: "\n")
    }
}
#endif
