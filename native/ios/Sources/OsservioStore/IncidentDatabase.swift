import Foundation
import OsservioCore

/// Serial-worker-owned storage for explicit snapshots, independently retained from live histories.
final class IncidentDatabase {
    private let connection: SQLiteConnection
    private let options: IncidentPersistenceOptions
    private let details: IncidentDetailDatabase
    private static let applicationID: Int64 = 0x4f535649
    private static let revision: Int64 = 2

    init(url: URL, options: IncidentPersistenceOptions, now: Int64) throws {
        self.options = options
        connection = try EvidenceSQLite.open(url: url, byteLimit: options.databaseByteLimit,
            applicationID: Self.applicationID, revision: Self.revision, schema: Self.schema + IncidentDetailDatabase.schema,
            migrations: [1: IncidentDetailDatabase.schema])
        details = IncidentDetailDatabase(connection: connection)
        do {
            try connection.transaction {
                guard try scalar("SELECT count(*) FROM incidents") <= 16 else { throw PersistenceError.invalidRecord }
                try prune(now: now, reserve: 0)
            }
        } catch { connection.close(); throw error }
    }
    func close() { connection.close() }
    private func scalar(_ sql: String) throws -> Int64 {
        try connection.statement(sql) { row in
            guard try row.step() else { throw PersistenceError.corrupt }
            return try row.integer(0)
        }
    }
    private static let schema = [
        """
        CREATE TABLE incidents (ordinal INTEGER PRIMARY KEY, id TEXT NOT NULL UNIQUE COLLATE NOCASE,
            timestamp INTEGER NOT NULL, saved_at INTEGER NOT NULL, note TEXT,
            private INTEGER NOT NULL, truncated INTEGER NOT NULL, environment_platform TEXT, environment_time INTEGER,
            breadcrumb_count INTEGER, source_count INTEGER, source_evicted INTEGER, text_bytes INTEGER NOT NULL)
        """,
        """
        CREATE TABLE environment_facts (incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
            key TEXT NOT NULL, type TEXT NOT NULL, text_value TEXT, number_value INTEGER,
            truncated INTEGER NOT NULL, PRIMARY KEY(incident_id, key))
        """,
        """
        CREATE TABLE incident_breadcrumbs (incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
            position INTEGER NOT NULL, id TEXT NOT NULL COLLATE NOCASE, session_id TEXT NOT NULL,
            sequence INTEGER NOT NULL, timestamp INTEGER NOT NULL, elapsed TEXT, kind TEXT NOT NULL,
            label TEXT NOT NULL, message TEXT, private INTEGER NOT NULL, truncated INTEGER NOT NULL,
            value INTEGER, retention TEXT NOT NULL, PRIMARY KEY(incident_id, position), UNIQUE(incident_id, id))
        """
    ]
    func save(_ snapshot: IncidentSnapshot, now: Int64) throws {
        guard snapshot.textByteCount <= options.textBytesPerIncident else { throw PersistenceError.invalidRecord }
        try connection.transaction {
            // Check identity before eviction, so saving the same snapshot cannot replace its original evidence.
            let duplicate = try connection.statement("SELECT 1 FROM incidents WHERE id=?", [.text(snapshot.id)]) { try $0.step() }
            guard !duplicate else { throw PersistenceError.invalidRecord }
            try prune(now: now, reserve: 1)
            try connection.execute("""
                INSERT INTO incidents(id, timestamp, saved_at, note, private, truncated, environment_platform,
                    environment_time, breadcrumb_count, source_count, source_evicted, text_bytes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, [.text(snapshot.id), .integer(snapshot.timestampMillis), .integer(now), snapshot.note.map { .text($0.text) } ?? .null,
                         .integer(snapshot.note?.isPrivate == true ? 1 : 0), .integer(snapshot.note?.isTruncated == true ? 1 : 0),
                         snapshot.environment.map { .text($0.platform.rawValue) } ?? .null,
                         snapshot.environment.map { .integer($0.timestampMillis) } ?? .null,
                         snapshot.breadcrumbs.map { .integer(Int64($0.events.count)) } ?? .null,
                         snapshot.breadcrumbs.map { .integer(Int64($0.sourceEventCount)) } ?? .null,
                         snapshot.breadcrumbs.map { .integer($0.sourceEvictedCount) } ?? .null, .integer(Int64(snapshot.textByteCount))])
            if let environment = snapshot.environment {
                for key in EnvironmentKey.allCases {
                    guard let value = environment.values[key] else { throw PersistenceError.invalidRecord }
                    let type: String, text: SQLiteValue, number: SQLiteValue
                    switch value.fact {
                    case .text(let value): type = "text"; text = .text(value); number = .null
                    case .number(let value): type = "number"; text = .null; number = .integer(value)
                    case .flag(let value): type = "flag"; text = .null; number = .integer(value ? 1 : 0)
                    case .unavailable: type = "unavailable"; text = .null; number = .null
                    case .unsupported: type = "unsupported"; text = .null; number = .null
                    }
                    try connection.execute("INSERT INTO environment_facts(incident_id, key, type, text_value, number_value, truncated) VALUES (?, ?, ?, ?, ?, ?)",
                        [.text(snapshot.id), .text(key.rawValue), .text(type), text, number, .integer(value.isTruncated ? 1 : 0)])
                }
            }
            for (position, event) in (snapshot.breadcrumbs?.events ?? []).enumerated() {
                try connection.execute("""
                    INSERT INTO incident_breadcrumbs(incident_id, position, id, session_id, sequence, timestamp,
                        elapsed, kind, label, message, private, truncated, value, retention)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, [.text(snapshot.id), .integer(Int64(position)), .text(event.id), .text(event.sessionID), .integer(event.sequence),
                             .integer(event.timestampMillis), event.sessionElapsedMillis.map { .text(String($0)) } ?? .null,
                             .text(event.kind.rawValue), .text(event.label), event.message.map(SQLiteValue.text) ?? .null,
                             .integer(event.isPrivate ? 1 : 0), .integer(event.isTruncated ? 1 : 0),
                             event.value.map(SQLiteValue.integer) ?? .null, .text(event.retention.rawValue)])
            }
            try details.save(snapshot)
        }
    }
    private func prune(now: Int64, reserve: Int) throws {
        if now >= options.maximumAgeMillis {
            try connection.execute("DELETE FROM incidents WHERE saved_at<?", [.integer(now - options.maximumAgeMillis)])
        }
        try connection.execute("DELETE FROM incidents WHERE id IN (SELECT id FROM incidents ORDER BY ordinal DESC LIMIT -1 OFFSET ?)",
                               [.integer(Int64(options.retainedIncidents - reserve))])
    }
    func summaries() throws -> [IncidentSummary] {
        try connection.statement(Self.headerQuery + " ORDER BY ordinal DESC LIMIT 17") { row in
            var summaries: [IncidentSummary] = []
            while try row.step() {
                guard summaries.count < options.retainedIncidents else { throw PersistenceError.invalidRecord }
                summaries.append(try header(row).summary)
            }
            return summaries
        }
    }
    func read(id: String) throws -> IncidentSnapshot? {
        guard Self.validID(id) else { throw PersistenceError.invalidRecord }
        let info = try connection.statement(Self.headerQuery + " WHERE id=?", [.text(id)]) { row -> Header? in
            try row.step() ? header(row) : nil
        }
        guard let info else { return nil }
        let environment = try readEnvironment(id: info.summary.id, info: info)
        let breadcrumbs = try readBreadcrumbs(id: info.summary.id, info: info)
        let detail = try details.read(id: info.summary.id, windows: info.windows)
        let restored = try IncidentSnapshot.restore(id: info.summary.id, timestampMillis: info.summary.timestampMillis,
            note: info.summary.note, environment: environment, breadcrumbs: breadcrumbs, logs: detail.logs, http: detail.http, webSockets: detail.webSockets)
        guard restored.textByteCount == info.bytes else { throw PersistenceError.invalidRecord }
        return restored
    }
    func delete(id: String) throws {
        guard Self.validID(id) else { throw PersistenceError.invalidRecord }
        try connection.transaction { try connection.execute("DELETE FROM incidents WHERE id=?", [.text(id)]) }
    }
    func deleteAll() throws { try connection.transaction { try connection.execute("DELETE FROM incidents") } }
    static func validID(_ id: String) -> Bool { Array(id.utf8.prefix(37)).count == 36 && UUID(uuidString: id) != nil }

    private static let headerQuery = """
        SELECT id, timestamp, note, private, truncated, environment_platform, environment_time,
            breadcrumb_count, source_count, source_evicted, text_bytes FROM incidents
        """
    private struct Header {
        let summary: IncidentSummary
        let platform: EnvironmentPlatform?
        let environmentTime: Int64?
        let sourceCount: Int?
        let sourceEvicted: Int64?
        let bytes: Int
        let windows: [IncidentDetailDatabase.Kind: IncidentDetailDatabase.Window]
    }
    private func header(_ row: SQLiteStatement) throws -> Header {
        let id = try row.text(0, byteLimit: 36), isPrivate = try row.flag(3), truncated = try row.flag(4)
        guard Self.validID(id), !row.isNull(2) || (!isPrivate && !truncated) else { throw PersistenceError.invalidRecord }
        let note = try row.isNull(2) ? nil : IncidentNote.restore(text: row.text(2, byteLimit: 4_096), isPrivate: isPrivate, isTruncated: truncated)
        let platform: EnvironmentPlatform?
        if row.isNull(5) { platform = nil; guard row.isNull(6) else { throw PersistenceError.invalidRecord } }
        else {
            guard let parsed = EnvironmentPlatform(rawValue: try row.text(5, byteLimit: 16)), !row.isNull(6) else { throw PersistenceError.invalidRecord }
            platform = parsed
        }
        let count = try row.isNull(7) ? nil : row.integer(7)
        let source = try row.isNull(8) ? nil : row.integer(8)
        let evicted = try row.isNull(9) ? nil : row.integer(9)
        if let count {
            guard (0...2_000).contains(count), let source, source >= count, source <= Int32.max,
                  let evicted, evicted >= 0 else { throw PersistenceError.invalidRecord }
        } else { guard source == nil, evicted == nil else { throw PersistenceError.invalidRecord } }
        let bytes = try row.integer(10)
        guard (0...2_097_152).contains(bytes) else { throw PersistenceError.invalidRecord }
        let windows = try details.windows(id: id)
        guard windows.values.reduce(0, { $0 + $1.bytes + $1.payloadBytes }) <= bytes else { throw PersistenceError.invalidRecord }
        return try Header(summary: IncidentSummary(id: id, timestampMillis: row.integer(1), note: note,
            hasEnvironment: platform != nil, breadcrumbCount: count.map(Int.init), logCount: windows[.logs]?.count,
            httpCount: windows[.http]?.count, webSocketCount: windows[.websocket]?.count), platform: platform,
            environmentTime: platform == nil ? nil : row.integer(6), sourceCount: source.map(Int.init), sourceEvicted: evicted, bytes: Int(bytes), windows: windows)
    }
    private func readEnvironment(id: String, info: Header) throws -> EnvironmentSnapshot? {
        var facts: [EnvironmentKey: EnvironmentFact] = [:], truncated = Set<EnvironmentKey>()
        try connection.statement("SELECT key, type, text_value, number_value, truncated FROM environment_facts WHERE incident_id=? LIMIT 18", [.text(id)]) { row in
            while try row.step() {
                guard info.platform != nil, facts.count < EnvironmentKey.allCases.count,
                      let key = EnvironmentKey(rawValue: try row.text(0, byteLimit: 32)), facts[key] == nil else { throw PersistenceError.invalidRecord }
                let type = try row.text(1, byteLimit: 16)
                let fact: EnvironmentFact
                switch type {
                case "text": guard row.isNull(3) else { throw PersistenceError.invalidRecord }; fact = .text(try row.text(2, byteLimit: 256))
                case "number": guard row.isNull(2) else { throw PersistenceError.invalidRecord }; fact = .number(try row.integer(3))
                case "flag": guard row.isNull(2) else { throw PersistenceError.invalidRecord }; fact = .flag(try row.flag(3))
                case "unavailable", "unsupported":
                    guard row.isNull(2), row.isNull(3) else { throw PersistenceError.invalidRecord }
                    fact = type == "unavailable" ? .unavailable : .unsupported
                default: throw PersistenceError.invalidRecord
                }
                facts[key] = fact
                if try row.flag(4) { truncated.insert(key) }
            }
        }
        guard let platform = info.platform, let time = info.environmentTime else { return nil }
        do { return try EnvironmentSnapshot.restore(platform: platform, publicFacts: facts, truncatedKeys: truncated, timestampMillis: time) }
        catch { throw PersistenceError.invalidRecord }
    }
    private func readBreadcrumbs(id: String, info: Header) throws -> IncidentBreadcrumbs? {
        var events: [BreadcrumbEvent] = []
        var bytes = 0
        try connection.statement("""
            SELECT position, id, session_id, sequence, timestamp, elapsed, kind, label, message,
                private, truncated, value, retention FROM incident_breadcrumbs WHERE incident_id=? ORDER BY position LIMIT 2001
            """, [.text(id)]) { row in
            while try row.step() {
                guard let count = info.summary.breadcrumbCount, events.count < count,
                      try row.integer(0) == events.count else { throw PersistenceError.invalidRecord }
                let elapsedText = try row.isNull(5) ? nil : row.text(5, byteLimit: 20)
                let elapsed = elapsedText.flatMap(UInt64.init)
                if let elapsedText { guard let elapsed, String(elapsed) == elapsedText else { throw PersistenceError.invalidRecord } }
                guard let kind = BreadcrumbKind(rawValue: try row.text(6, byteLimit: 32)),
                      let retention = BreadcrumbRetention(rawValue: try row.text(12, byteLimit: 32)) else { throw PersistenceError.invalidRecord }
                let event = try BreadcrumbEvent.restore(id: row.text(1, byteLimit: 36), sessionID: row.text(2, byteLimit: 36),
                    sequence: row.integer(3), timestampMillis: row.integer(4), sessionElapsedMillis: elapsed, kind: kind,
                    label: row.text(7, byteLimit: 64), message: row.isNull(8) ? nil : row.text(8, byteLimit: 4_096),
                    isPrivate: row.flag(9), isTruncated: row.flag(10), value: row.isNull(11) ? nil : row.integer(11), retention: retention)
                guard event.textByteCount <= 1_048_576 - bytes else { throw PersistenceError.invalidRecord }
                bytes += event.textByteCount; events.append(event)
            }
        }
        guard let count = info.summary.breadcrumbCount, let source = info.sourceCount, let evicted = info.sourceEvicted else { return nil }
        guard events.count == count else { throw PersistenceError.invalidRecord }
        return try IncidentBreadcrumbs.restore(events: events, sourceEventCount: source, sourceEvictedCount: evicted)
    }
}
