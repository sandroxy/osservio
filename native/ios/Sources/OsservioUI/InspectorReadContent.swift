import Foundation
import OsservioCore

/// The sanitized payload is separate from explanatory UI text and its completeness.
struct InspectorReadContent {
    let text: String
    private let noticeValue: () -> String
    var notice: String { noticeValue() }
    let allowsJSON: Bool
    let isAvailable: Bool
    var isForm = false
    var context: () -> String
    init(text: String? = nil, notice: @escaping () -> String = { "" }, allowsJSON: Bool = false, context: @escaping () -> String = { "" }) {
        self.text = text ?? ""; noticeValue = notice; self.allowsJSON = allowsJSON; isAvailable = text != nil; self.context = context
    }
    func withNoticePrefix(_ prefix: @escaping () -> String) -> Self {
        var copy = Self(text: isAvailable ? text : nil, notice: {
            [prefix(), notice].filter { !$0.isEmpty }.joined(separator: "\n")
        }, allowsJSON: allowsJSON, context: context)
        copy.isForm = isForm; return copy
    }
    static func saved(_ payload: IncidentPayload?, notice: @escaping () -> String, complete: Bool) -> Self {
        guard let payload, payload.state == .available, let text = payload.text else {
            return Self(notice: { IncidentPayloadPresentation.content(payload) })
        }
        return Self(text: text, notice: { notice() + (text.isEmpty ? "\n" + HTTPPresentation.text("http_body_empty") : "") }, allowsJSON: complete)
    }
}
