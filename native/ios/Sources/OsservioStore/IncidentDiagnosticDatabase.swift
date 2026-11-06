import Foundation
import OsservioCore

/// Worker-owned storage for reviewed system facts. Raw platform reports never enter this table.
final class IncidentDiagnosticDatabase {
    private let connection: SQLiteConnection
    private let signals: SignalCrashDatabase
    init(connection: SQLiteConnection) { self.connection = connection; signals = SignalCrashDatabase(connection: connection) }
    static let schema = [
        "ALTER TABLE incidents ADD COLUMN diagnostic_kind TEXT",
        """
        CREATE TABLE incident_diagnostics (incident_id TEXT PRIMARY KEY NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
            source TEXT NOT NULL, interval_start INTEGER NOT NULL, interval_end INTEGER NOT NULL,
            reason INTEGER, status INTEGER, process_id INTEGER, importance INTEGER, pss INTEGER, rss INTEGER,
            signal INTEGER, exception_type INTEGER, exception_code TEXT, hang_duration INTEGER)
        """,
        """
        CREATE TABLE diagnostic_metadata (incident_id TEXT NOT NULL REFERENCES incident_diagnostics(incident_id) ON DELETE CASCADE,
            key TEXT NOT NULL, value TEXT NOT NULL, truncated INTEGER NOT NULL, PRIMARY KEY(incident_id, key))
        """
    ]
    func save(_ snapshot: IncidentSnapshot) throws {
        guard let diagnostic = snapshot.systemDiagnostic else { return }
        let exit = diagnostic.androidExit, metric = diagnostic.metricKit
        try connection.execute("""
            INSERT INTO incident_diagnostics(incident_id, source, interval_start, interval_end, reason, status, process_id,
                importance, pss, rss, signal, exception_type, exception_code, hang_duration)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, [.text(snapshot.id), .text(diagnostic.source.rawValue), .integer(diagnostic.intervalStartMillis),
                .integer(diagnostic.intervalEndMillis), integer(exit.map { Int64($0.reasonCode) }), integer(exit.map { Int64($0.status) }),
                integer(exit.map { Int64($0.processID) }), integer(exit.map { Int64($0.importance) }), integer(exit?.pssKiB), integer(exit?.rssKiB),
                integer(metric?.signal.map(Int64.init)), integer(metric?.exceptionType), metric?.exceptionCode.map(SQLiteValue.text) ?? .null,
                integer(metric?.hangDurationMillis)])
        for (key, value) in metric?.metadata ?? diagnostic.appleSignal?.metadata ?? [:] {
            try connection.execute("INSERT INTO diagnostic_metadata(incident_id, key, value, truncated) VALUES (?, ?, ?, ?)",
                [.text(snapshot.id), .text(key.rawValue), .text(value.value), .integer(value.isTruncated ? 1 : 0)])
        }
        if let signal = diagnostic.appleSignal { try signals.save(signal, id: snapshot.id) }
    }
    func read(id: String, expectedKind: SystemDiagnosticKind?) throws -> SystemDiagnostic? {
        let metadata = try readMetadata(id: id)
        return try connection.statement("""
            SELECT source, interval_start, interval_end, reason, status, process_id, importance, pss, rss,
                signal, exception_type, exception_code, hang_duration FROM incident_diagnostics WHERE incident_id=? LIMIT 2
            """, [.text(id)]) { row in
            guard try row.step() else {
                guard expectedKind == nil, metadata.isEmpty else { throw PersistenceError.invalidRecord }
                try signals.requireAbsent(id: id)
                return nil
            }
            guard let expectedKind, let source = SystemDiagnosticSource(rawValue: try row.text(0, byteLimit: 32)) else {
                throw PersistenceError.invalidRecord
            }
            let start = try row.integer(1), end = try row.integer(2)
            let result: SystemDiagnostic
            if source != .appleSignal { try signals.requireAbsent(id: id) }
            switch source {
            case .androidExitInfo:
                guard start == end, metadata.isEmpty, (9...12).allSatisfy({ row.isNull($0) }) else { throw PersistenceError.invalidRecord }
                result = try .androidExit(timestampMillis: start, reasonCode: int32(row, 3), status: int32(row, 4),
                    processID: int32(row, 5), importance: int32(row, 6), pssKiB: optionalInteger(row, 7), rssKiB: optionalInteger(row, 8))
            case .metricKit:
                guard (3...8).allSatisfy({ row.isNull($0) }) else { throw PersistenceError.invalidRecord }
                result = try .metricKit(intervalStartMillis: start, intervalEndMillis: end, kind: expectedKind, metadata: metadata,
                    signal: row.isNull(9) ? nil : int32(row, 9), exceptionType: optionalInteger(row, 10),
                    exceptionCode: row.isNull(11) ? nil : row.text(11, byteLimit: 20), hangDurationMillis: optionalInteger(row, 12))
            case .appleSignal:
                guard start == end, (3...12).allSatisfy({ row.isNull($0) }) else { throw PersistenceError.invalidRecord }
                result = .appleSignal(try signals.read(id: id, timestampMillis: start, metadata: metadata))
            }
            guard result.kind == expectedKind, try !row.step() else { throw PersistenceError.invalidRecord }
            return result
        }
    }
    private func readMetadata(id: String) throws -> [SystemDiagnostic.MetadataKey: SystemDiagnostic.Text] {
        try connection.statement("SELECT key, value, truncated FROM diagnostic_metadata WHERE incident_id=? LIMIT 5", [.text(id)]) { row in
            var values: [SystemDiagnostic.MetadataKey: SystemDiagnostic.Text] = [:]
            while try row.step() {
                guard values.count < 4, let key = SystemDiagnostic.MetadataKey(rawValue: try row.text(0, byteLimit: 32)), values[key] == nil else {
                    throw PersistenceError.invalidRecord
                }
                values[key] = try .restore(value: row.text(1, byteLimit: 256), isTruncated: row.flag(2))
            }
            return values
        }
    }
    private func integer(_ value: Int64?) -> SQLiteValue { value.map(SQLiteValue.integer) ?? .null }
    private func optionalInteger(_ row: SQLiteStatement, _ index: Int32) throws -> Int64? { try row.isNull(index) ? nil : row.integer(index) }
    private func int32(_ row: SQLiteStatement, _ index: Int32) throws -> Int32 {
        guard let value = Int32(exactly: try row.integer(index)) else { throw PersistenceError.invalidRecord }; return value
    }
}
