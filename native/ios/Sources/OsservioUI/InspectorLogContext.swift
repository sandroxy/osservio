import OsservioCore

/// A bounded reading window from retained evidence. It never observes subsequent capture.
struct InspectorLogContext {
    static let sideLimit = 20
    static let textByteLimit = 131_072
    let events: [LogEvent]
    let targetIndex: Int

    init?(source: [LogEvent], target: LogEvent) {
        let session = target.sessionID.lowercased()
        guard let anchor = source.firstIndex(where: { $0.sequence == target.sequence && $0.sessionID.lowercased() == session }) else { return nil }
        var selected: [LogEvent] = [], bytes = target.textByteCount
        var beforeBudget = (Self.textByteLimit - bytes) / 2
        for index in stride(from: anchor - 1, through: 0, by: -1) {
            guard selected.count < Self.sideLimit else { break }
            let event = source[index]
            guard event.sessionID.lowercased() == session else { continue }
            guard event.textByteCount <= beforeBudget else { break }
            selected.append(event); beforeBudget -= event.textByteCount; bytes += event.textByteCount
        }
        selected.reverse(); targetIndex = selected.count; selected.append(target)
        var after = 0
        for event in source.dropFirst(anchor + 1) {
            guard after < Self.sideLimit else { break }
            guard event.sessionID.lowercased() == session else { continue }
            guard event.textByteCount <= Self.textByteLimit - bytes else { break }
            selected.append(event); bytes += event.textByteCount; after += 1
        }
        events = selected
    }
}
