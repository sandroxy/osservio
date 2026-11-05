import Foundation
import OsservioCore

/// SQLite owns transaction recovery. This class is confined to a serial background worker.
final class BreadcrumbDatabase {
    private let connection: SQLiteConnection
    private let options: BreadcrumbPersistenceOptions
    private let runID: String
    private let platform: EnvironmentPlatform
    private let processID: Int64
    private let startedAt: Int64
    private static let applicationID: Int64 = 0x4f53564f
    private static let revision: Int64 = 1

    init(url: URL, options: BreadcrumbPersistenceOptions, runID: String, platform: EnvironmentPlatform,
         processID: Int64, startedAtMillis: Int64) throws {
        self.options = options; self.runID = runID; self.platform = platform
        self.processID = processID; startedAt = startedAtMillis
        // Enforce recovery input limits before the engine can read a journal.
        let recoveryFiles = [(url, Int64(options.databaseByteLimit)),
                             (URL(fileURLWithPath: url.path + "-journal"), 2 * Int64(options.databaseByteLimit) + 65_536)]
        for (file, limit) in recoveryFiles where FileManager.default.fileExists(atPath: file.path) {
            let attributes = try FileManager.default.attributesOfItem(atPath: file.path)
            guard attributes[.type] as? FileAttributeType == .typeRegular,
                  let bytes = attributes[.size] as? NSNumber, bytes.int64Value <= limit else {
                throw PersistenceError.storageFull
            }
        }
        connection = try SQLiteConnection(url: url)
        do {
            try configure()
            try connection.transaction {
                let count = try scalar("SELECT count(*) FROM recording_runs")
                guard count <= 16 else { throw PersistenceError.invalidRecord }
                try pruneRuns(now: startedAtMillis)
                let retained = try connection.statement("SELECT id FROM recording_runs") { row in
                    var ids: [String] = []
                    while try row.step() { ids.append(try row.text(0, byteLimit: 36)) }
                    return ids
                }
                for id in retained { try trimAndAccount(run: id) }
            }
        }
        catch { connection.close(); throw error }
    }
    func close() { connection.close() }
    func deleteRun(id: String) throws {
        guard Array(id.utf8.prefix(37)).count == 36, UUID(uuidString: id) != nil else { throw PersistenceError.invalidRecord }
        try connection.transaction { try connection.execute("DELETE FROM recording_runs WHERE id=?", [.text(id)]) }
    }
    func deleteAllRuns() throws {
        try connection.transaction { try connection.execute("DELETE FROM recording_runs") }
    }
    private func scalar(_ sql: String) throws -> Int64 {
        try connection.statement(sql) { row in
            guard try row.step() else { throw PersistenceError.corrupt }
            return try row.integer(0)
        }
    }
    private func configure() throws {
        let owner = try scalar("PRAGMA application_id")
        let version = try scalar("PRAGMA user_version")
        let empty = try scalar("SELECT count(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'") == 0
        if !(owner == 0 && version == 0 && empty), owner != Self.applicationID || version != Self.revision {
            throw PersistenceError.unsupportedSchema
        }
        try connection.statement("PRAGMA journal_mode=DELETE") { row in
            guard try row.step(), try row.text(0, byteLimit: 16) == "delete" else { throw PersistenceError.unavailable }
        }
        try connection.execute("PRAGMA synchronous=FULL")
        try connection.execute("PRAGMA foreign_keys=ON")
        try connection.execute("PRAGMA temp_store=MEMORY")
        try connection.execute("PRAGMA cache_size=-256")
        let pageSize = try scalar("PRAGMA page_size")
        guard pageSize >= 512, pageSize <= 65_536 else { throw PersistenceError.corrupt }
        let pages = Int64(options.databaseByteLimit) / pageSize
        guard try scalar("PRAGMA max_page_count=\(pages)") <= pages else { throw PersistenceError.storageFull }
        if owner == 0 && version == 0 && empty {
            try connection.transaction {
                try connection.execute("""
                    CREATE TABLE recording_runs (
                        ordinal INTEGER PRIMARY KEY, id TEXT NOT NULL UNIQUE,
                        platform TEXT NOT NULL, process_id INTEGER NOT NULL,
                        started_at INTEGER NOT NULL, last_recorded_at INTEGER NOT NULL,
                        last_written_at INTEGER NOT NULL, evicted_events INTEGER NOT NULL DEFAULT 0)
                    """)
                try connection.execute("""
                    CREATE TABLE breadcrumbs (
                        ordinal INTEGER PRIMARY KEY, id TEXT NOT NULL UNIQUE,
                        run_id TEXT NOT NULL REFERENCES recording_runs(id) ON DELETE CASCADE,
                        session_id TEXT NOT NULL, sequence INTEGER NOT NULL CHECK(sequence > 0),
                        timestamp INTEGER NOT NULL, elapsed TEXT, kind TEXT NOT NULL,
                        label TEXT NOT NULL, message TEXT, private INTEGER NOT NULL CHECK(private IN (0,1)),
                        truncated INTEGER NOT NULL CHECK(truncated IN (0,1)), value INTEGER,
                        text_bytes INTEGER NOT NULL CHECK(text_bytes >= 0))
                    """)
                try connection.execute("CREATE INDEX breadcrumb_run_order ON breadcrumbs(run_id, ordinal)")
                try connection.execute("PRAGMA application_id=\(Self.applicationID)")
                try connection.execute("PRAGMA user_version=\(Self.revision)")
            }
        }
    }
    func write(_ events: [BreadcrumbEvent], now: Int64) throws {
        guard !events.isEmpty else { return }
        guard events.count <= options.pendingEventLimit,
              events.allSatisfy({ $0.retention == .restartRelevant && $0.textByteCount <= options.textBytesPerRun }) else {
            throw PersistenceError.invalidRecord
        }
        try connection.transaction {
            try connection.execute("""
                INSERT OR IGNORE INTO recording_runs(id, platform, process_id, started_at, last_recorded_at, last_written_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, [.text(runID), .text(platform.rawValue), .integer(processID), .integer(startedAt),
                         .integer(events[0].timestampMillis), .integer(now)])
            try pruneRuns(now: now)
            for event in events {
                try connection.execute("""
                    INSERT INTO breadcrumbs(id, run_id, session_id, sequence, timestamp, elapsed, kind, label, message, private, truncated, value, text_bytes)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, [.text(event.id), .text(runID), .text(event.sessionID), .integer(event.sequence), .integer(event.timestampMillis),
                             event.sessionElapsedMillis.map { .text(String($0)) } ?? .null, .text(event.kind.rawValue), .text(event.label),
                             event.message.map(SQLiteValue.text) ?? .null, .integer(event.isPrivate ? 1 : 0),
                             .integer(event.isTruncated ? 1 : 0), event.value.map(SQLiteValue.integer) ?? .null, .integer(Int64(event.textByteCount))])
            }
            try trimAndAccount(run: runID)
            try connection.execute("""
                UPDATE recording_runs SET last_recorded_at=?, last_written_at=? WHERE id=?
                """, [.integer(events[events.count - 1].timestampMillis), .integer(now), .text(runID)])
        }
    }
    private func pruneRuns(now: Int64) throws {
        if now >= options.maximumAgeMillis {
            try connection.execute("DELETE FROM recording_runs WHERE id<>? AND last_written_at<?",
                                   [.text(runID), .integer(now - options.maximumAgeMillis)])
        }
        let currentCount = try connection.statement("SELECT count(*) FROM recording_runs WHERE id=?", [.text(runID)]) { row in
            guard try row.step() else { throw PersistenceError.corrupt }
            return try row.integer(0)
        }
        try connection.execute("""
            DELETE FROM recording_runs WHERE id IN (
                SELECT id FROM recording_runs WHERE id<>? ORDER BY ordinal DESC LIMIT -1 OFFSET ?)
            """, [.text(runID), .integer(Int64(options.retainedRuns) - currentCount)])
    }
    private func trimAndAccount(run id: String) throws {
        var count = 0
        var bytes: Int64 = 0
        var cutoff: Int64?
        var evicted: Int64 = 0
        // A valid database has at most the largest configured window plus one pending batch.
        try connection.statement("SELECT ordinal, text_bytes FROM breadcrumbs WHERE run_id=? ORDER BY ordinal DESC LIMIT 2257", [.text(id)]) { row in
            while try row.step() {
                guard count + Int(evicted) < 2_256 else { throw PersistenceError.invalidRecord }
                let size = try row.integer(1)
                guard size >= 0, size <= 4_160 else { throw PersistenceError.invalidRecord }
                if cutoff != nil || count >= options.recordsPerRun || bytes > Int64(options.textBytesPerRun) - size {
                    if cutoff == nil { cutoff = try row.integer(0) }
                    evicted += 1
                } else { count += 1; bytes += size }
            }
        }
        if let cutoff {
            try connection.execute("DELETE FROM breadcrumbs WHERE run_id=? AND ordinal<=?", [.text(id), .integer(cutoff)])
            try connection.execute("""
                UPDATE recording_runs SET evicted_events=CASE WHEN evicted_events>9223372036854775807-?
                THEN 9223372036854775807 ELSE evicted_events+? END WHERE id=?
                """, [.integer(evicted), .integer(evicted), .text(id)])
        }
    }
    func runs() throws -> [RetainedRun] {
        try connection.statement(Self.runQuery + " ORDER BY r.ordinal DESC LIMIT ?", [.integer(Int64(options.retainedRuns))]) { row in
            var runs: [RetainedRun] = []
            while try row.step() { runs.append(try readRunSummary(row)) }
            return runs
        }
    }
    func read(id: String) throws -> RetainedBreadcrumbs? {
        guard Array(id.utf8.prefix(37)).count == 36, UUID(uuidString: id) != nil else { throw PersistenceError.invalidRecord }
        let run = try connection.statement(Self.runQuery + " WHERE r.id=?", [.text(id)]) { row -> RetainedRun? in
            guard try row.step() else { return nil }
            return try readRunSummary(row)
        }
        guard let run else { return nil }
        return try connection.statement("""
            SELECT id, session_id, sequence, timestamp, elapsed, kind, label, message, private, truncated, value, text_bytes
            FROM breadcrumbs WHERE run_id=? ORDER BY ordinal LIMIT ?
            """, [.text(id), .integer(Int64(options.recordsPerRun + 1))]) { row in
            var events: [BreadcrumbEvent] = []
            var bytes = 0
            while try row.step() {
                guard events.count < options.recordsPerRun else { throw PersistenceError.invalidRecord }
                let elapsedText = try row.isNull(4) ? nil : row.text(4, byteLimit: 20)
                let elapsed = elapsedText.flatMap(UInt64.init)
                if let elapsedText { guard let elapsed, String(elapsed) == elapsedText else { throw PersistenceError.invalidRecord } }
                guard let kind = BreadcrumbKind(rawValue: try row.text(5, byteLimit: 32)) else { throw PersistenceError.invalidRecord }
                let event = try BreadcrumbEvent.restore(id: row.text(0, byteLimit: 36), sessionID: row.text(1, byteLimit: 36),
                    sequence: row.integer(2), timestampMillis: row.integer(3), sessionElapsedMillis: elapsed, kind: kind,
                    label: row.text(6, byteLimit: 64), message: row.isNull(7) ? nil : row.text(7, byteLimit: 4_096),
                    isPrivate: row.flag(8), isTruncated: row.flag(9), value: row.isNull(10) ? nil : row.integer(10), retention: .restartRelevant)
                guard Int64(event.textByteCount) == (try row.integer(11)), bytes <= options.textBytesPerRun - event.textByteCount else {
                    throw PersistenceError.invalidRecord
                }
                bytes += event.textByteCount; events.append(event)
            }
            guard events.count == run.eventCount else { throw PersistenceError.invalidRecord }
            return RetainedBreadcrumbs(run: run, events: events)
        }
    }
    private static let runQuery = """
        SELECT r.id, r.platform, r.process_id, r.started_at, r.last_recorded_at, r.evicted_events,
        (SELECT count(*) FROM breadcrumbs b WHERE b.run_id=r.id) FROM recording_runs r
        """
    private func readRunSummary(_ row: SQLiteStatement) throws -> RetainedRun {
        let id = try row.text(0, byteLimit: 36)
        guard id.utf8.count == 36, UUID(uuidString: id) != nil,
              let platform = EnvironmentPlatform(rawValue: try row.text(1, byteLimit: 16)) else { throw PersistenceError.invalidRecord }
        let processID = try row.integer(2)
        let evicted = try row.integer(5)
        let count = try row.integer(6)
        guard processID > 0, evicted >= 0, count >= 0, count <= options.recordsPerRun else { throw PersistenceError.invalidRecord }
        return try RetainedRun(id: id, platform: platform, processID: processID, recordingStartedAtMillis: row.integer(3),
            lastRecordedAtMillis: row.integer(4), eventCount: Int(count), evictedEvents: evicted, isCurrent: id == runID)
    }
}
