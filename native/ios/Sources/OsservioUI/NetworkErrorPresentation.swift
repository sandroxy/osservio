import Foundation
import OsservioCore

/// Detail and explicit search expose diagnostic text without enlarging normal list rows.
enum NetworkErrorPresentation {
    static func notice(limited: Bool, processed: Bool) -> String {
        [limited ? InspectorLocalization.text("network_details_limited") : "",
         processed ? InspectorLocalization.text("network_details_processed") : ""].filter { !$0.isEmpty }.joined(separator: "\n")
    }
    static func fields(_ error: NetworkError) -> [(String, String)] {
        [("network_error_message", error.message), ("network_error_code", error.code),
         ("network_error_type", error.type), ("network_error_domain", error.domain)].compactMap { key, value in value.map { (key, $0) } }
    }
    static func text(_ error: NetworkError?) -> String {
        guard let error else { return "" }
        return (fields(error).map { InspectorLocalization.text($0.0) + ": " + $0.1 }
            + [notice(limited: error.isLimited, processed: error.isProcessed)]).filter { !$0.isEmpty }.joined(separator: "\n")
    }
    static func text(_ reason: WebSocketCloseReason?) -> String {
        guard let reason else { return "" }
        return InspectorLocalization.text("network_close_reason") + ": " + (reason.text ?? InspectorLocalization.text("network_text_unavailable"))
            + "\n" + notice(limited: reason.isLimited, processed: reason.isProcessed)
    }
}

#if canImport(UIKit)
extension NetworkErrorPresentation {
    @MainActor
    static func sections(error: NetworkError?, reason: WebSocketCloseReason?, open: @escaping (String, String, String) -> Void) -> [InspectorDetailViewController.Section] {
        typealias Section = InspectorDetailViewController.Section
        typealias Row = InspectorDetailViewController.Row
        var sections: [Section] = []
        if let error {
            let note = notice(limited: error.isLimited, processed: error.isProcessed)
            let rows = fields(error).map { key, value -> Row in
                if key == "network_error_message" {
                    return Row(InspectorLocalization.text(key), value.isEmpty ? InspectorLocalization.text("ui_content_empty") : InspectorRecordPresentation.compact(value, limit: 600),
                        action: { open(key, value, note) })
                }
                return Row(InspectorLocalization.text(key), value)
            }
            sections.append(Section(title: InspectorLocalization.text("network_error_details"), rows: rows.isEmpty ? [Row(InspectorLocalization.text("network_text_unavailable"))] : rows, footer: note))
        }
        if let reason {
            let note = notice(limited: reason.isLimited, processed: reason.isProcessed)
            let row: Row
            if let text = reason.text {
                row = Row(text.isEmpty ? InspectorLocalization.text("ui_content_empty") : InspectorRecordPresentation.compact(text, limit: 600),
                    action: { open("network_close_reason", text, note) })
            } else { row = Row(InspectorLocalization.text("network_text_unavailable")) }
            sections.append(Section(title: InspectorLocalization.text("network_close_reason"), rows: [row], footer: note))
        }
        return sections
    }
}
#endif
