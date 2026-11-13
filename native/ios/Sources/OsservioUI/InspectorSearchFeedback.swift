import Foundation

enum InspectorSearchFeedback {
    static func activity(_ result: InspectorRecordSearch.Result?, running: Bool, paused: Bool = false) -> String? {
        if let result, !result.complete, running || paused {
            return String(format: InspectorLocalization.text(paused && !running ? "ui_search_paused" : "ui_search_progress"), result.scanned, result.total)
        }
        return running ? InspectorLocalization.text("ui_searching") : nil
    }
    static func text(_ result: InspectorRecordSearch.Result?) -> String? {
        guard let result, result.limited || result.unavailable > 0 || result.truncated > 0 else { return nil }
        return InspectorLocalization.text("ui_search_partial")
    }
}
