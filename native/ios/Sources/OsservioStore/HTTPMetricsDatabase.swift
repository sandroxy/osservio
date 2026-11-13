import Foundation
import OsservioCore

/// Normalized, bounded phase evidence, sharing the incident transaction and ownership lease.
final class HTTPMetricsDatabase {
    static let schema = [
        "ALTER TABLE incident_http ADD COLUMN metrics_present INTEGER NOT NULL DEFAULT 0",
        "CREATE TABLE incident_http_metrics (incident_id TEXT NOT NULL, event_position INTEGER NOT NULL, protocol TEXT, cache TEXT NOT NULL, reused INTEGER, limited INTEGER NOT NULL, phase_count INTEGER NOT NULL, PRIMARY KEY(incident_id,event_position), FOREIGN KEY(incident_id,event_position) REFERENCES incident_http(incident_id,position) ON DELETE CASCADE)",
        "CREATE TABLE incident_http_phases (incident_id TEXT NOT NULL, event_position INTEGER NOT NULL, position INTEGER NOT NULL, phase TEXT NOT NULL, started INTEGER NOT NULL, ended INTEGER, PRIMARY KEY(incident_id,event_position,position), FOREIGN KEY(incident_id,event_position) REFERENCES incident_http_metrics(incident_id,event_position) ON DELETE CASCADE)"
    ]
    private let connection: SQLiteConnection
    init(_ connection: SQLiteConnection) { self.connection = connection }
    func save(_ metrics: HTTPMetrics?, incidentID: String, position: Int) throws {
        guard let metrics else { return }
        let key: [SQLiteValue] = [.text(incidentID), .integer(Int64(position))]
        try connection.execute("INSERT INTO incident_http_metrics VALUES (?,?,?,?,?,?,?)", key + [
            metrics.protocolName.map(SQLiteValue.text) ?? .null, .text(metrics.cache.rawValue),
            metrics.connectionReused.map { .integer($0 ? 1 : 0) } ?? .null, .integer(metrics.limited ? 1 : 0), .integer(Int64(metrics.phases.count))])
        for (index, phase) in metrics.phases.enumerated() {
            try connection.execute("INSERT INTO incident_http_phases VALUES (?,?,?,?,?,?)", key + [
                .integer(Int64(index)), .text(phase.phase.rawValue), .integer(phase.startMillis), phase.endMillis.map(SQLiteValue.integer) ?? .null])
        }
    }
    func read(_ id: String, eventCount: Int) throws -> [Int: HTTPMetrics] {
        var values: [Int: HTTPMetrics] = [:], phaseCount = 0
        try connection.statement("SELECT event_position,protocol,cache,reused,limited,phase_count FROM incident_http_metrics WHERE incident_id=? ORDER BY event_position LIMIT 2001", [.text(id)]) { row in
            while try row.step() {
                let position = try row.integer(0)
                guard position >= 0, position < eventCount, values[Int(position)] == nil,
                      let cache = try HTTPCache(rawValue: row.text(2, byteLimit: 32)) else { throw PersistenceError.invalidRecord }
                var phases: [HTTPPhaseTiming] = []
                try connection.statement("SELECT position,phase,started,ended FROM incident_http_phases WHERE incident_id=? AND event_position=? ORDER BY position LIMIT 33", [.text(id), .integer(position)]) { item in
                    while try item.step() {
                        guard phases.count < 32, try item.integer(0) == phases.count,
                              let phase = try HTTPPhase(rawValue: item.text(1, byteLimit: 32)) else { throw PersistenceError.invalidRecord }
                        phases.append(try HTTPPhaseTiming(phase: phase, startMillis: item.integer(2), endMillis: item.isNull(3) ? nil : item.integer(3)))
                    }
                }
                guard try row.integer(5) == phases.count else { throw PersistenceError.invalidRecord }
                phaseCount += phases.count
                values[Int(position)] = try HTTPMetrics(phases: phases, protocolName: row.isNull(1) ? nil : row.text(1, byteLimit: 32),
                    cache: cache, connectionReused: row.isNull(3) ? nil : row.flag(3), limited: row.flag(4))
            }
        }
        try connection.statement("SELECT count(*) FROM incident_http_phases WHERE incident_id=?", [.text(id)]) { row in
            guard try row.step(), try row.integer(0) == phaseCount else { throw PersistenceError.invalidRecord }
        }
        return values
    }
}
