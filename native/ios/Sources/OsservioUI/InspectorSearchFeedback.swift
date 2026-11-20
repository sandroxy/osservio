import Foundation

enum InspectorSearchFeedback {
    static func activity(_ result: InspectorRecordSearch.Result?, running: Bool, paused: Bool = false) -> String? {
        if let result, !result.complete, running || paused {
            return String(format: InspectorLocalization.text(paused && !running ? "ui_search_paused" : "ui_search_progress"), result.scanned, result.total)
        }
        return running ? InspectorLocalization.text("ui_searching") : nil
    }
    static func filteredEmpty(_ result: InspectorRecordSearch.Result?) -> String {
        if let result, !result.complete || result.hasIncompleteContent { return "ui_filtered_empty_partial" }
        return "ui_filtered_empty"
    }
    static func text(_ result: InspectorRecordSearch.Result?) -> String? {
        guard let result, result.hasIncompleteContent else { return nil }
        return InspectorLocalization.text("ui_search_partial")
    }
}
