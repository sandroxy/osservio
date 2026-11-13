import os.signpost

/// Fixed, content-free intervals. No timer, retained samples or diagnostic payloads.
enum InspectorPerformance {
    enum Point {
        case logRefresh, networkRefresh, logApply, networkApply, recordSearch, jsonParse, formParse, open, collapse, tab
        var name: StaticString {
            switch self {
            case .logRefresh: return "Osservio.Log.refresh"
            case .networkRefresh: return "Osservio.Network.refresh"
            case .logApply: return "Osservio.Log.apply"
            case .networkApply: return "Osservio.Network.apply"
            case .recordSearch: return "Osservio.Record.search"
            case .jsonParse: return "Osservio.JSON.parse"
            case .formParse: return "Osservio.Form.parse"
            case .open: return "Osservio.Inspector.open"
            case .collapse: return "Osservio.Inspector.collapse"
            case .tab: return "Osservio.Inspector.tab"
            }
        }
    }
    private static let log = OSLog(subsystem: "com.sandrox.osservio", category: .pointsOfInterest)
    struct Span {
        let id: OSSignpostID
        let name: StaticString
        func end() { os_signpost(.end, log: InspectorPerformance.log, name: name, signpostID: id) }
    }
    static func begin(_ point: Point) -> Span? {
        guard log.signpostsEnabled else { return nil }
        let span = Span(id: OSSignpostID(log: log), name: point.name)
        os_signpost(.begin, log: log, name: span.name, signpostID: span.id)
        return span
    }
    static func mark(_ point: Point) { os_signpost(.event, log: log, name: point.name) }
}
