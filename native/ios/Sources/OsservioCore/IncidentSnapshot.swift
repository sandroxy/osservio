import Foundation

/// A deliberate local problem snapshot. Its existence does not establish a crash or its cause.
public struct IncidentSnapshot: Sendable {
    public let id: String
    public let timestampMillis: Int64
    public let note: IncidentNote?
    /// The supplied snapshot retains its own observation time; it is not an atomic reading at the mark time.
    public let environment: EnvironmentSnapshot?
    /// Nil means no breadcrumb snapshot was supplied, not that the application had no activity.
    public let breadcrumbs: IncidentBreadcrumbs?
    /// Sanitized text bytes only; excludes structural, indexing, and object overhead.
    public var textByteCount: Int {
        let environmentBytes = environment?.values.values.reduce(0) { total, value in
            if case .text(let text) = value.fact { return total + text.utf8.count }
            return total
        } ?? 0
        return (note?.text.utf8.count ?? 0) + environmentBytes + (breadcrumbs?.retainedTextBytes ?? 0)
    }

    public static func capture(note: LogMessage? = nil, environment: EnvironmentSnapshot? = nil,
                               breadcrumbs: BreadcrumbSnapshot? = nil,
                               breadcrumbLimit: Int = 200, breadcrumbTextByteLimit: Int = 65_536,
                               clock: any LogClock = SystemLogClock()) throws -> IncidentSnapshot {
        guard (1...2_000).contains(breadcrumbLimit), (64...1_048_576).contains(breadcrumbTextByteLimit) else {
            throw CaptureConfigurationError.invalidLimit
        }
        let safeNote = try note.map { try IncidentNote.capture($0) }
        let context = try breadcrumbs.map { try IncidentBreadcrumbs.capture($0, count: breadcrumbLimit, bytes: breadcrumbTextByteLimit) }
        return IncidentSnapshot(id: UUID().uuidString, timestampMillis: clock.wallTimeMillis(), note: safeNote,
                                environment: environment, breadcrumbs: context)
    }
    /// Reconstructs validated sanitized evidence without generating a new identity or observation time.
    public static func restore(id: String, timestampMillis: Int64, note: IncidentNote?,
                               environment: EnvironmentSnapshot?, breadcrumbs: IncidentBreadcrumbs?) throws -> IncidentSnapshot {
        guard Array(id.utf8.prefix(37)).count == 36, UUID(uuidString: id) != nil else { throw PersistenceError.invalidRecord }
        return IncidentSnapshot(id: id, timestampMillis: timestampMillis, note: note, environment: environment, breadcrumbs: breadcrumbs)
    }
}

public struct IncidentNote: Sendable {
    public let text: String
    public let isPrivate: Bool
    public let isTruncated: Bool
    /// Classifies a note independently of the mark time, retaining at most 4,096 UTF-8 bytes.
    public static func capture(_ message: LogMessage) throws -> IncidentNote {
        guard let safe = BoundedText.prefix(message.text, bytes: 4_096) else { throw PersistenceError.invalidRecord }
        return IncidentNote(text: safe.text, isPrivate: message.isPrivate, isTruncated: safe.truncated)
    }
    public static func restore(text: String, isPrivate: Bool, isTruncated: Bool) throws -> IncidentNote {
        guard let safe = BoundedText.prefix(text, bytes: 4_096), !safe.truncated,
              !isPrivate || (text == "[private]" && !isTruncated) else { throw PersistenceError.invalidRecord }
        return IncidentNote(text: text, isPrivate: isPrivate, isTruncated: isTruncated)
    }
}

public struct IncidentBreadcrumbs: Sendable {
    public let events: [BreadcrumbEvent]
    public let sourceEventCount: Int
    /// Evictions reported by this live history, not a count of all possible capture losses.
    public let sourceEvictedCount: Int64
    public let retainedTextBytes: Int
    public var omittedEventCount: Int { sourceEventCount - events.count }

    fileprivate static func capture(_ source: BreadcrumbSnapshot, count: Int, bytes: Int) throws -> IncidentBreadcrumbs {
        guard source.evictedCount >= 0 else { throw PersistenceError.invalidRecord }
        var chosen: [BreadcrumbEvent] = []
        var retained = 0
        for event in source.events.reversed() {
            guard chosen.count < count, event.textByteCount <= bytes - retained else { break }
            chosen.append(event); retained += event.textByteCount
        }
        return try restore(events: chosen.reversed(), sourceEventCount: source.events.count, sourceEvictedCount: source.evictedCount)
    }
    public static func restore(events: [BreadcrumbEvent], sourceEventCount: Int, sourceEvictedCount: Int64) throws -> IncidentBreadcrumbs {
        guard events.count <= 2_000, sourceEventCount >= events.count, sourceEventCount <= Int32.max,
              sourceEvictedCount >= 0 else { throw PersistenceError.invalidRecord }
        var identifiers = Set<String>()
        var bytes = 0
        for event in events {
            guard identifiers.insert(event.id.lowercased()).inserted, event.textByteCount <= 1_048_576 - bytes else { throw PersistenceError.invalidRecord }
            bytes += event.textByteCount
        }
        return IncidentBreadcrumbs(events: events, sourceEventCount: sourceEventCount, sourceEvictedCount: sourceEvictedCount, retainedTextBytes: bytes)
    }
}
