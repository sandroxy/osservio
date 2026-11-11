import Foundation
import OsservioCore

/// The sanitized payload is separate from explanatory UI text and its completeness.
struct InspectorReadContent {
    let text: String
    var notice: String
    let allowsJSON: Bool
    let isAvailable: Bool
    var context: () -> String
    init(text: String? = nil, notice: String = "", allowsJSON: Bool = false, context: @escaping () -> String = { "" }) {
        self.text = text ?? ""; self.notice = notice; self.allowsJSON = allowsJSON; isAvailable = text != nil; self.context = context
    }
    static func saved(_ payload: IncidentPayload?, notice: String, complete: Bool) -> Self {
        guard let payload, payload.state == .available, let text = payload.text else {
            return Self(notice: IncidentPayloadPresentation.content(payload))
        }
        return Self(text: text, notice: notice + (text.isEmpty ? "\n" + HTTPPresentation.text("http_body_empty") : ""), allowsJSON: complete)
    }
}
