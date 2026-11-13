import OsservioCore

enum InspectorLogDetails {
    static func label(_ state: LogDetailsState) -> String {
        switch state {
        case .available: return "ui_log_context_open"
        case .omitted: return "ui_log_context_omitted"
        case .limited: return "ui_log_context_limited"
        case .invalid: return "ui_log_context_invalid"
        case .unsupported: return "ui_log_context_unsupported"
        }
    }
    static func content(_ event: LogEvent, history: (any LogDetailsHistory)?, saved: [String: IncidentPayload]?) -> InspectorReadContent {
        guard let reference = event.details, let id = reference.blobID else { return InspectorReadContent() }
        let context = { InspectorRecordPresentation.context(event) }
        if let saved {
            var result = InspectorReadContent.saved(saved[id], notice: { "" }, complete: true); result.context = context; return result
        }
        if let history {
            switch history.details(id: id) {
            case .available(let content): return InspectorReadContent(text: content.text, allowsJSON: true, context: context)
            case .busy: return InspectorReadContent(notice: { InspectorLocalization.text("incident_payload_busy") }, context: context)
            case .unavailable: break
            }
        }
        return InspectorReadContent(notice: { InspectorLocalization.text("incident_payload_unavailable") }, context: context)
    }
}
