import Foundation

enum InspectorSearchFeedback {
    static func text(_ result: InspectorRecordSearch.Result?) -> String? {
        guard let result, result.limited || result.unavailable > 0 || result.truncated > 0 else { return nil }
        return InspectorLocalization.text("ui_search_partial")
    }
}
