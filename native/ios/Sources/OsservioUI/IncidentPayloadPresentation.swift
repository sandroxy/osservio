import Foundation
import OsservioCore

/// Saved inclusion states describe the frozen copy, independently of the original transport observation.
enum IncidentPayloadPresentation {
    static func content(_ payload: IncidentPayload?) -> String {
        guard let payload else { return HTTPPresentation.text("incident_payload_missing") }
        switch payload.state {
        case .available: return payload.text?.isEmpty == true ? HTTPPresentation.text("http_body_empty") : payload.text ?? HTTPPresentation.text("incident_payload_missing")
        case .notSelected: return HTTPPresentation.text("incident_payload_not_selected")
        case .unavailable: return HTTPPresentation.text("incident_payload_unavailable")
        case .busy: return HTTPPresentation.text("incident_payload_busy")
        case .budgetExceeded: return HTTPPresentation.text("incident_payload_budget")
        }
    }
}
